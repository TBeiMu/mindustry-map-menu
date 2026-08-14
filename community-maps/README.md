# 多人联机地图包（按分类组织）

地图按 **config/maps 下的子文件夹** 自动分类，游戏/插件天然递归扫描子文件夹：

| 文件夹 | 分类 | 说明 |
| --- | --- | --- |
| `config/maps/survival/` | 生存地图 | 自定义生存（PvE 守波）地图放这里 |
| `config/maps/attack/` | 进攻地图 | 自定义进攻地图放这里 |
| `config/maps/pvp/` | PVP地图 | 自定义 PVP 地图放这里 |
| `config/maps/`（根目录） | 自动判定 | 旧地图放根目录仍兼容，按地图规则/核心队伍自动归类（不保证 100% 准确） |
| 游戏内置 | 内置分类 | 游戏自带地图自动在「游戏内置地图」中 |

## 当前内容

### survival/（6 张）
- `antarctica-survival.msav` 南极生存（经典，Sharlotte）
- `central-base.msav` 中央基地（经典，Sharlotte）
- `volcanic-grounds.msav` 埃瑞吉火山生存（Erekir / EXTREME）
- `scattered-island.msav` 散落群岛（小图快节奏）
- `hellish-tower-defense.msav` 混合科技塔防（每 100 波 Boss）
- `revenge-of-kyotaer.msav` Kyotaer 系列剧情 PvE

### attack/（3 张）
- `black-sand-fortress.msav` 黑沙要塞（威胁等级：歼灭级）
- `spore-canyon.msav` 孢菌峡谷（威胁等级：中等）
- `breach-of-siege.msav` 围城突破（Sharlotte）

### pvp/（3 张）
- `pvp-4-corners-of-death.msav` 死亡四角（四方混战，NYDUS 服务器图）
- `pvp-war-zone-revamped.msav` 战争地带·重制（NYDUS 服务器图）
- `scrap-pvp.msav` 废料混战（Sharlotte）

## 部署

1. 把整个文件夹（survival/ attack/ pvp/）上传到服务器 `config/maps/` 下。
2. 重启服务器，或运行中输入 `reloadmaps` 重新扫描。
3. 玩家 `/maps`：先选分类（游戏内置/生存/进攻/PVP），再选图；编号可直接用于 `/map <编号>`。

提示：根目录的自动判定对"蓝红双核心"的图可能归入进攻（例如 veins），
这类图建议直接放进 `pvp/` 文件夹，文件夹优先级最高。

## 来源
- mindustry-tool.com（已验证地图库，按下载量挑选）
- Sharlottes/SharMapPackage（GitHub）
- Quezler/mindustry__nydus--map-pool（NYDUS 服务器图池，GitHub）