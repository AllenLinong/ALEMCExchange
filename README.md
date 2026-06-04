# ALEMCExchange

[![Version](https://img.shields.io/badge/version-1.1.4-blue)](https://github.com/AllenLinong/ALEMCExchange/releases)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21--26.1-green)](https://www.spigotmc.org/)
[![Java](https://img.shields.io/badge/Java-21%2B-orange)](https://adoptium.net/)
[![License](https://img.shields.io/badge/license-MIT-brightgreen)](LICENSE)
[![Folia](https://img.shields.io/badge/Folia-supported-purple)](https://papermc.io/software/folia)

**ALEMCExchange** 是一个 Minecraft 服务器插件，实现了 **EMC（Energy Matter Covalence）** 经济系统。玩家可以通过挖掘、出售物品获得 EMC 积分，并用积分购买已解锁的物品。

## 特性

- **EMC 经济系统** — 挖掘、出售物品赚取 EMC，用 EMC 购买物品
- **物品解锁机制** — 达到挖掘/出售条件后解锁物品购买权限
- **自动出售** — 拾取时自动出售物品获取 EMC
- **税率系统** — VIP/Premium/免税多级权限税率
- **GUI 菜单** — 完整的 GUI 交互界面（出售、兑换、浏览）
- **数据库支持** — SQLite + MySQL（HikariCP 连接池）
- **PlaceholderAPI** — 支持 `%alembcexchange_balance%` 等占位符
- **Folia 兼容** — 完整支持 Folia 多线程服务端

## 快速开始

1. 从 [Releases](https://github.com/AllenLinong/ALEMCExchange/releases) 下载最新 JAR
2. 放入服务器的 `plugins/` 文件夹
3. 启动服务器，自动生成配置文件
4. 编辑 `plugins/ALEMCExchange/` 下的配置
5. 执行 `/alex reload` 重载配置

## 命令

| 命令 | 描述 | 权限 |
|------|------|------|
| `/alex` | 打开主菜单 | `alembcexchange.use` |
| `/alex help` | 帮助信息 | `alembcexchange.use` |
| `/alex sell` | 出售菜单 | `alembcexchange.use` |
| `/alex exchange` | 兑换菜单 | `alembcexchange.use` |
| `/alex browse` | 浏览菜单 | `alembcexchange.use` |
| `/alex balance` | 查看余额 | `alembcexchange.use` |
| `/alex autosell` | 切换自动出售 | `alembcexchange.autosell` |
| `/alex pay <玩家> <金额>` | 转账 | `alembcexchange.use` |
| `/alex reload` | 重载配置 | `alembcexchange.admin` |
| `/alex give <玩家> <金额>` | 给予 EMC | `alembcexchange.admin` |
| `/alex set <玩家> <金额>` | 设置 EMC | `alembcexchange.admin` |
| `/alex unlockall <玩家>` | 解锁全部物品 | `alembcexchange.admin` |
| `/alex unlock <玩家> <ID>` | 解锁单个物品 | `alembcexchange.admin` |

## 权限

| 权限 | 描述 | 默认 |
|------|------|------|
| `alembcexchange.use` | 基础权限 | true |
| `alembcexchange.admin` | 管理员（含所有子权限） | op |
| `alembcexchange.autosell` | 自动出售 | false |
| `alembcexchange.notax` | 免税（0%） | false |
| `alembcexchange.vip` | VIP（3% 税率） | false |
| `alembcexchange.premium` | Premium（1% 税率） | false |
| `alembcexchange.unlockall.auto` | 自动解锁全部物品 | false |

## 依赖

- **必需**: Spigot/Paper/Folia 1.21+
- **可选**: [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) — 提供占位符支持

## 文档

- [完整 Wiki](WIKI.md) — 配置详解、游戏机制、常见问题
- [API 文档](ALEMCExchangeAPI.md) — 开发者 API 接口
- [构建教程](BUILD.md) — 从源码编译

## 构建

```bash
git clone https://github.com/AllenLinong/ALEMCExchange.git
cd ALEMCExchange
mvn clean package -DskipTests
```

构建产物在 `target/ALEMCExchange-1.1.4.jar`

## 许可证

MIT License - 详见 [LICENSE](LICENSE)

## 联系

- GitHub Issues: [提交问题](https://github.com/AllenLinong/ALEMCExchange/issues)
- QQ: 1422163791
