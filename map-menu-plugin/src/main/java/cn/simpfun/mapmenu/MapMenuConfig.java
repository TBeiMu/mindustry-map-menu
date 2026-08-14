package cn.simpfun.mapmenu;

import arc.Core;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.maps.Map;

/**
 * 地图菜单插件的配置，使用 arc.Settings 持久化到服务器的 settings.bin。
 */
public class MapMenuConfig{
    public static final String PREFIX = "mapmenu.";

    private static final int DEFAULT_VOTES_REQUIRED = 0; // 0 表示多数票
    private static final int DEFAULT_VOTE_SECONDS = 60;
    private static final int DEFAULT_COOLDOWN_SECONDS = 5;
    private static final int DEFAULT_COUNTDOWN_SECONDS = 10;

    private MapMenuConfig(){}

    /** 换图所需票数，0 表示多数票。 */
    public static int votesRequired(){
        return Math.max(0, Core.settings.getInt(PREFIX + "votes-required", DEFAULT_VOTES_REQUIRED));
    }

    public static void setVotesRequired(int votes){
        Core.settings.put(PREFIX + "votes-required", Math.max(0, votes));
        save();
    }

    public static String votesRequiredLabel(){
        int v = votesRequired();
        return v <= 0 ? "多数票" : v + " 票";
    }

    public static int voteSeconds(){
        return Math.max(5, Core.settings.getInt(PREFIX + "vote-seconds", DEFAULT_VOTE_SECONDS));
    }

    public static void setVoteSeconds(int seconds){
        Core.settings.put(PREFIX + "vote-seconds", Math.max(5, seconds));
        save();
    }

    public static int cooldownSeconds(){
        return Math.max(0, Core.settings.getInt(PREFIX + "cooldown-seconds", DEFAULT_COOLDOWN_SECONDS));
    }

    public static void setCooldownSeconds(int seconds){
        Core.settings.put(PREFIX + "cooldown-seconds", Math.max(0, seconds));
        save();
    }

    public static int countdownSeconds(){
        return Math.max(3, Core.settings.getInt(PREFIX + "countdown-seconds", DEFAULT_COUNTDOWN_SECONDS));
    }

    public static void setCountdownSeconds(int seconds){
        Core.settings.put(PREFIX + "countdown-seconds", Math.max(3, seconds));
        save();
    }

    // ---- 地图策略 ----

    public static String defaultMapKey(){
        return Core.settings.getString(PREFIX + "default-map", "");
    }

    public static void setDefaultMapKey(String key){
        Core.settings.put(PREFIX + "default-map", key == null ? "" : key);
        save();
    }

    public static boolean randomEnabled(){
        return Core.settings.getBool(PREFIX + "random", false);
    }

    public static void setRandomEnabled(boolean enabled){
        Core.settings.put(PREFIX + "random", enabled);
        save();
    }

    public static String rotationRaw(){
        return Core.settings.getString(PREFIX + "rotation", "");
    }

    public static void setRotationRaw(String raw){
        Core.settings.put(PREFIX + "rotation", raw == null ? "" : raw.trim());
        save();
    }

    /** 把轮换字符串拆成地图 key 列表（保持顺序并去重）。 */
    public static Seq<String> rotationKeys(){
        Seq<String> keys = new Seq<>();
        String raw = rotationRaw();
        if(raw.isEmpty()) return keys;
        for(String part : raw.split("[,，\\s]+")){
            String key = part.trim();
            if(key.isEmpty() || keys.contains(key, false)) continue;
            keys.add(key);
        }
        return keys;
    }

    /** 地图的唯一标识：文件名（不含扩展名）。 */
    public static String keyOf(Map map){
        return map == null || map.file == null ? "" : map.file.nameWithoutExtension();
    }

    /** 按 key 或地图名查找地图。 */
    public static Map byKey(String key){
        if(key == null || key.isEmpty()) return null;
        for(Map map : Vars.maps.all()){
            if(key.equalsIgnoreCase(keyOf(map)) || key.equalsIgnoreCase(map.name())) return map;
        }
        return null;
    }

    private static void save(){
        Core.settings.forceSave();
    }
}