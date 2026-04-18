# ALEMCExchange API 文档

## 简介

ALEMCExchange 插件提供了一套API，允许其他插件与EMC系统进行联动。通过这些API，其他插件可以获取玩家的EMC余额、修改余额、检查物品解锁状态等。

## Maven 依赖

```xml
<dependency>
    <groupId>com.alemcexchange</groupId>
    <artifactId>ALEMCExchange</artifactId>
    <version>1.1.2</version>
    <scope>provided</scope>
</dependency>
```

## API 类

### DatabaseManager

`DatabaseManager` 是核心API类，提供了与EMC系统交互的方法。

#### 方法列表

| 方法名                                              | 描述            | 参数                         | 返回值            |
| ------------------------------------------------ | ------------- | -------------------------- | -------------- |
| `getEMCBalance(UUID uuid)`                       | 获取玩家的EMC余额    | uuid: 玩家UUID               | double: EMC余额  |
| `setEMCBalance(UUID uuid, double balance)`       | 设置玩家的EMC余额    | uuid: 玩家UUIDbalance: 新的余额  | void           |
| `addEMCBalance(UUID uuid, double amount)`        | 增加玩家的EMC余额    | uuid: 玩家UUIDamount: 增加的金额  | void           |
| `hasSufficientEMC(UUID uuid, double amount)`     | 检查玩家是否有足够的EMC | uuid: 玩家UUIDamount: 需要的金额  | boolean: 是否足够  |
| `isUnlocked(UUID uuid, String material)`         | 检查物品是否已解锁     | uuid: 玩家UUIDmaterial: 物品名称 | boolean: 是否已解锁 |
| `unlockItem(UUID uuid, String material)`         | 解锁物品          | uuid: 玩家UUIDmaterial: 物品名称 | void           |
| `unlockAllItems(UUID uuid)`                      | 解锁所有物品        | uuid: 玩家UUID               | void           |
| `isAutoSellEnabled(UUID uuid)`                   | 检查自动出售是否开启    | uuid: 玩家UUID               | boolean: 是否开启  |
| `setAutoSellEnabled(UUID uuid, boolean enabled)` | 设置自动出售状态      | uuid: 玩家UUIDenabled: 是否开启  | void           |
| `getMineProgress(UUID uuid, String material)`    | 获取挖掘进度        | uuid: 玩家UUIDmaterial: 物品名称 | int: 进度        |
| `getSellProgress(UUID uuid, String material)`    | 获取出售进度        | uuid: 玩家UUIDmaterial: 物品名称 | int: 进度        |

### 示例代码

```java
// 获取DatabaseManager实例
JavaPlugin plugin = ...; // 你的插件实例
ConfigManager configManager = new ConfigManager(plugin);
DatabaseManager databaseManager = new DatabaseManager(plugin, configManager);
databaseManager.initialize();

// 获取玩家EMC余额
UUID playerUUID = player.getUniqueId();
double balance = databaseManager.getEMCBalance(playerUUID);

// 增加EMC余额
databaseManager.addEMCBalance(playerUUID, 100.0);

// 检查物品是否解锁
boolean isUnlocked = databaseManager.isUnlocked(playerUUID, "DIAMOND");

// 解锁物品
databaseManager.unlockItem(playerUUID, "DIAMOND");
```

## 事件

插件目前没有提供自定义事件，所有操作通过API方法直接调用。

## 注意事项

1. 所有数据库操作都可能抛出 `SQLException` 和 `ClassNot``FoundException`**，请确保在调用时**进行异常处理。
2. 建议在异步线程中执行数据库操作，避免阻塞主线程。
3. 频繁的数据库操作可能会影响性能，建议使用缓存机制。

## 版本兼容性

- API 1.1.2 兼容 Spigot 1.21+
- 后续版本可能会增加新的API方法，但会保持向后兼容。

