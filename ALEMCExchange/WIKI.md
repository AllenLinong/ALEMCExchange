# ALEMCExchange 插件 Wiki

## 插件介绍

ALEMCExchange 是一个 Minecraft 服务器插件，提供了 EMC（Energy Matter Covalence）系统，允许玩家通过挖掘、出售物品来获得 EMC 积分，然后使用 EMC 积分购买物品。

## 功能特性

- **EMC 系统**：通过挖掘和出售物品获得 EMC 积分
- **物品解锁**：通过挖掘和出售物品解锁新的可购买物品
- **自动出售**：自动出售拾取的物品获得 EMC
- **兑换系统**：使用 EMC 积分购买已解锁的物品
- **税率系统**：不同权限等级有不同的税率
- **数据库支持**：支持 SQLite 和 MySQL
- **PlaceholderAPI 支持**：可在其他插件中显示 EMC 余额

## 安装说明

1. **下载插件**：从 releases 页面下载最新版本的 JAR 文件
2. **安装插件**：将 JAR 文件放入服务器的 `plugins` 文件夹
3. **启动服务器**：服务器启动时会自动创建配置文件
4. **配置插件**：编辑 `plugins/ALEMCExchange` 文件夹中的配置文件

## 配置文件

### config.yml

```yaml
# 数据库配置
database:
  type: sqlite  # 数据库类型：sqlite 或 mysql
  mysql:  # MySQL 数据库配置
    host: localhost
    port: 3306
    database: minecraft
    user: root
    password: ''
    connection-parameters: "?autoReconnect=true&useSSL=false&useUnicode=true&characterEncoding=UTF-8"
    pool-settings:
      max-pool-size: 10
      min-idle: 5
      max-lifetime: 1800000
      keep-alive-time: 60000
      time-out: 30000
  table_prefix: 'alembc_'  # 表名前缀
  sqlite:
    file: 'alembc.db'  # SQLite 数据库文件

# 默认税率
sell_tax: 0.05  # 5%

# 不同权限的税率
tax_rates:
  alemcexchange.vip: 0.03  # VIP 3%
  alemcexchange.premium: 0.01  # 高级用户 1%
  alemcexchange.notax: 0.0  # 无税率

# 功能开关
features:
  auto_sell: true  # 自动出售功能

# 调试功能
debug:
  enabled: false  # 是否开启调试模式
  level: info  # 调试级别

# 自动出售设置
autosell:
  send_message: true  # 自动出售时发送提示消息
  batch_threshold: 64  # 批量出售阈值
```

### items.yml

配置物品的 EMC 价格和解锁条件：

```yaml
items:
  DIAMOND:
    name: "钻石"
    emc: 100.0
    required_mine: 10  # 需要挖掘10个才能解锁
    required_sell: 5   # 需要出售5个才能解锁
  IRON_INGOT:
    name: "铁锭"
    emc: 10.0
    required_mine: 0   # 无需挖掘即可解锁
    required_sell: 0   # 无需出售即可解锁
```

### lang.yml

配置插件的语言文件：

```yaml
prefix: "&6[EMC系统] &r"

command:
  player-only: "&c只有玩家可以执行此命令！"
  no-permission: "&c你没有权限执行此命令！"
  # 其他命令消息...

help:
  title: "&6=== ALEMCExchange 帮助 ===="
  # 帮助消息...

menu:
  sell:
    success: "&a出售成功！获得 &6{amount} EMC"
    failed: "&c出售失败！"
  exchange:
    inventory-full: "&c背包已满，无法兑换！"
    purchase-success: "&a购买成功！消耗 &6{amount} EMC"
    error: "&c兑换过程中发生错误！"

# 其他语言配置...
```

## 命令

| 命令                         | 描述          | 权限                        |
| -------------------------- | ----------- | ------------------------- |
| `/alex`                    | 打开主菜单       | `alembcexchange.use`      |
| `/alex help`               | 显示帮助信息      | `alembcexchange.use`      |
| `/alex sell`               | 打开出售菜单      | `alembcexchange.use`      |
| `/alex exchange`           | 打开兑换菜单      | `alembcexchange.use`      |
| `/alex browse`             | 打开浏览菜单      | `alembcexchange.use`      |
| `/alex balance`            | 查看 EMC 余额   | `alembcexchange.use`      |
| `/alex autosell`           | 切换自动出售功能    | `alembcexchange.autosell` |
| `/alex pay <玩家> <金额>`      | 转账给其他玩家     | `alembcexchange.use`      |
| `/alex reload`             | 重载配置文件      | `alembcexchange.admin`    |
| `/alex give <玩家> <金额>`     | 给玩家增加 EMC   | `alembcexchange.admin`    |
| `/alex set <玩家> <金额>`      | 设置玩家的 EMC 值 | `alembcexchange.admin`    |
| `/alex unlockall <玩家>`     | 解锁玩家的所有物品   | `alembcexchange.admin`    |
| `/alex unlock <玩家> <物品ID>` | 解锁玩家的单个物品   | `alembcexchange.admin`    |

## 权限

| 权限                              | 描述               |
| ------------------------------- | ---------------- |
| `alembcexchange.use`            | 基础使用权限（默认所有玩家拥有） |
| `alembcexchange.admin`          | 管理员权限            |
| `alembcexchange.autosell`       | 允许使用自动出售功能       |
| `alembcexchange.notax`          | 无税率权限            |
| `alembcexchange.vip`            | VIP 权限，3% 税率     |
| `alembcexchange.premium`        | 高级用户权限，1% 税率     |
| `alembcexchange.unlockall.auto` | 自动解锁所有物品         |

## 游戏机制

### 获得 EMC

1. **挖掘物品**：挖掘配置中的物品会获得 EMC
2. **出售物品**：在出售菜单中出售物品获得 EMC
3. **自动出售**：开启自动出售后，拾取物品会自动出售获得 EMC
4. **管理员给予**：管理员可以通过命令给玩家增加 EMC

### 解锁物品

物品需要通过挖掘和出售来解锁：

- 挖掘一定数量的物品
- 出售一定数量的物品
- 同时满足以上两个条件后物品会自动解锁

### 购买物品

在兑换菜单中，玩家可以使用 EMC 积分购买已解锁的物品。

## PlaceholderAPI 支持

插件提供了 PlaceholderAPI 占位符：

| 占位符                         | 描述           |
| --------------------------- | ------------ |
| `%alembcexchange_balance%`  | 显示玩家的 EMC 余额 |
| `%alembcexchange_autosell%` | 显示自动出售状态     |

## 常见问题

### Q: 物品没有 EMC 价值

A: 请检查 `items.yml` 配置文件，确保物品已正确配置 EMC 价格。

### Q: 自动出售不工作

A: 请检查：

- 自动出售功能是否开启（`features.auto_sell: true`）
- 玩家是否有 `alembcexchange.autosell` 权限
- 物品是否在 `items.yml` 中配置

### Q: 数据库连接失败

A: 请检查数据库配置，确保连接信息正确。

## 开发者 API

请参考 `ALEMCExchangeAPI.md` 文件获取详细的 API 文档。

## 联系与支持

- **QQ**：[1422163791](https://discord.gg/yourserver)

