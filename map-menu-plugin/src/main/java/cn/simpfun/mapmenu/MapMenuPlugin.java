package cn.simpfun.mapmenu;

import arc.Events;
import arc.files.Fi;
import arc.graphics.Pixmap;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.core.NetServer;
import mindustry.core.Version;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.game.EventType.ServerLoadEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Gamemode;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.io.MapIO;
import mindustry.io.SaveIO;
import mindustry.io.SaveMeta;
import mindustry.maps.Map;
import mindustry.mod.Plugin;
import mindustry.server.ServerControl;
import mindustry.ui.Menus;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;

public class MapMenuPlugin extends Plugin{
    private static final int MAPS_PER_PAGE = 8;
    private static final int SAVE_SLOT_COUNT = 10;
    // WorldLoadEvent fires before existing clients finish receiving the new world. A menu sent
    // immediately can be discarded by the client's loading screen, so retry a few times.
    private static final float[] PVP_TEAM_MENU_DELAYS = {2f, 6f, 12f};
    private static final ObjectSet<String> BUILTIN_PVP_MAPS = ObjectSet.with("veins", "glacier", "passage");

    private enum MapCategory{
        ALL, BUILTIN, SURVIVAL, ATTACK, PVP
    }

    private enum AdminContext{ none, pickDefaultMap }

    private enum PendingInput{ none, votes, voteTime, cooldown, countdown, rotation }

    // 菜单 / 输入框 id
    private int mapMenuId;
    private int modeMenuId;
    private int pvpTeamMenuId;
    private int voteMenuId;
    private int adminMenuId;
    private int adminPickMenuId;
    private int voteSettingsMenuId;
    private int categoryMenuId;
    private int saveSlotsMenuId;
    private int saveSlotMenuId;
    private int inputMenuId;

    // 投票状态
    private final ObjectSet<String> votes = new ObjectSet<>();
    private Map votedMap;
    private Gamemode votedMode;
    private Instant voteEndsAt = Instant.EPOCH;

    // 切换状态
    private boolean countdownActive;
    private int countdownGeneration;
    private Instant nextSwitchAt = Instant.EPOCH;

    // PVP 自主选队状态；未选择的玩家暂处无核心的闲置队伍，不会在错误核心出生
    private final ObjectSet<String> selectedPvpPlayers = new ObjectSet<>();
    private NetServer.TeamAssigner defaultTeamAssigner;
    private NetServer.TeamAssigner pvpTeamAssigner;

    // 玩家界面状态
    private final ObjectIntMap<String> openPages = new ObjectIntMap<>();
    private final ObjectMap<String, Map> selectedMaps = new ObjectMap<>();
    private final ObjectMap<String, AdminContext> adminContexts = new ObjectMap<>();
    private final ObjectMap<String, MapCategory> mapCategories = new ObjectMap<>();
    private final ObjectMap<Map, MapCategory> categoryCache = new ObjectMap<>();
    private final ObjectMap<String, PendingInput> pendingInputs = new ObjectMap<>();
    private final ObjectIntMap<String> pendingSlots = new ObjectIntMap<>();
    private final ObjectMap<String, Seq<Team>> pvpTeamMenuChoices = new ObjectMap<>();
    private int pvpTeamPromptGeneration;

    // map-choice pending state: waiting for a /maps pick at startup or after game over
    private boolean pendingMapChoice;

    // PVP 地图核心阵营数缓存
    private final ObjectIntMap<Map> coreTeamCounts = new ObjectIntMap<>();

    @Override
    public void init(){
        mapMenuId = Menus.registerMenu(this::handleMapMenu);
        modeMenuId = Menus.registerMenu(this::handleModeMenu);
        pvpTeamMenuId = Menus.registerMenu(this::handlePvpTeamMenu);
        voteMenuId = Menus.registerMenu(this::handleVoteMenu);
        adminMenuId = Menus.registerMenu(this::handleAdminMenu);
        adminPickMenuId = Menus.registerMenu(this::handleMapMenu);
        voteSettingsMenuId = Menus.registerMenu(this::handleVoteSettingsMenu);
        saveSlotsMenuId = Menus.registerMenu(this::handleSaveSlotsMenu);
        saveSlotMenuId = Menus.registerMenu(this::handleSaveSlotMenu);
        categoryMenuId = Menus.registerMenu(this::handleCategoryMenu);
        inputMenuId = Menus.registerTextInput(this::handleTextInput);

        Events.on(PlayerLeave.class, event -> {
            votes.remove(event.player.uuid());
            selectedPvpPlayers.remove(event.player.uuid());
            openPages.remove(event.player.uuid(), 0);
            selectedMaps.remove(event.player.uuid());
            adminContexts.remove(event.player.uuid());
            pendingInputs.remove(event.player.uuid());
            pendingSlots.remove(event.player.uuid());
            mapCategories.remove(event.player.uuid());
            pvpTeamMenuChoices.remove(event.player.uuid());
            checkVote();
            if(Vars.state.rules.pvp) Timer.schedule(this::refreshPendingPvpTeamMenus, 0.1f);
        });
        Events.on(WorldLoadEvent.class, event -> {
            int generation = ++pvpTeamPromptGeneration;
            // ServerControl.play() assigns the new map rules immediately after
            // World.loadMap() returns. WorldLoadEvent is fired from inside
            // World.loadMap(), so Vars.state.rules can still describe the old
            // map here. Defer the check until the caller has finished updating
            // the state, otherwise existing players never get a PVP prompt.
            Timer.schedule(() -> {
                if(generation != pvpTeamPromptGeneration) return;
                if(Vars.state.rules.pvp){
                    preparePvpTeamSelection();
                    schedulePvpTeamMenuPrompts(generation);
                }else{
                    selectedPvpPlayers.clear();
                }
            }, 0.1f);
        });
        Events.on(PlayerJoin.class, event -> {
            if(Vars.state.rules.pvp){
                preparePlayerForPvpTeamChoice(event.player);
                Timer.schedule(() -> showPvpTeamMenu(event.player), 0.5f);
            }
            if(pendingMapChoice){
                Timer.schedule(() -> {
                    if(pendingMapChoice) showCategoryMenu(event.player);
                }, 1.5f);
            }
        });
        Events.on(ServerLoadEvent.class, event -> {
            installPvpTeamAssigner();
            installMapProvider();
            pendingMapChoice = true;
            installGameOverListener();
            Timer.schedule(this::applyDefaultMapOnStart, 1f);
        });
        Timer.schedule(this::expireVote, 1f, 1f);
        warmCategoryCache();
        Timer.schedule(this::logCategorySummary, 8f);
        preloadBuiltinPvpCounts();
        Log.info("[MapMenu] Loaded for Mindustry build @.", Version.build);
    }

    @Override
    public void registerClientCommands(CommandHandler handler){
        handler.<Player>register("maps", "打开地图选择菜单。", (args, player) -> {
            if(rejectDuringCountdown(player)) return;
            showCategoryMenu(player);
        });
        handler.<Player>register("map", "<编号/名称>", "按编号或名称直接选择地图。", (args, player) -> {
            if(args.length == 0 || args[0].isEmpty()){
                player.sendMessage("[scarlet]用法：/map <编号> 或 /map <名称>[]");
                return;
            }
            selectMapByQuery(player, args[0]);
        });
        handler.<Player>register("mapvote", "查看当前换图投票。", (args, player) -> {
            if(rejectDuringCountdown(player)) return;
            showVoteStatus(player);
        });
        handler.<Player>register("team", "打开 PVP 选队菜单。", (args, player) -> showPvpTeamMenu(player));
        handler.<Player>register("mmconfig", "查看地图管理配置。", (args, player) -> showConfig(player));
        handler.<Player>register("admin", "打开管理员地图管理菜单。", (args, player) -> showAdminMenu(player));
        handler.<Player>register("setvotes", "<票数|majority>", "设置换图所需票数，majority 表示多数票。", (args, player) -> {
            if(!requireAdmin(player)) return;
            if(args[0].equalsIgnoreCase("majority") || args[0].equalsIgnoreCase("多数")){
                MapMenuConfig.setVotesRequired(0);
                player.sendMessage("[green]换图通过条件已设为多数票。[]");
            }else{
                int v = parsePositive(args[0]);
                if(v < 1){
                    player.sendMessage("[scarlet]请输入有效的票数（至少 1）。[]");
                    return;
                }
                MapMenuConfig.setVotesRequired(v);
                player.sendMessage("[green]换图所需票数已设为 " + v + " 票。[]");
            }
        });
        handler.<Player>register("setvotetime", "<秒>", "设置换图投票时长（秒）。", (args, player) -> {
            if(!requireAdmin(player)) return;
            int v = parsePositive(args[0]);
            if(v < 5){
                player.sendMessage("[scarlet]投票时长至少 5 秒。[]");
                return;
            }
            MapMenuConfig.setVoteSeconds(v);
            player.sendMessage("[green]投票时长已设为 " + v + " 秒。[]");
        });
        handler.<Player>register("setcooldown", "<秒>", "设置换图冷却时间（秒）。", (args, player) -> {
            if(!requireAdmin(player)) return;
            int v = parsePositive(args[0]);
            if(v < 0){
                player.sendMessage("[scarlet]请输入有效的秒数。[]");
                return;
            }
            MapMenuConfig.setCooldownSeconds(v);
            player.sendMessage("[green]换图冷却已设为 " + v + " 秒。[]");
        });
        handler.<Player>register("setcountdown", "<秒>", "设置切换倒计时秒数。", (args, player) -> {
            if(!requireAdmin(player)) return;
            int v = parsePositive(args[0]);
            if(v < 3){
                player.sendMessage("[scarlet]倒计时至少 3 秒。[]");
                return;
            }
            MapMenuConfig.setCountdownSeconds(v);
            player.sendMessage("[green]切换倒计时已设为 " + v + " 秒。[]");
        });
        handler.<Player>register("setdefault", "<编号/名称|off>", "设置或清除默认地图。", (args, player) -> {
            if(!requireAdmin(player)) return;
            if(args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("清除")){
                MapMenuConfig.setDefaultMapKey("");
                player.sendMessage("[green]默认地图已清除。[]");
                return;
            }
            Map map = resolveMapToken(args[0], availableMaps());
            if(map == null){
                player.sendMessage("[scarlet]没有找到地图：[]" + args[0]);
                return;
            }
            MapMenuConfig.setDefaultMapKey(MapMenuConfig.keyOf(map));
            player.sendMessage("[green]默认地图已设置为：[]" + map.name());
        });
        handler.<Player>register("setrandom", "<on/off>", "开启或关闭随机换图。", (args, player) -> {
            if(!requireAdmin(player)) return;
            boolean on = args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("开") || args[0].equalsIgnoreCase("true");
            boolean off = args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("关") || args[0].equalsIgnoreCase("false");
            if(!on && !off){
                player.sendMessage("[scarlet]请输入 on 或 off。[]");
                return;
            }
            MapMenuConfig.setRandomEnabled(on);
            player.sendMessage("[green]随机换图已" + (on ? "开启。[]" : "关闭。[]"));
        });
        handler.<Player>register("setrotation", "<列表|off>", "设置地图轮换列表（编号或名称，空格分隔）或 off。", (args, player) -> {
            if(!requireAdmin(player)) return;
            if(args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("关闭")){
                MapMenuConfig.setRotationRaw("");
                player.sendMessage("[green]地图轮换已关闭。[]");
                return;
            }
            applyRotationFromTokens(player, args);
        });
        handler.<Player>register("mmrestart", "重启当前地图。", (args, player) -> {
            if(!requireAdmin(player)) return;
            restartCurrentMap(player);
        });
    }
    // ---- 权限与通用 ----

    private boolean rejectDuringCountdown(Player player){
        if(!countdownActive) return false;
        player.sendMessage("[scarlet]换图倒计时进行中，暂时不能使用地图菜单。[]");
        return true;
    }

    private boolean requireAdmin(Player player){
        if(player.admin) return true;
        player.sendMessage("[scarlet]该命令/功能仅限管理员使用。[]");
        return false;
    }

    private Seq<Map> availableMaps(){
        Seq<Map> maps = Vars.maps.defaultMaps().copy();
        maps.addAll(Vars.maps.customMaps());
        maps.sort(Comparator.comparing(Map::name, String.CASE_INSENSITIVE_ORDER));
        return maps;
    }

    private boolean isCustomMap(Map map){
        return Vars.maps.customMaps().contains(map, true);
    }

    private boolean isBuiltinPvpMap(Map map){
        return !isCustomMap(map) && BUILTIN_PVP_MAPS.contains(map.file.nameWithoutExtension());
    }

    private void preloadBuiltinPvpCounts(){
        for(Map map : Vars.maps.defaultMaps()){
            if(isBuiltinPvpMap(map)){
                Log.info("[MapMenu] PVP map @: @ teams.", map.plainName(), pvpTeamCount(map));
            }
        }
    }

    private String mapTypeLabel(Map map){
        if(isCustomMap(map)) return "[cyan][自定义][] ";
        if(isBuiltinPvpMap(map)){
            return "[green][内置 " + pvpTeamCount(map) + "人PVP][] ";
        }
        return "[green][内置][] ";
    }

    private int pvpTeamCount(Map map){
        return coreTeamCount(map);
    }

    private int coreTeamCount(Map map){
        if(coreTeamCounts.containsKey(map)) return coreTeamCounts.get(map, 0);
        int teams = scanPvpTeamCount(map);
        coreTeamCounts.put(map, teams);
        return teams;
    }

    private int scanPvpTeamCount(Map map){
        Pixmap preview = null;
        try{
            preview = MapIO.generatePreview(map);
            return countSelectableMapTeams(map);
        }catch(Throwable error){
            Log.err("[MapMenu] Failed to scan PVP teams for map @", map.name(), error);
            return countSelectableMapTeams(map);
        }finally{
            if(preview != null) preview.dispose();
        }
    }


    private void logCategorySummary(){
        int builtin = 0, survival = 0, attack = 0, pvp = 0;
        for(Map map : availableMaps()){
            switch(categoryOf(map)){
                case BUILTIN -> builtin++;
                case SURVIVAL -> survival++;
                case ATTACK -> attack++;
                case PVP -> pvp++;
            }
        }
        Log.info("[MapMenu] 地图分类统计：内置 @ 张，自定义生存 @ 张，自定义进攻 @ 张，自定义PVP @ 张。",
                builtin, survival, attack, pvp);
    }
    // ---- 地图分类 ----

    private void showCategoryMenu(Player player){
        if(rejectDuringCountdown(player)) return;
        int builtin = 0, survival = 0, attack = 0, pvp = 0;
        for(Map map : availableMaps()){
            switch(categoryOf(map)){
                case BUILTIN -> builtin++;
                case SURVIVAL -> survival++;
                case ATTACK -> attack++;
                case PVP -> pvp++;
            }
        }
        String[][] buttons = {
                {"[green]游戏内置地图[]\n[gray]" + builtin + " 张[]", "[cyan]生存地图[]\n[gray]" + survival + " 张[]"},
                {"[orange]进攻地图[]\n[gray]" + attack + " 张[]", "[scarlet]PVP地图[]\n[gray]" + pvp + " 张[]"},
                {"[lightgray]关闭[]"}
        };
        Call.menu(player.con(), categoryMenuId, "[accent]地图分类[]",
                "先选择分类，再浏览地图\n列表编号可直接用于 /map <编号>", buttons);
    }

    private void handleCategoryMenu(Player player, int option){
        if(rejectDuringCountdown(player)) return;
        MapCategory category = switch(option){
            case 0 -> MapCategory.BUILTIN;
            case 1 -> MapCategory.SURVIVAL;
            case 2 -> MapCategory.ATTACK;
            case 3 -> MapCategory.PVP;
            default -> null;
        };
        if(category != null) showMapMenu(player, 0, AdminContext.none, category);
    }

    private String categoryLabel(MapCategory category){
        return switch(category){
            case ALL -> "全部地图";
            case BUILTIN -> "游戏内置地图";
            case SURVIVAL -> "生存地图";
            case ATTACK -> "进攻地图";
            case PVP -> "PVP地图";
        };
    }

    private static final String[] CATEGORY_FOLDERS = {"survival", "attack", "pvp"};

    private MapCategory folderCategoryOf(Map map){
        if(map.file == null) return null;
        String path = map.file.path().replace('\\', '/');
        for(int i = 0; i < CATEGORY_FOLDERS.length; i++){
            if(path.contains("/" + CATEGORY_FOLDERS[i] + "/")){
                return switch(i){
                    case 0 -> MapCategory.SURVIVAL;
                    case 1 -> MapCategory.ATTACK;
                    default -> MapCategory.PVP;
                };
            }
        }
        return null;
    }

    private MapCategory categoryOf(Map map){
        if(!map.custom) return MapCategory.BUILTIN;
        if(categoryCache.containsKey(map)) return categoryCache.get(map, MapCategory.SURVIVAL);
        // 优先按 config/maps 下的分类文件夹识别：maps/survival、maps/attack、maps/pvp
        MapCategory folder = folderCategoryOf(map);
        if(folder != null){
            categoryCache.put(map, folder);
            return folder;
        }
        boolean pvpFlag = false, attackFlag = false;
        try{
            Rules own = map.rules();
            pvpFlag = own.pvp;
            attackFlag = own.attackMode;
        }catch(Throwable ignored){
        }
        MapCategory result;
        if(pvpFlag && !attackFlag){
            result = MapCategory.PVP;
        }else if(attackFlag && !pvpFlag){
            result = MapCategory.ATTACK;
        }else if(pvpTeamCount(map) >= 2){
            // 多方核心：具备"玩家核心+敌方核心"布局的按进攻归类，其余按 PVP
            result = supportsAttackMode(map) ? MapCategory.ATTACK : MapCategory.PVP;
        }else{
            result = MapCategory.SURVIVAL;
        }
        categoryCache.put(map, result);
        return result;
    }

    private void warmCategoryCache(){
        Seq<Map> customs = Vars.maps.customMaps();
        if(customs.isEmpty()) return;
        final int batch = 4;
        for(int i = 0; i < customs.size; i += batch){
            final int start = i;
            Timer.schedule(() -> {
                for(int j = start; j < Math.min(customs.size, start + batch); j++){
                    categoryOf(customs.get(j));
                }
            }, 2f + (start / (float)batch) * 0.6f);
        }
    }
    // ---- 地图选择菜单 ----

    private void showMapMenu(Player player, int requestedPage, AdminContext context, MapCategory category){
        Seq<Map> all = availableMaps();
        if(all.isEmpty()){
            player.sendMessage("[scarlet]服务器没有可用地图。[]");
            return;
        }
        Seq<Map> maps = category == MapCategory.ALL ? all : all.select(map -> categoryOf(map) == category);
        if(maps.isEmpty()){
            player.sendMessage("[scarlet]「[]" + categoryLabel(category) + "[]」分类下没有地图。[]");
            showCategoryMenu(player);
            return;
        }
        int pageCount = Math.max(1, (maps.size + MAPS_PER_PAGE - 1) / MAPS_PER_PAGE);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        int start = page * MAPS_PER_PAGE;
        int itemCount = Math.min(MAPS_PER_PAGE, maps.size - start);
        String[][] buttons = new String[itemCount + 1][];

        for(int row = 0; row < itemCount; row++){
            Map map = maps.get(start + row);
            int num = all.indexOf(map) + 1;
            buttons[row] = new String[]{"[accent]" + num + ".[] " + mapTypeLabel(map) + map.name()};
        }
        buttons[itemCount] = new String[]{
                page > 0 ? "[lightgray]< 上一页[]" : "[scarlet]返回[]",
                page + 1 < pageCount ? "[lightgray]下一页 >[]" : "[scarlet]关闭[]"
        };

        openPages.put(player.uuid(), page);
        adminContexts.put(player.uuid(), context);
        mapCategories.put(player.uuid(), category);
        int menuId = context == AdminContext.pickDefaultMap ? adminPickMenuId : mapMenuId;
        String title = context == AdminContext.pickDefaultMap ? "[accent]选择默认地图[]" : "[accent]" + categoryLabel(category) + "[]";
        String desc = "第 " + (page + 1) + " / " + pageCount + " 页\n也可以输入 [accent]/map <编号>[] 直接选择";
        Call.menu(player.con(), menuId, title, desc, buttons);
    }

    private void handleMapMenu(Player player, int option){
        if(option < 0){
            selectedMaps.remove(player.uuid());
            adminContexts.remove(player.uuid());
            mapCategories.remove(player.uuid());
            return;
        }
        AdminContext context = adminContexts.get(player.uuid(), AdminContext.none);
        if(context == AdminContext.none && rejectDuringCountdown(player)) return;
        Seq<Map> all = availableMaps();
        if(all.isEmpty()) return;
        MapCategory category = context == AdminContext.pickDefaultMap ? MapCategory.ALL : mapCategories.get(player.uuid(), MapCategory.ALL);
        Seq<Map> maps = category == MapCategory.ALL ? all : all.select(map -> categoryOf(map) == category);
        if(maps.isEmpty()) return;
        int pageCount = Math.max(1, (maps.size + MAPS_PER_PAGE - 1) / MAPS_PER_PAGE);
        int page = Math.max(0, Math.min(openPages.get(player.uuid(), 0), pageCount - 1));
        int start = page * MAPS_PER_PAGE;
        int itemCount = Math.min(MAPS_PER_PAGE, maps.size - start);

        if(option < itemCount){
            Map map = maps.get(start + option);
            if(context == AdminContext.pickDefaultMap){
                MapMenuConfig.setDefaultMapKey(MapMenuConfig.keyOf(map));
                adminContexts.remove(player.uuid());
                mapCategories.remove(player.uuid());
                player.sendMessage("[green]默认地图已设置为：[]" + map.name());
                return;
            }
            selectedMaps.put(player.uuid(), map);
            showModeMenu(player, map);
        }else if(option == itemCount){
            if(page > 0){
                showMapMenu(player, page - 1, context, category);
            }else{
                // first page or single page: go back to previous menu
                adminContexts.remove(player.uuid());
                mapCategories.remove(player.uuid());
                if(context == AdminContext.pickDefaultMap){
                    showAdminMenu(player);
                }else{
                    showCategoryMenu(player);
                }
            }
        }else if(option == itemCount + 1 && page + 1 < pageCount){
            showMapMenu(player, page + 1, context, category);
        }
    }

    private void selectMapByQuery(Player player, String query){
        if(rejectDuringCountdown(player)) return;
        Seq<Map> maps = availableMaps();
        if(maps.isEmpty()){
            player.sendMessage("[scarlet]服务器没有可用地图。[]");
            return;
        }
        Map map = null;
        try{
            int index = Integer.parseInt(query.trim()) - 1;
            if(index >= 0 && index < maps.size){
                map = maps.get(index);
            }else{
                player.sendMessage("[scarlet]编号超出范围，可用地图 1 - " + maps.size + "。[]");
                return;
            }
        }catch(NumberFormatException ignored){
            String q = query.trim().toLowerCase(Locale.ROOT);
            for(Map m : maps){
                if(m.plainName().toLowerCase(Locale.ROOT).contains(q)){
                    map = m;
                    break;
                }
            }
            if(map == null){
                player.sendMessage("[scarlet]没有找到名称包含“[]" + query + "[scarlet]”的地图。[]");
                return;
            }
        }
        selectedMaps.put(player.uuid(), map);
        showModeMenu(player, map);
    }

    private void showModeMenu(Player player, Map map){
        if(rejectDuringCountdown(player)) return;
        String[][] buttons = {
                {"[green]生存[]", "[gold]沙盒[]"},
                {"[scarlet]进攻[]", "[cyan]PVP[]"},
                {"[lightgray]返回地图[]"}
        };
        Call.menu(player.con(), modeMenuId, "[accent]选择游戏模式[]", "地图：[cyan]" + map.name() + "[]", buttons);
    }

    private void handleModeMenu(Player player, int option){
        if(rejectDuringCountdown(player)) return;
        Map map = selectedMaps.get(player.uuid());
        if(map == null || option < 0) return;
        if(option == 4){
            showMapMenu(player, openPages.get(player.uuid(), 0), AdminContext.none, mapCategories.get(player.uuid(), MapCategory.ALL));
            return;
        }
        Gamemode mode = switch(option){
            case 0 -> Gamemode.survival;
            case 1 -> Gamemode.sandbox;
            case 2 -> Gamemode.attack;
            case 3 -> Gamemode.pvp;
            default -> null;
        };
        if(mode == null) return;
        submitPlan(player, map, mode);
    }

    private int countSelectableMapTeams(Map map){
        int[] count = {0};
        map.teams.each(id -> {
            if(id != Team.derelict.id) count[0]++;
        });
        return count[0];
    }

    // ---- 换图流程 ----

    private void submitPlan(Player player, Map map, Gamemode mode){
        if(countdownActive){
            player.sendMessage("[scarlet]换图倒计时正在进行。[]");
            return;
        }
        if(Instant.now().isBefore(nextSwitchAt)){
            player.sendMessage("[scarlet]换图冷却中，请稍后再试。[]");
            return;
        }
        if(mode == Gamemode.attack && !supportsAttackMode(map)){
            player.sendMessage("[scarlet]该地图不支持进攻模式：缺少玩家核心或敌方核心。[]");
            return;
        }
        if(mode == Gamemode.pvp && pvpTeamCount(map) < 2){
            player.sendMessage("[scarlet]该地图的有效核心队伍不足，需要至少 2 个。[]");
            return;
        }
        if(player.admin){
            beginCountdown(map, mode, player.name);
        }else{
            startOrJoinVote(player, map, mode);
        }
    }

    private boolean supportsAttackMode(Map map){
        coreTeamCount(map);
        Rules rules = map.applyRules(Gamemode.attack);
        return rules.defaultTeam != rules.waveTeam
                && map.teams.contains(rules.defaultTeam.id)
                && map.teams.contains(rules.waveTeam.id);
    }


    // ---- 投票 ----

    private void startOrJoinVote(Player player, Map map, Gamemode mode){
        Instant now = Instant.now();
        boolean same = votedMap == map && votedMode == mode;
        if(votedMap == null || now.isAfter(voteEndsAt)){
            votedMap = map;
            votedMode = mode;
            votes.clear();
            voteEndsAt = now.plusSeconds(MapMenuConfig.voteSeconds());
            Call.sendMessage("[accent]" + player.name + "[] 发起换图投票：" + planLabel(map, mode) +
                    "\n输入 [accent]/mapvote[] 查看并投票，持续 " + MapMenuConfig.voteSeconds() + " 秒，需 " + requiredVotes() + " 票。");
        }else if(!same){
            player.sendMessage("[scarlet]当前已有其他换图方案正在投票。[]");
            showVoteStatus(player);
            return;
        }
        votes.add(player.uuid());
        announceVote();
        checkVote();
    }

    private void showVoteStatus(Player player){
        expireVote();
        if(votedMap == null){
            player.sendMessage("[lightgray]当前没有换图投票。输入 /maps 或 /map <编号> 选择地图。[]");
            return;
        }
        long remaining = Math.max(0, Duration.between(Instant.now(), voteEndsAt).getSeconds());
        boolean voted = votes.contains(player.uuid());
        String status = voted ? "[green]你已投赞成票[]" : "[lightgray]你尚未投票[]";
        String[][] buttons = voted
                ? new String[][]{{"[gray]已投票[]", "[scarlet]撤票[]"}}
                : new String[][]{{"[green]赞成[]", "[lightgray]关闭[]"}};
        Call.menu(player.con(), voteMenuId, "[accent]换图投票[]",
                planLabel(votedMap, votedMode) +
                        "\n[white]" + votes.size + "[] / " + requiredVotes() + " 票 · 剩余 " + remaining + " 秒\n" + status,
                buttons);
    }

    private void handleVoteMenu(Player player, int option){
        if(rejectDuringCountdown(player)) return;
        if(votedMap == null || option < 0) return;
        if(option == 0 && !votes.contains(player.uuid())){
            votes.add(player.uuid());
            player.sendMessage("[green]你已投赞成票。[]");
        }else if(option == 1 && votes.contains(player.uuid())){
            votes.remove(player.uuid());
            player.sendMessage("[lightgray]你已撤票。[]");
        }
        announceVote();
        checkVote();
    }

    private int requiredVotes(){
        int players = Groups.player.size();
        int configured = MapMenuConfig.votesRequired();
        if(configured <= 0) return Math.max(1, players / 2 + 1);
        return Math.max(1, Math.min(configured, Math.max(1, players)));
    }

    private void announceVote(){
        if(votedMap != null){
            Call.sendMessage("[accent]换图投票：[]" + planLabel(votedMap, votedMode) +
                    " [green]" + votes.size + "[] / " + requiredVotes());
        }
    }

    private void checkVote(){
        if(votedMap != null && votes.size >= requiredVotes()){
            Map map = votedMap;
            Gamemode mode = votedMode;
            clearVote();
            beginCountdown(map, mode, "玩家投票");
        }
    }

    private void expireVote(){
        if(votedMap != null && Instant.now().isAfter(voteEndsAt)){
            Call.sendMessage("[scarlet]换图投票未通过：[]" + planLabel(votedMap, votedMode));
            clearVote();
        }
    }

    private void clearVote(){
        votedMap = null;
        votedMode = null;
        votes.clear();
        voteEndsAt = Instant.EPOCH;
    }

    // ---- 切换与保存 ----

    private void beginCountdown(Map map, Gamemode mode, String initiator){
        pendingMapChoice = false;
        countdownActive = true;
        int generation = ++countdownGeneration;
        int seconds = MapMenuConfig.countdownSeconds();
        clearVote();
        Call.sendMessage("[accent]" + initiator + "[] 选择了 " + planLabel(map, mode) + "，" + seconds + " 秒后切换。");
        for(int i = seconds; i >= 1; i--){
            showCountdown(generation, seconds - i, i);
        }
        Timer.schedule(() -> switchMap(generation, map, mode), seconds);
    }

    private void showCountdown(int generation, float delay, int seconds){
        Timer.schedule(() -> {
            if(countdownActive && countdownGeneration == generation){
                Call.setHudTextReliable("[accent]即将更换地图[]\n[white]" + seconds + "[]");
            }
        }, delay);
    }

    private void switchMap(int generation, Map map, Gamemode mode){
        if(!countdownActive || countdownGeneration != generation) return;
        countdownActive = false;
        Call.hideHudText();
        performSwitch(map, mode);
    }

    private void performSwitch(Map map, Gamemode mode){
        nextSwitchAt = Instant.now().plusSeconds(MapMenuConfig.cooldownSeconds());
        try{
            ServerControl.instance.lastMode = mode;
            ServerControl.instance.play(false, () -> Vars.world.loadMap(map, map.applyRules(mode)));
        }catch(Throwable error){
            Log.err("[MapMenu] 切换地图失败：@", map.name(), error);
            Call.sendMessage("[scarlet]地图切换失败，请管理员查看控制台日志。[]");
        }
    }


    private void togglePause(Player admin){
        if(Vars.state.isPaused()){
            Vars.state.set(GameState.State.playing);
            Call.sendMessage("[green]游戏已由 " + admin.name + " 继续。[]");
        }else if(Vars.state.isPlaying()){
            Vars.state.set(GameState.State.paused);
            Call.sendMessage("[orange]游戏已由 " + admin.name + " 暂停。[]");
        }else{
            admin.sendMessage("[scarlet]当前不在游戏中，无法暂停/继续。[]");
        }
    }
    private void restartCurrentMap(Player admin){
        Map map = Vars.state.map;
        if(map == null){
            admin.sendMessage("[scarlet]当前没有正在运行的地图。[]");
            return;
        }
        beginCountdown(map, activeMode(), admin.name);
    }

    private Gamemode activeMode(){
        if(ServerControl.instance != null && ServerControl.instance.lastMode != null){
            return ServerControl.instance.lastMode;
        }
        return modeFromRules(Vars.state != null ? Vars.state.rules : null);
    }

    private Gamemode modeFromRules(Rules rules){
        if(rules != null){
            if(rules.pvp) return Gamemode.pvp;
            if(rules.attackMode) return Gamemode.attack;
            if(rules.infiniteResources) return Gamemode.sandbox;
        }
        return Gamemode.survival;
    }
    // ---- PVP 自主选队 ----

    private void installPvpTeamAssigner(){
        if(Vars.netServer == null) return;
        if(pvpTeamAssigner == null){
            defaultTeamAssigner = Vars.netServer.assigner;
            pvpTeamAssigner = (player, players) -> Vars.state.rules.pvp
                    ? pendingPvpTeam()
                    : defaultTeamAssigner.assign(player, players);
        }
        Vars.netServer.assigner = pvpTeamAssigner;
    }

    private void preparePvpTeamSelection(){
        installPvpTeamAssigner();
        selectedPvpPlayers.clear();
        for(Player player : Groups.player) preparePlayerForPvpTeamChoice(player);
    }

    private void preparePlayerForPvpTeamChoice(Player player){
        if(player == null || !Vars.state.rules.pvp) return;
        selectedPvpPlayers.remove(player.uuid());
        player.team(pendingPvpTeam());
        if(player.unit() != null){
            player.unit().remove();
            player.clearUnit();
        }
        player.deathTimer(0f);
    }

    private void showPvpTeamMenuToAll(){
        if(!Vars.state.rules.pvp) return;
        for(Player player : Groups.player) showPvpTeamMenu(player);
    }

    private void schedulePvpTeamMenuPrompts(int generation){
        for(float delay : PVP_TEAM_MENU_DELAYS){
            Timer.schedule(() -> {
                // Ignore delayed prompts belonging to an older world. Only players who have not
                // selected a team are prompted, so a successful earlier prompt is never repeated.
                if(generation != pvpTeamPromptGeneration || !Vars.state.rules.pvp) return;
                refreshPendingPvpTeamMenus();
            }, delay);
        }
    }

    private void refreshPendingPvpTeamMenus(){
        if(!Vars.state.rules.pvp) return;
        for(Player player : Groups.player){
            if(!selectedPvpPlayers.contains(player.uuid())) showPvpTeamMenu(player);
        }
    }

    private void showPvpTeamMenu(Player player){
        if(player == null || player.con() == null) return;
        if(!Vars.state.rules.pvp){
            player.sendMessage("[scarlet]当前不是 PVP 模式。[]");
            return;
        }
        if(selectedPvpPlayers.contains(player.uuid())){
            player.sendMessage("[lightgray]你已经选择了 " + player.team().coloredName() + "[lightgray]，本局不能再次换队。[]");
            return;
        }
        Seq<Team> teams = pvpCoreTeams();
        if(teams.isEmpty()){
            player.sendMessage("[scarlet]当前地图没有可选择的核心队伍。[]");
            return;
        }
        pvpTeamMenuChoices.put(player.uuid(), teams.copy());
        String[][] buttons = new String[(teams.size + 1) / 2][];
        for(int row = 0; row < buttons.length; row++){
            int first = row * 2;
            int count = Math.min(2, teams.size - first);
            buttons[row] = new String[count];
            for(int column = 0; column < count; column++){
                Team team = teams.get(first + column);
                buttons[row][column] = team.coloredName() + "\n[white]当前 " + selectedPlayerCount(team) + " 人[]";
            }
        }
        // Use a follow-up menu so delayed retries replace the existing dialog
        // instead of stacking multiple dialogs on the client's UI.
        Call.followUpMenu(player.con(), pvpTeamMenuId, "[accent]选择 PVP 队伍[]",
                "请选择一个有核心的队伍。选队后各队人数差不能超过 2。\n[lightgray]关闭后可输入 /team 重新打开。[]", buttons);
    }

    private void handlePvpTeamMenu(Player player, int option){
        if(option < 0 || !Vars.state.rules.pvp) return;
        if(selectedPvpPlayers.contains(player.uuid())){
            player.sendMessage("[scarlet]你已经完成选队，本局不能再次换队。[]");
            return;
        }
        Seq<Team> menuTeams = pvpTeamMenuChoices.get(player.uuid());
        if(menuTeams == null || option >= menuTeams.size){
            player.sendMessage("[scarlet]选队菜单已经过期，请重新选择。[]");
            showPvpTeamMenu(player);
            return;
        }
        Team target = menuTeams.get(option);
        Seq<Team> teams = pvpCoreTeams();
        if(!teams.contains(target, true)){
            player.sendMessage("[scarlet]该队伍已经失去核心，请重新选择。[]");
            showPvpTeamMenu(player);
            return;
        }
        if(!canJoinPvpTeam(target, teams)){
            player.sendMessage("[scarlet]无法加入 " + target.coloredName() + "[scarlet]：选择后队伍最大与最小人数之差会超过 2，请选择人数较少的队伍。[]");
            showPvpTeamMenu(player);
            return;
        }
        player.team(target);
        selectedPvpPlayers.add(player.uuid());
        pvpTeamMenuChoices.remove(player.uuid());
        Call.hideFollowUpMenu(player.con(), pvpTeamMenuId);
        player.deathTimer(0f);
        if(target.core() != null) target.core().requestSpawn(player);
        player.sendMessage("[green]你已加入 " + target.coloredName() + "[green]。[]");
        Timer.schedule(this::refreshPendingPvpTeamMenus, 0.1f);
    }

    private boolean canJoinPvpTeam(Team target, Seq<Team> teams){
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for(Team team : teams){
            int count = selectedPlayerCount(team) + (team == target ? 1 : 0);
            min = Math.min(min, count);
            max = Math.max(max, count);
        }
        return max - min <= 2;
    }

    private int selectedPlayerCount(Team team){
        int count = 0;
        for(Player player : Groups.player){
            if(selectedPvpPlayers.contains(player.uuid()) && player.team() == team) count++;
        }
        return count;
    }

    private Seq<Team> pvpCoreTeams(){
        Seq<Team> teams = new Seq<>();
        for(Team team : Team.all){
            if(team != null && team != Team.derelict && team.cores().any()) teams.add(team);
        }
        return teams;
    }

    private Team pendingPvpTeam(){
        for(int i = Team.all.length - 1; i >= 0; i--){
            Team team = Team.all[i];
            if(team != null && team != Team.derelict && !team.cores().any() && !team.active()) return team;
        }
        return Team.derelict;
    }

    // ---- 默认地图 / 随机 / 轮换 ----

    private void installMapProvider(){
        Vars.maps.setMapProvider((mode, current) -> {
            Map next = nextConfiguredMap(mode, current);
            if(next != null) return next;
            if(mode == Gamemode.attack) return nextAttackMap(current);
            return Vars.maps.getShuffleMode().next(mode, current);
        });
    }

    private Map nextConfiguredMap(Gamemode mode, Map current){
        if(!MapMenuConfig.rotationKeys().isEmpty()){
            Map next = nextRotationMap(mode, current);
            if(next != null) return next;
        }
        if(MapMenuConfig.randomEnabled()){
            Seq<Map> candidates = mapsForMode(mode);
            if(!candidates.isEmpty()){
                candidates.shuffle();
                for(Map candidate : candidates){
                    if(candidate != current || candidates.size == 1) return candidate;
                }
                return candidates.first();
            }
        }
        return null;
    }

    private Map nextRotationMap(Gamemode mode, Map current){
        Seq<Map> list = rotationMaps().select(map -> mapValidForMode(map, mode));
        if(list.isEmpty()) return null;
        int index = list.indexOf(current);
        if(index < 0) return list.first();
        return list.get((index + 1) % list.size);
    }

    private Seq<Map> mapsForMode(Gamemode mode){
        return availableMaps().select(map -> mapValidForMode(map, mode));
    }

    private boolean mapValidForMode(Map map, Gamemode mode){
        if(mode == Gamemode.attack) return mode.valid(map) && supportsAttackMode(map);
        if(mode == Gamemode.pvp) return mode.valid(map) && pvpTeamCount(map) >= 2;
        return mode.valid(map);
    }

    private Map nextAttackMap(Map current){
        Seq<Map> candidates = availableMaps().select(this::supportsAttackMode);
        candidates.shuffle();
        for(Map candidate : candidates){
            if(candidate != current || candidates.size == 1) return candidate;
        }
        return null;
    }

    private Seq<Map> rotationMaps(){
        Seq<Map> result = new Seq<>();
        for(String key : MapMenuConfig.rotationKeys()){
            Map map = MapMenuConfig.byKey(key);
            if(map == null) continue;
            if(!result.contains(map, true)) result.add(map);
        }
        return result;
    }

    private String rotationLabel(){
        Seq<Map> rotation = rotationMaps();
        if(rotation.isEmpty()) return "关闭";
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < rotation.size && i < 2; i++){
            sb.append(shorten(rotation.get(i).name(), 10)).append("  ");
        }
        if(rotation.size > 2) sb.append("等 ").append(rotation.size).append(" 张");
        return sb.toString().trim();
    }

    private boolean sameMap(Map a, Map b){
        return a != null && b != null && MapMenuConfig.keyOf(a).equalsIgnoreCase(MapMenuConfig.keyOf(b));
    }

    private void applyDefaultMapOnStart(){
        Map def = MapMenuConfig.byKey(MapMenuConfig.defaultMapKey());
        if(def == null) return;
        Map current = Vars.state.map;
        if(current != null && sameMap(current, def)) return;
        Call.sendMessage("[accent]正在加载默认地图：[]" + def.name());
        performSwitch(def, activeMode());
    }

    // Take over game-over map switching: instead of the auto-random next map, wait for players to pick via /maps.
    // ServerControl reads this public field at fire time, so replacing it fully redirects the default flow.
    private void installGameOverListener(){
        if(ServerControl.instance == null){
            Log.warn("[MapMenu] ServerControl \u672a\u5c31\u7eea\uff0c\u65e0\u6cd5\u63a5\u7ba1\u6218\u6597\u7ed3\u675f\u6362\u56fe\u6d41\u7a0b\u3002");
            return;
        }
        ServerControl.instance.gameOverListener = event -> {
            String mapName = Vars.state.map == null ? "\u672a\u77e5" : arc.util.Strings.capitalize(Vars.state.map.plainName());
            if(Vars.state.rules.waves){
                Log.info("[MapMenu] \u6e38\u620f\u7ed3\u675f\uff01\u5b58\u6d3b\u81f3\u7b2c @ \u6ce2\uff0c\u5728\u7ebf\u73a9\u5bb6 @\uff0c\u5730\u56fe @\u3002", Vars.state.wave, Groups.player.size(), mapName);
            }else{
                Log.info("[MapMenu] \u6e38\u620f\u7ed3\u675f\uff01\u961f\u4f0d @ \u83b7\u80dc\uff0c\u5728\u7ebf\u73a9\u5bb6 @\uff0c\u5730\u56fe @\u3002", event.winner == null ? "\u672a\u77e5" : event.winner.name, Groups.player.size(), mapName);
            }
            Vars.state.gameOver = true;
            Call.updateGameOver(event.winner);
            if(availableMaps().isEmpty()){
                Log.info("[MapMenu] \u6ca1\u6709\u53ef\u7528\u81ea\u5b9a\u4e49\u5730\u56fe\uff0c\u6309\u9ed8\u8ba4\u884c\u4e3a\u5904\u7406\u3002");
                Vars.netServer.kickAll(mindustry.net.Packets.KickReason.gameover);
                Vars.state.set(GameState.State.menu);
                Vars.net.closeServer();
                return;
            }
            boolean firstPrompt = !pendingMapChoice;
            pendingMapChoice = true;
            Call.sendMessage("[scarlet]\u6218\u6597\u7ed3\u675f\uff01[] \u7b49\u5f85\u4f7f\u7528 [accent]/maps[] \u9009\u62e9\u4e0b\u4e00\u5f20\u5730\u56fe...");
            if(firstPrompt){
                Timer.schedule(() -> {
                    if(!pendingMapChoice) return;
                    for(Player p : Groups.player){
                        if(p != null && p.con() != null && !p.con().kicked){
                            showCategoryMenu(p);
                        }
                    }
                }, 3f);
            }
        };
        Log.info("[MapMenu] \u5df2\u63a5\u7ba1\u6218\u6597\u7ed3\u675f\u6362\u56fe\u6d41\u7a0b\uff1a\u6539\u4e3a\u7b49\u5f85\u73a9\u5bb6\u9009\u62e9\u5730\u56fe\u3002");
    }

    // ---- 管理员菜单 ----

    private void showAdminMenu(Player player){
        if(!requireAdmin(player)) return;
        Map def = MapMenuConfig.byKey(MapMenuConfig.defaultMapKey());
        String defLabel = def == null ? "未设置" : shorten(def.name(), 12);
        String[][] buttons = {
                {"[green]默认地图[]\n" + defLabel, "[orange]地图轮换[]\n" + rotationLabel()},
                {"[cyan]随机换图[]\n" + (MapMenuConfig.randomEnabled() ? "[green]开[]" : "[gray]关[]"), "[gold]投票设置[]"},
                {(Vars.state.isPaused() ? "[green]继续游戏[]" : "[orange]暂停游戏[]"), "[purple]重启地图[]"},
                {"[lightgray]存档管理[]", "[lightgray]查看配置[]"},
                {"[scarlet]关闭[]"}
        };
        Map current = Vars.state.map;
        Call.menu(player.con(), adminMenuId, "[accent]管理员地图管理[]",
                "当前地图：[cyan]" + (current == null ? "无" : current.name()) + "[]\n换图请用 /maps 或 /map <编号>", buttons);
    }

    private void handleAdminMenu(Player player, int option){
        if(!requireAdmin(player)) return;
        if(option < 0) return;
        switch(option){
            case 0 -> showMapMenu(player, 0, AdminContext.pickDefaultMap, MapCategory.ALL);
            case 1 -> askInput(player, PendingInput.rotation);
            case 2 -> {
                MapMenuConfig.setRandomEnabled(!MapMenuConfig.randomEnabled());
                player.sendMessage("[green]随机换图已" + (MapMenuConfig.randomEnabled() ? "开启。[]" : "关闭。[]"));
            }
            case 3 -> showVoteSettingsMenu(player);
            case 4 -> togglePause(player);
            case 5 -> restartCurrentMap(player);
            case 6 -> showSaveManageMenu(player);
            case 7 -> showConfig(player);
            default -> {}
        }
    }

    private void showVoteSettingsMenu(Player player){
        String[][] buttons = {
                {"[green]投票人数[]\n" + MapMenuConfig.votesRequiredLabel(), "[gold]投票时长[]\n" + MapMenuConfig.voteSeconds() + " 秒"},
                {"[orange]换图冷却[]\n" + MapMenuConfig.cooldownSeconds() + " 秒", "[cyan]切换倒计时[]\n" + MapMenuConfig.countdownSeconds() + " 秒"},
                {"[lightgray]返回管理菜单[]"}
        };
        Call.menu(player.con(), voteSettingsMenuId, "[accent]投票设置[]", "点击项目后输入数值", buttons);
    }

    private void handleVoteSettingsMenu(Player player, int option){
        if(!requireAdmin(player)) return;
        if(option < 0) return;
        if(option == 4){
            showAdminMenu(player);
            return;
        }
        PendingInput action = switch(option){
            case 0 -> PendingInput.votes;
            case 1 -> PendingInput.voteTime;
            case 2 -> PendingInput.cooldown;
            case 3 -> PendingInput.countdown;
            default -> null;
        };
        if(action != null) askInput(player, action);
    }

    private void askInput(Player player, PendingInput action){
        String title;
        String message;
        String def;
        int maxLen;
        switch(action){
            case votes -> {
                title = "设置投票人数";
                message = "输入所需赞成票数，或输入 majority 表示多数票。";
                def = MapMenuConfig.votesRequired() <= 0 ? "majority" : String.valueOf(MapMenuConfig.votesRequired());
                maxLen = 8;
            }
            case voteTime -> {
                title = "设置投票时长";
                message = "输入投票持续时间（秒，至少 5）。";
                def = String.valueOf(MapMenuConfig.voteSeconds());
                maxLen = 5;
            }
            case cooldown -> {
                title = "设置换图冷却";
                message = "输入换图冷却时间（秒）。";
                def = String.valueOf(MapMenuConfig.cooldownSeconds());
                maxLen = 5;
            }
            case countdown -> {
                title = "设置切换倒计时";
                message = "输入倒计时秒数（至少 3）。";
                def = String.valueOf(MapMenuConfig.countdownSeconds());
                maxLen = 5;
            }
            case rotation -> {
                title = "设置地图轮换";
                message = "输入地图编号（空格或逗号分隔，对应 /maps 内编号），输入 off 关闭。";
                def = MapMenuConfig.rotationRaw().replace(",", " ");
                maxLen = 200;
            }
            default -> {
                pendingInputs.remove(player.uuid());
                return;
            }
        }
        pendingInputs.put(player.uuid(), action);
        Call.textInput(player.con(), inputMenuId, "[accent]" + title + "[]", message, maxLen, def, false);
    }

    private void handleTextInput(Player player, String text){
        PendingInput action = pendingInputs.get(player.uuid(), PendingInput.none);
        pendingInputs.remove(player.uuid());
        if(action == PendingInput.none || text == null || text.trim().isEmpty()) return;
        String value = text.trim();
        switch(action){
            case votes -> {
                if(value.equalsIgnoreCase("majority") || value.equalsIgnoreCase("多数")){
                    MapMenuConfig.setVotesRequired(0);
                    player.sendMessage("[green]换图通过条件已设为多数票。[]");
                }else{
                    int v = parsePositive(value);
                    if(v < 1){
                        player.sendMessage("[scarlet]无效输入：需要至少 1 票。[]");
                    }else{
                        MapMenuConfig.setVotesRequired(v);
                        player.sendMessage("[green]换图所需票数已设为 " + v + " 票。[]");
                    }
                }
            }
            case voteTime -> {
                int v = parsePositive(value);
                if(v < 5){
                    player.sendMessage("[scarlet]投票时长至少 5 秒。[]");
                }else{
                    MapMenuConfig.setVoteSeconds(v);
                    player.sendMessage("[green]投票时长已设为 " + v + " 秒。[]");
                }
            }
            case cooldown -> {
                int v = parsePositive(value);
                if(v < 0){
                    player.sendMessage("[scarlet]无效输入。[]");
                }else{
                    MapMenuConfig.setCooldownSeconds(v);
                    player.sendMessage("[green]换图冷却已设为 " + v + " 秒。[]");
                }
            }
            case countdown -> {
                int v = parsePositive(value);
                if(v < 3){
                    player.sendMessage("[scarlet]倒计时至少 3 秒。[]");
                }else{
                    MapMenuConfig.setCountdownSeconds(v);
                    player.sendMessage("[green]切换倒计时已设为 " + v + " 秒。[]");
                }
            }
            case rotation -> {
                if(value.equalsIgnoreCase("off") || value.equalsIgnoreCase("关闭")){
                    MapMenuConfig.setRotationRaw("");
                    player.sendMessage("[green]地图轮换已关闭。[]");
                }else{
                    applyRotationFromTokens(player, value.split("[,，\\s]+"));
                }
            }
            default -> {}
        }
    }

    private void applyRotationFromTokens(Player player, String[] tokens){
        Seq<Map> maps = availableMaps();
        Seq<String> keys = new Seq<>();
        StringBuilder errors = new StringBuilder();
        for(String token : tokens){
            Map map = resolveMapToken(token, maps);
            if(map == null){
                if(errors.length() > 0) errors.append(" ");
                errors.append(token);
                continue;
            }
            String key = MapMenuConfig.keyOf(map);
            if(!keys.contains(key, false)) keys.add(key);
        }
        if(keys.isEmpty()){
            player.sendMessage("[scarlet]没有有效的地图编号/名称：[]" + errors);
            return;
        }
        MapMenuConfig.setRotationRaw(String.join(",", keys.toArray(String.class)));
        StringBuilder sb = new StringBuilder("[green]地图轮换已设置：[]");
        for(int i = 0; i < keys.size; i++){
            Map m = MapMenuConfig.byKey(keys.get(i));
            sb.append(i + 1).append(". ").append(m == null ? keys.get(i) : m.name()).append("  ");
        }
        if(errors.length() > 0) sb.append("\n[scarlet]无效项：[]").append(errors);
        player.sendMessage(sb.toString());
    }

    private Map resolveMapToken(String token, Seq<Map> maps){
        String t = token.trim();
        try{
            int index = Integer.parseInt(t) - 1;
            if(index >= 0 && index < maps.size) return maps.get(index);
            return null;
        }catch(NumberFormatException ignored){
            String q = t.toLowerCase(Locale.ROOT);
            for(Map m : maps){
                if(MapMenuConfig.keyOf(m).toLowerCase(Locale.ROOT).contains(q) || m.plainName().toLowerCase(Locale.ROOT).contains(q)) return m;
            }
            return null;
        }
    }

    private int parsePositive(String text){
        try{
            return Integer.parseInt(text.trim());
        }catch(NumberFormatException ignored){
            return -1;
        }
    }

    private String shorten(String s, int max){
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }


    // ---- 存档管理 ----

    private Fi saveSlotFile(int slot){
        return Vars.saveDirectory.child("mapmenu-slot-" + slot + "." + Vars.saveExtension);
    }

    private SaveMeta slotMeta(int slot){
        Fi file = saveSlotFile(slot);
        if(!SaveIO.isSaveValid(file)) return null;
        try{
            return SaveIO.getMeta(file);
        }catch(Throwable ignored){
            return null;
        }
    }

    private String timeText(long timestamp){
        try{
            return DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()));
        }catch(Throwable ignored){
            return "";
        }
    }

    private String slotLabel(int slot){
        SaveMeta meta = slotMeta(slot);
        if(meta == null) return "[accent]" + slot + ".[] [gray]空[]";
        String mapName = meta.map == null ? "未知地图" : shorten(meta.map.name(), 8);
        return "[accent]" + slot + ".[] " + mapName + "\n[gray]" + timeText(meta.timestamp) + " · 波次 " + meta.wave + "[]";
    }

    private void showSaveManageMenu(Player player){
        String[][] buttons = new String[6][];
        for(int i = 0; i < 5; i++){
            buttons[i] = new String[]{slotLabel(i * 2 + 1), slotLabel(i * 2 + 2)};
        }
        buttons[5] = new String[]{"[lightgray]返回管理菜单[]"};
        Call.menu(player.con(), saveSlotsMenuId, "[accent]存档管理[]",
                "10 个手动存档栏位，保存于 config/saves\n点击栏位选择保存或读取", buttons);
    }

    private void handleSaveSlotsMenu(Player player, int option){
        if(!requireAdmin(player)) return;
        if(option < 0) return;
        if(option == 10){
            showAdminMenu(player);
            return;
        }
        if(option >= 0 && option < SAVE_SLOT_COUNT){
            pendingSlots.put(player.uuid(), option + 1);
            showSaveSlotMenu(player, option + 1);
        }
    }

    private void showSaveSlotMenu(Player player, int slot){
        SaveMeta meta = slotMeta(slot);
        String status = meta == null
                ? "[gray]空栏位[]"
                : "[green]已保存：" + timeText(meta.timestamp) + " · " + (meta.map == null ? "未知地图" : meta.map.name()) + " · 波次 " + meta.wave + "[]";
        String[][] buttons = {
                {"[green]保存到该栏[]", meta == null ? "[gray]读取（空）[]" : "[orange]读取该栏[]"},
                {"[lightgray]返回存档列表[]"}
        };
        Call.menu(player.con(), saveSlotMenuId, "[accent]存档 " + slot + "[]", status, buttons);
    }

    private void handleSaveSlotMenu(Player player, int option){
        if(!requireAdmin(player)) return;
        int slot = pendingSlots.get(player.uuid(), -1);
        if(option < 0) return;
        if(slot < 1 || slot > SAVE_SLOT_COUNT) return;
        if(option == 0){
            saveToSlot(player, slot);
        }else if(option == 1){
            loadFromSlot(player, slot);
        }else if(option == 2){
            showSaveManageMenu(player);
        }
    }

    private void saveToSlot(Player admin, int slot){
        if(Vars.disableSave){
            admin.sendMessage("[scarlet]服务器已禁用存档功能。[]");
            return;
        }
        if(Vars.state.isMenu() || !Vars.state.isPlaying()){
            admin.sendMessage("[scarlet]当前没有正在进行的游戏，无法保存。[]");
            return;
        }
        try{
            Fi file = saveSlotFile(slot);
            SaveIO.save(file);
            Call.sendMessage("[green]" + admin.name + "[] 已将当前游戏手动保存到 [white]存档 " + slot + "[]");
        }catch(Throwable error){
            Log.err("[MapMenu] 保存存档 " + slot + " 失败", error);
            admin.sendMessage("[scarlet]存档保存失败，请查看控制台日志。[]");
        }
    }

    private void loadFromSlot(Player admin, int slot){
        Fi file = saveSlotFile(slot);
        if(!SaveIO.isSaveValid(file)){
            admin.sendMessage("[scarlet]存档 " + slot + " 为空或已损坏。[]");
            return;
        }
        boolean wasHosting = Vars.net.active() && Vars.net.server();
        Gamemode previousMode = ServerControl.instance == null ? null : ServerControl.instance.lastMode;
        clearVote();
        countdownActive = false;
        countdownGeneration++;
        selectedPvpPlayers.clear();
        Call.hideHudText();
        Call.sendMessage("[accent]" + admin.name + "[] 正在读取存档 " + slot + " ...");
        try{
            if(wasHosting){
                Vars.netServer.kickAll(mindustry.net.Packets.KickReason.serverRestarting);
                Vars.net.closeServer();
                Log.info("[MapMenu] \u5df2\u65ad\u5f00\u6240\u6709\u73a9\u5bb6\u5e76\u5173\u95ed\u7f51\u7edc\u5c42\uff0c\u51c6\u5907\u8bfb\u6863\u3002");
            }
        }catch(Throwable networkCloseError){
            Log.warn("[MapMenu] \u5173\u95ed\u7f51\u7edc\u5c42\u65f6\u51fa\u73b0\u5f02\u5e38\uff08\u7ee7\u7eed\u5c1d\u8bd5\u8bfb\u6863\uff09", networkCloseError);
        }
        Timer.schedule(() -> {
            try{
                SaveIO.load(file);
                pendingMapChoice = false;
                Gamemode loadedMode = modeFromRules(Vars.state.rules);
                if(ServerControl.instance != null) ServerControl.instance.lastMode = loadedMode;
                int restoredAssets = Vars.state.data.getAllAssets().size;
                if(restoredAssets > 0){
                    Log.warn("[MapMenu] 存档 @ 恢复出 @ 个数据资产（可能来自含内容MOD时期的存档）。当前服务器无对应内容MOD，自动清除以免客户端世界流损坏。", slot, restoredAssets);
                    try{
                        Vars.state.data.unload();
                        Log.info("[MapMenu] 已清除读档恢复的数据资产。");
                    }catch(Throwable unloadError){
                        Log.err("[MapMenu] 清除数据资产失败", unloadError);
                    }
                }else{
                    Log.info("[MapMenu] 存档 @ 无数据资产。", slot);
                }
                Vars.state.rules.sector = null;
                Vars.state.set(GameState.State.playing);
                if(wasHosting && !Vars.net.active()) Vars.netServer.openServer();
                Call.sendMessage("[green]存档 " + slot + " 读取完成。[]");
            }catch(Throwable error){
                countdownActive = false;
                if(ServerControl.instance != null) ServerControl.instance.lastMode = previousMode;
                Log.err("[MapMenu] 读取存档 " + slot + " 失败", error);
                if(wasHosting && !Vars.net.active()){
                    Log.warn("[MapMenu] 读档失败，正在重新开放服务器网络端口。");
                    Vars.netServer.openServer();
                }
                Call.sendMessage("[scarlet]存档读取失败，请查看控制台日志。[]");
            }
        }, 1.5f);
    }
    // ---- 文案 ----

    private String modeLabel(Gamemode mode){
        if(mode == Gamemode.survival) return "生存";
        if(mode == Gamemode.sandbox) return "沙盒";
        if(mode == Gamemode.attack) return "进攻";
        if(mode == Gamemode.pvp) return "PVP";
        return mode.name();
    }

    private String planLabel(Map map, Gamemode mode){
        return "[cyan]" + map.name() + "[] / [gold]" + modeLabel(mode) + "[]";
    }

    private void showConfig(Player player){
        Map def = MapMenuConfig.byKey(MapMenuConfig.defaultMapKey());
        Seq<Map> rotation = rotationMaps();
        StringBuilder sb = new StringBuilder();
        sb.append("[accent]—— 地图管理配置 ——[]\n");
        sb.append("[white]默认地图：[]").append(def == null ? "[gray]未设置[]" : def.name()).append("\n");
        sb.append("[white]随机换图：[]").append(MapMenuConfig.randomEnabled() ? "[green]开[]" : "[gray]关[]").append("\n");
        sb.append("[white]地图轮换：[]");
        if(rotation.isEmpty()){
            sb.append("[gray]关闭[]\n");
        }else{
            for(int i = 0; i < rotation.size; i++){
                sb.append(i + 1).append(". ").append(rotation.get(i).name()).append("  ");
            }
            sb.append("\n");
        }
        sb.append("[white]投票通过：[]").append(MapMenuConfig.votesRequiredLabel());
        sb.append("（当前在线 ").append(Groups.player.size()).append(" 人）\n");
        sb.append("[white]投票时长：[]").append(MapMenuConfig.voteSeconds()).append(" 秒\n");
        sb.append("[white]换图冷却：[]").append(MapMenuConfig.cooldownSeconds()).append(" 秒\n");
        sb.append("[white]切换倒计时：[]").append(MapMenuConfig.countdownSeconds()).append(" 秒");
        player.sendMessage(sb.toString());
    }
}
