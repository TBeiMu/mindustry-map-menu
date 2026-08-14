# Mindustry Map Menu 服务器地图管理器

和朋友一起玩的 Mindustry 8（build 159.7）专用服务器插件与地图包仓库。

## 内容

| 路径 | 说明 |
| --- | --- |
| `map-menu-plugin/` | 「地图管理器」插件源码（Java 17 / Gradle），详见 [map-menu-plugin/README.md](map-menu-plugin/README.md) |
| `outputs/MapMenu.jar` | 当前构建产物，直接放入服务器 `config/mods/` 即可 |
| `outputs/maps-4pvp/` | 已验证的 4 人 PVP 地图（5 张） |
| `outputs/maps-attack/` | 已验证的进攻模式地图（8 张） |
| `community-maps/` | 按 `survival/ attack/ pvp/` 分类整理的社区地图包，见 [community-maps/README.md](community-maps/README.md) |

## 插件功能速览

- `/maps` 游戏内地图选择菜单（分页、按类型分类：[内置] / [生存] / [进攻] / [PVP]）
- `/map <编号|名称>` 直接选图，选后可切换 生存 / 沙盒 / 进攻 / PVP 模式
- PVP 地图自动弹选队菜单（只显示实际有核心的队伍、限制人数差 ≤ 2），`/team` 可重新打开
- 管理员直接换图；普通玩家走换图投票（票数/时长/冷却/倒计时可配置；支持默认图、随机换图、轮换列表）
- 管理员菜单 `/admin`：暂停/继续、重启地图、管理 10 个手动存档栏位
- 所有管理功能仅管理员可用

完整命令与安装说明见 [map-menu-plugin/README.md](map-menu-plugin/README.md)。

## 构建

需要 JDK 17+，将服务器 `config/mods/Mindustry.jar` 放入 `map-menu-plugin/libs/` 后：

```text
gradle clean jar
```

产物在 `map-menu-plugin/build/libs/MapMenu.jar`。