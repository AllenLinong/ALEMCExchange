package com.alemcexchange.util;

import com.alemcexchange.config.ConfigManager;
import com.alemcexchange.database.DatabaseManager;
import com.alemcexchange.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.*;

public class MenuManager implements Listener {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final DatabaseManager databaseManager;
    private final Map<UUID, Inventory> playerInventories = new HashMap<>();
    private final Map<UUID, String> playerMenuTypes = new HashMap<>();
    private final Map<UUID, Integer> playerBrowsePages = new HashMap<>();
    private final Map<UUID, Integer> playerExchangePages = new HashMap<>();
    private final int ITEMS_PER_PAGE = 36;
    
    // 缓存
    private final Map<String, ItemStack> menuItemsCache = new HashMap<>();
    private final Map<UUID, Double> playerBalanceCache = new HashMap<>();
    private final Map<UUID, Map<String, Boolean>> playerUnlockedCache = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> playerMineProgressCache = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> playerSellProgressCache = new HashMap<>();
    private final Map<UUID, Long> playerCacheTimestamps = new HashMap<>(); // 缓存时间戳

    public MenuManager(JavaPlugin plugin, ConfigManager configManager, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.databaseManager = databaseManager;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        
        // 启动定期缓存清理任务
        startCacheCleanupTask();
    }
    
    // 启动定期缓存清理任务
    private void startCacheCleanupTask() {
        // 每5分钟清理一次缓存
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            // 清理菜单物品缓存（保留最近使用的物品）
            if (menuItemsCache.size() > 1000) {
                // 只保留最近使用的500个物品
                Map<String, ItemStack> newCache = new HashMap<>();
                int count = 0;
                for (Map.Entry<String, ItemStack> entry : menuItemsCache.entrySet()) {
                    if (count < 500) {
                        newCache.put(entry.getKey(), entry.getValue());
                        count++;
                    } else {
                        break;
                    }
                }
                menuItemsCache.clear();
                menuItemsCache.putAll(newCache);
                plugin.getLogger().info("清理了菜单物品缓存，保留了500个最近使用的物品");
            }
            
            // 清理长时间未使用的玩家缓存（超过30分钟）
            long currentTime = System.currentTimeMillis();
            long thirtyMinutesAgo = currentTime - (30 * 60 * 1000);
            
            List<UUID> toRemove = new ArrayList<>();
            for (Map.Entry<UUID, Long> entry : playerCacheTimestamps.entrySet()) {
                if (entry.getValue() < thirtyMinutesAgo) {
                    toRemove.add(entry.getKey());
                }
            }
            
            for (UUID playerUUID : toRemove) {
                clearPlayerCache(playerUUID);
                playerCacheTimestamps.remove(playerUUID);
            }
            
            if (!toRemove.isEmpty()) {
                plugin.getLogger().info("清理了 " + toRemove.size() + " 个长时间未使用的玩家缓存");
            }
        }, 0L, 5 * 60 * 20L); // 5分钟
    }

    public void openMainMenu(Player player) {
        clearPlayerCache(player.getUniqueId());

        Inventory inventory = Bukkit.createInventory(player, 27, ColorUtil.translateColorCodes(configManager.getMenus().getString("menus.main.title")));

        double balance = getCachedBalance(player.getUniqueId());

        // 添加出售按钮
        List<String> sellLore = new ArrayList<>(configManager.getMenus().getStringList("menus.main.buttons.sell.lore"));
        for (int i = 0; i < sellLore.size(); i++) {
            sellLore.set(i, sellLore.get(i).replace("%balance%", String.format("%.2f", balance)));
        }
        ItemStack sellItem = createMenuItem(
                Material.valueOf(configManager.getMenus().getString("menus.main.buttons.sell.material")),
                configManager.getMenus().getString("menus.main.buttons.sell.display_name"),
                sellLore
        );
        inventory.setItem(configManager.getMenus().getInt("menus.main.buttons.sell.slot"), sellItem);

        // 添加兑换按钮
        List<String> exchangeLore = new ArrayList<>(configManager.getMenus().getStringList("menus.main.buttons.exchange.lore"));
        for (int i = 0; i < exchangeLore.size(); i++) {
            exchangeLore.set(i, exchangeLore.get(i).replace("%balance%", String.format("%.2f", balance)));
        }
        ItemStack exchangeItem = createMenuItem(
                Material.valueOf(configManager.getMenus().getString("menus.main.buttons.exchange.material")),
                configManager.getMenus().getString("menus.main.buttons.exchange.display_name"),
                exchangeLore
        );
        inventory.setItem(configManager.getMenus().getInt("menus.main.buttons.exchange.slot"), exchangeItem);

        // 添加浏览按钮
        List<String> browseLore = new ArrayList<>(configManager.getMenus().getStringList("menus.main.buttons.browse.lore"));
        for (int i = 0; i < browseLore.size(); i++) {
            browseLore.set(i, browseLore.get(i).replace("%balance%", String.format("%.2f", balance)));
        }
        ItemStack browseItem = createMenuItem(
                Material.valueOf(configManager.getMenus().getString("menus.main.buttons.browse.material")),
                configManager.getMenus().getString("menus.main.buttons.browse.display_name"),
                browseLore
        );
        inventory.setItem(configManager.getMenus().getInt("menus.main.buttons.browse.slot"), browseItem);

        // 添加关闭按钮
        ItemStack closeItem = createMenuItem(
                Material.valueOf(configManager.getMenus().getString("menus.main.buttons.close.material")),
                configManager.getMenus().getString("menus.main.buttons.close.display_name"),
                configManager.getMenus().getStringList("menus.main.buttons.close.lore")
        );
        inventory.setItem(configManager.getMenus().getInt("menus.main.buttons.close.slot"), closeItem);

        player.openInventory(inventory);
        playerInventories.put(player.getUniqueId(), inventory);
        playerMenuTypes.put(player.getUniqueId(), "main");
    }

    public void openSellMenu(Player player) {
        clearPlayerCache(player.getUniqueId());

        Inventory inventory = Bukkit.createInventory(player, 54, ColorUtil.translateColorCodes(configManager.getMenus().getString("menus.sell.title")));

        // 添加玻璃板
        List<Integer> glassSlots = configManager.getMenus().getIntegerList("menus.sell.glass.slots");
        if (glassSlots.isEmpty()) {
            // 默认值：第一行和第六行（除了按钮位置）
            glassSlots = Arrays.asList(0, 1, 2, 3, 5, 6, 7, 8, 45, 46, 47, 48, 50, 51, 52);
        }
        
        ItemStack glassPane = new ItemStack(Material.valueOf(configManager.getMenus().getString("menus.sell.glass.material", "GRAY_STAINED_GLASS_PANE")));
        ItemMeta glassMeta = glassPane.getItemMeta();
        if (glassMeta != null) {
            glassMeta.setDisplayName(configManager.getMenus().getString("menus.sell.glass.display_name", " "));
            glassPane.setItemMeta(glassMeta);
        }
        
        for (int slot : glassSlots) {
            inventory.setItem(slot, glassPane);
        }

        // 添加说明按钮
        ItemStack infoItem = createMenuItem(
                Material.valueOf(configManager.getMenus().getString("menus.sell.buttons.info.material")),
                configManager.getMenus().getString("menus.sell.buttons.info.display_name"),
                configManager.getMenus().getStringList("menus.sell.buttons.info.lore")
        );
        inventory.setItem(configManager.getMenus().getInt("menus.sell.buttons.info.slot"), infoItem);

        // 添加出售按钮
        List<String> sellLore = new ArrayList<>(configManager.getMenus().getStringList("menus.sell.buttons.sell.lore"));
        // 计算税率
        double taxRate = configManager.getConfig().getDouble("sell_tax", 0.05);
        if (player.hasPermission("alemcexchange.notax")) {
            taxRate = 0.0;
        } else if (player.hasPermission("alemcexchange.premium")) {
            taxRate = configManager.getConfig().getDouble("tax_rates.alemcexchange.premium", 0.01);
        } else if (player.hasPermission("alemcexchange.vip")) {
            taxRate = configManager.getConfig().getDouble("tax_rates.alemcexchange.vip", 0.03);
        }
        sellLore.set(1, sellLore.get(1).replace("%tax%", String.format("%.1f", taxRate * 100)));

        ItemStack sellItem = createMenuItem(
                Material.valueOf(configManager.getMenus().getString("menus.sell.buttons.sell.material")),
                configManager.getMenus().getString("menus.sell.buttons.sell.display_name"),
                sellLore
        );
        inventory.setItem(configManager.getMenus().getInt("menus.sell.buttons.sell.slot"), sellItem);

        // 添加返回按钮
        ItemStack backItem = createMenuItem(
                Material.valueOf(configManager.getMenus().getString("menus.sell.buttons.back.material")),
                configManager.getMenus().getString("menus.sell.buttons.back.display_name"),
                configManager.getMenus().getStringList("menus.sell.buttons.back.lore")
        );
        inventory.setItem(configManager.getMenus().getInt("menus.sell.buttons.back.slot"), backItem);

        player.openInventory(inventory);
        playerInventories.put(player.getUniqueId(), inventory);
        playerMenuTypes.put(player.getUniqueId(), "sell");
    }

    public void openExchangeMenu(Player player) {
        openExchangeMenu(player, 1);
    }

    public void openExchangeMenu(Player player, int page) {
        clearPlayerCache(player.getUniqueId());

        Inventory inventory = Bukkit.createInventory(player, 54, ColorUtil.translateColorCodes(configManager.getMenus().getString("menus.exchange.title")));

        // 添加玻璃板
        List<Integer> glassSlots = configManager.getMenus().getIntegerList("menus.exchange.glass.slots");
        if (glassSlots.isEmpty()) {
            // 默认值：第一行和第六行（除了按钮位置）
            glassSlots = Arrays.asList(0, 1, 2, 3, 5, 6, 7, 47, 48, 50, 51, 52, 53);
        }
        
        ItemStack glassPane = new ItemStack(Material.valueOf(configManager.getMenus().getString("menus.exchange.glass.material", "GRAY_STAINED_GLASS_PANE")));
        ItemMeta glassMeta = glassPane.getItemMeta();
        if (glassMeta != null) {
            glassMeta.setDisplayName(configManager.getMenus().getString("menus.exchange.glass.display_name", " "));
            glassPane.setItemMeta(glassMeta);
        }
        
        for (int slot : glassSlots) {
            inventory.setItem(slot, glassPane);
        }

        // 读取物品显示区域配置
        List<Integer> displaySlots = configManager.getMenus().getIntegerList("menus.exchange.display.slots");
        if (displaySlots.isEmpty()) {
            // 默认值：第二行到第五行
            displaySlots = Arrays.asList(9, 10, 11, 12, 13, 14, 15, 16, 17,
                                         18, 19, 20, 21, 22, 23, 24, 25, 26,
                                         27, 28, 29, 30, 31, 32, 33, 34, 35,
                                         36, 37, 38, 39, 40, 41, 42, 43, 44);
        }
        int itemsPerPage = displaySlots.size();

        // 添加说明按钮
        List<String> infoLore = new ArrayList<>(configManager.getMenus().getStringList("menus.exchange.buttons.info.lore"));
        try {
            double balance = databaseManager.getEMCBalance(player.getUniqueId());
            for (int i = 0; i < infoLore.size(); i++) {
                infoLore.set(i, infoLore.get(i).replace("%balance%", String.format("%.2f", balance)));
            }
        } catch (SQLException | ClassNotFoundException e) {
            plugin.getLogger().severe("Error getting balance: " + e.getMessage());
        }

        ItemStack infoItem = createMenuItem(
                Material.valueOf(configManager.getMenus().getString("menus.exchange.buttons.info.material")),
                configManager.getMenus().getString("menus.exchange.buttons.info.display_name"),
                infoLore
        );
        inventory.setItem(configManager.getMenus().getInt("menus.exchange.buttons.info.slot"), infoItem);

        // 添加返回按钮
        ItemStack backItem = createMenuItem(
                Material.valueOf(configManager.getMenus().getString("menus.exchange.buttons.back.material")),
                configManager.getMenus().getString("menus.exchange.buttons.back.display_name"),
                configManager.getMenus().getStringList("menus.exchange.buttons.back.lore")
        );
        inventory.setItem(configManager.getMenus().getInt("menus.exchange.buttons.back.slot"), backItem);

        // 获取所有物品
        List<String> allMaterials = new ArrayList<>(configManager.getItems().getConfigurationSection("items").getKeys(false));
        // 批量获取解锁状态
        Map<String, Boolean> unlockedStatuses = getCachedUnlockedStatuses(player.getUniqueId(), allMaterials);
        // 筛选已解锁物品
        List<String> unlockedMaterials = new ArrayList<>();
        for (String materialName : allMaterials) {
            if (unlockedStatuses.getOrDefault(materialName, false)) {
                unlockedMaterials.add(materialName);
            }
        }

        int totalItems = unlockedMaterials.size();
        int totalPages = (totalItems + itemsPerPage - 1) / itemsPerPage;

        // 计算当前页的物品范围
        int startIndex = (page - 1) * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, totalItems);

        // 添加当前页的物品
        int slotIndex = 0;
        for (int i = startIndex; i < endIndex && slotIndex < displaySlots.size(); i++) {
            String materialName = unlockedMaterials.get(i);
            int slot = displaySlots.get(slotIndex);
            
            try {
                Material material = Material.valueOf(materialName);
                double emc = configManager.getItems().getDouble("items." + materialName + ".emc");
                String itemName = configManager.getItems().getString("items." + materialName + ".name", materialName);
                ItemStack item = new ItemStack(material);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(ColorUtil.translateColorCodes( "&a" + itemName));
                    List<String> lore = new ArrayList<>();
                    lore.add(ColorUtil.translateColorCodes( "&7EMC: &6" + emc));
                    lore.add(ColorUtil.translateColorCodes( "&7左键: 购买1个"));
                    lore.add(ColorUtil.translateColorCodes( "&7右键: 购买64个"));
                    meta.setLore(lore);
                    item.setItemMeta(meta);
                }
                inventory.setItem(slot, item);
                slotIndex++; // 只有成功放置后才递增
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid material: " + materialName);
                // 不递增 slotIndex，继续尝试下一个物品
            }
        }

        // 添加上一页按钮
        if (page > 1) {
            List<String> prevLore = new ArrayList<>(configManager.getMenus().getStringList("menus.exchange.buttons.prev_page.lore"));
            for (int i = 0; i < prevLore.size(); i++) {
                prevLore.set(i, prevLore.get(i).replace("%prev_page%", String.valueOf(page - 1)));
            }
            ItemStack prevItem = createMenuItem(
                    Material.valueOf(configManager.getMenus().getString("menus.exchange.buttons.prev_page.material")),
                    configManager.getMenus().getString("menus.exchange.buttons.prev_page.display_name"),
                    prevLore
            );
            inventory.setItem(configManager.getMenus().getInt("menus.exchange.buttons.prev_page.slot"), prevItem);
        }

        // 添加下一页按钮
        if (page < totalPages) {
            List<String> nextLore = new ArrayList<>(configManager.getMenus().getStringList("menus.exchange.buttons.next_page.lore"));
            for (int i = 0; i < nextLore.size(); i++) {
                nextLore.set(i, nextLore.get(i).replace("%next_page%", String.valueOf(page + 1)));
            }
            ItemStack nextItem = createMenuItem(
                    Material.valueOf(configManager.getMenus().getString("menus.exchange.buttons.next_page.material")),
                    configManager.getMenus().getString("menus.exchange.buttons.next_page.display_name"),
                    nextLore
            );
            inventory.setItem(configManager.getMenus().getInt("menus.exchange.buttons.next_page.slot"), nextItem);
        }

        // 添加页码信息按钮
        String pageInfoDisplayName = configManager.getMenus().getString("menus.exchange.buttons.page_info.display_name")
                .replace("%page%", String.valueOf(page))
                .replace("%total_pages%", String.valueOf(totalPages));
        List<String> pageInfoLore = new ArrayList<>(configManager.getMenus().getStringList("menus.exchange.buttons.page_info.lore"));
        for (int i = 0; i < pageInfoLore.size(); i++) {
            pageInfoLore.set(i, pageInfoLore.get(i)
                    .replace("%total_items%", String.valueOf(totalItems))
                    .replace("%items_per_page%", String.valueOf(itemsPerPage)));
        }
        ItemStack pageInfoItem = createMenuItem(
                Material.valueOf(configManager.getMenus().getString("menus.exchange.buttons.page_info.material")),
                pageInfoDisplayName,
                pageInfoLore
        );
        inventory.setItem(configManager.getMenus().getInt("menus.exchange.buttons.page_info.slot"), pageInfoItem);

        player.openInventory(inventory);
        playerInventories.put(player.getUniqueId(), inventory);
        playerMenuTypes.put(player.getUniqueId(), "exchange");
        playerExchangePages.put(player.getUniqueId(), page);
    }

    public void openBrowseMenu(Player player) {
        openBrowseMenu(player, 1);
    }

    public void openBrowseMenu(Player player, int page) {
        clearPlayerCache(player.getUniqueId());

        Inventory inventory = Bukkit.createInventory(player, 54, ColorUtil.translateColorCodes(configManager.getMenus().getString("menus.browse.title")));

        // 添加玻璃板
        List<Integer> glassSlots = configManager.getMenus().getIntegerList("menus.browse.glass.slots");
        if (glassSlots.isEmpty()) {
            // 默认值：第一行和第六行（除了按钮位置）
            glassSlots = Arrays.asList(0, 1, 2, 3, 5, 6, 7, 47, 48, 50, 51, 52, 53);
        }
        
        ItemStack glassPane = new ItemStack(Material.valueOf(configManager.getMenus().getString("menus.browse.glass.material", "GRAY_STAINED_GLASS_PANE")));
        ItemMeta glassMeta = glassPane.getItemMeta();
        if (glassMeta != null) {
            glassMeta.setDisplayName(configManager.getMenus().getString("menus.browse.glass.display_name", " "));
            glassPane.setItemMeta(glassMeta);
        }
        
        for (int slot : glassSlots) {
            inventory.setItem(slot, glassPane);
        }

        // 读取物品显示区域配置
        List<Integer> displaySlots = configManager.getMenus().getIntegerList("menus.browse.display.slots");
        if (displaySlots.isEmpty()) {
            // 默认值：第二行到第五行
            displaySlots = Arrays.asList(9, 10, 11, 12, 13, 14, 15, 16, 17,
                                         18, 19, 20, 21, 22, 23, 24, 25, 26,
                                         27, 28, 29, 30, 31, 32, 33, 34, 35,
                                         36, 37, 38, 39, 40, 41, 42, 43, 44);
        }
        int itemsPerPage = displaySlots.size();

        // 添加说明按钮
        ItemStack infoItem = createMenuItem(
                Material.valueOf(configManager.getMenus().getString("menus.browse.buttons.info.material")),
                configManager.getMenus().getString("menus.browse.buttons.info.display_name"),
                configManager.getMenus().getStringList("menus.browse.buttons.info.lore")
        );
        inventory.setItem(configManager.getMenus().getInt("menus.browse.buttons.info.slot"), infoItem);

        // 添加返回按钮
        ItemStack backItem = createMenuItem(
                Material.valueOf(configManager.getMenus().getString("menus.browse.buttons.back.material")),
                configManager.getMenus().getString("menus.browse.buttons.back.display_name"),
                configManager.getMenus().getStringList("menus.browse.buttons.back.lore")
        );
        inventory.setItem(configManager.getMenus().getInt("menus.browse.buttons.back.slot"), backItem);

        // 获取所有物品
        List<String> materials = new ArrayList<>(configManager.getItems().getConfigurationSection("items").getKeys(false));
        int totalItems = materials.size();
        int totalPages = (totalItems + itemsPerPage - 1) / itemsPerPage;

        // 计算当前页的物品范围
        int startIndex = (page - 1) * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, totalItems);

        // 批量获取当前页物品的状态
        List<String> currentPageMaterials = materials.subList(startIndex, endIndex);
        Map<String, Boolean> unlockedStatuses = getCachedUnlockedStatuses(player.getUniqueId(), currentPageMaterials);
        Map<String, Integer> mineProgresses = getCachedMineProgresses(player.getUniqueId(), currentPageMaterials);
        Map<String, Integer> sellProgresses = getCachedSellProgresses(player.getUniqueId(), currentPageMaterials);

        // 添加当前页的物品
        int slotIndex = 0;
        for (int i = startIndex; i < endIndex && slotIndex < displaySlots.size(); i++) {
            String materialName = materials.get(i);
            int slot = displaySlots.get(slotIndex);
            
            try {
                Material material = Material.valueOf(materialName);
                double emc = configManager.getItems().getDouble("items." + materialName + ".emc");
                int requiredMine = configManager.getItems().getInt("items." + materialName + ".required_mine");
                int requiredSell = configManager.getItems().getInt("items." + materialName + ".required_sell");
                String itemName = configManager.getItems().getString("items." + materialName + ".name", materialName);
                ItemStack item = new ItemStack(material);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                        boolean unlocked = unlockedStatuses.getOrDefault(materialName, false);
                        meta.setDisplayName(ColorUtil.translateColorCodes( (unlocked ? "&a" : "&c") + itemName));
                        List<String> lore = new ArrayList<>();
                        lore.add(ColorUtil.translateColorCodes( "&7EMC: &6" + emc));
                        if (requiredMine > 0) {
                            int mineProgress = mineProgresses.getOrDefault(materialName, 0);
                            lore.add(ColorUtil.translateColorCodes( "&7挖掘: &a" + mineProgress + "/" + requiredMine));
                        }
                        if (requiredSell > 0) {
                            int sellProgress = sellProgresses.getOrDefault(materialName, 0);
                            lore.add(ColorUtil.translateColorCodes( "&7出售: &a" + sellProgress + "/" + requiredSell));
                        }
                        if (unlocked) {
                            lore.add(ColorUtil.translateColorCodes( "&a已解锁"));
                        } else {
                            lore.add(ColorUtil.translateColorCodes( "&c未解锁"));
                        }
                        meta.setLore(lore);
                        item.setItemMeta(meta);
                    }
                inventory.setItem(slot, item);
                slotIndex++; // 只有成功放置后才递增
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid material: " + materialName);
                // 不递增 slotIndex，继续尝试下一个物品
            }
        }

        // 添加上一页按钮
        if (page > 1) {
            List<String> prevLore = new ArrayList<>(configManager.getMenus().getStringList("menus.browse.buttons.prev_page.lore"));
            for (int i = 0; i < prevLore.size(); i++) {
                prevLore.set(i, prevLore.get(i).replace("%prev_page%", String.valueOf(page - 1)));
            }
            ItemStack prevItem = createMenuItem(
                    Material.valueOf(configManager.getMenus().getString("menus.browse.buttons.prev_page.material")),
                    configManager.getMenus().getString("menus.browse.buttons.prev_page.display_name"),
                    prevLore
            );
            inventory.setItem(configManager.getMenus().getInt("menus.browse.buttons.prev_page.slot"), prevItem);
        }

        // 添加下一页按钮
        if (page < totalPages) {
            List<String> nextLore = new ArrayList<>(configManager.getMenus().getStringList("menus.browse.buttons.next_page.lore"));
            for (int i = 0; i < nextLore.size(); i++) {
                nextLore.set(i, nextLore.get(i).replace("%next_page%", String.valueOf(page + 1)));
            }
            ItemStack nextItem = createMenuItem(
                    Material.valueOf(configManager.getMenus().getString("menus.browse.buttons.next_page.material")),
                    configManager.getMenus().getString("menus.browse.buttons.next_page.display_name"),
                    nextLore
            );
            inventory.setItem(configManager.getMenus().getInt("menus.browse.buttons.next_page.slot"), nextItem);
        }

        // 添加页码信息按钮
        String pageInfoDisplayName = configManager.getMenus().getString("menus.browse.buttons.page_info.display_name")
                .replace("%page%", String.valueOf(page))
                .replace("%total_pages%", String.valueOf(totalPages));
        List<String> pageInfoLore = new ArrayList<>(configManager.getMenus().getStringList("menus.browse.buttons.page_info.lore"));
        for (int i = 0; i < pageInfoLore.size(); i++) {
            pageInfoLore.set(i, pageInfoLore.get(i)
                    .replace("%total_items%", String.valueOf(totalItems))
                    .replace("%items_per_page%", String.valueOf(itemsPerPage)));
        }
        ItemStack pageInfoItem = createMenuItem(
                Material.valueOf(configManager.getMenus().getString("menus.browse.buttons.page_info.material")),
                pageInfoDisplayName,
                pageInfoLore
        );
        inventory.setItem(configManager.getMenus().getInt("menus.browse.buttons.page_info.slot"), pageInfoItem);

        player.openInventory(inventory);
        playerInventories.put(player.getUniqueId(), inventory);
        playerMenuTypes.put(player.getUniqueId(), "browse");
        playerBrowsePages.put(player.getUniqueId(), page);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        Inventory inventory = event.getInventory();

        if (!playerInventories.containsKey(player.getUniqueId()) || playerInventories.get(player.getUniqueId()) != inventory) {
            return;
        }

        ItemStack clickedItem = event.getCurrentItem();
        String menuType = playerMenuTypes.get(player.getUniqueId());

        if (menuType == null) {
            return;
        }

        if (menuType.equals("sell")) {
            int slot = event.getRawSlot();
            
            // 获取按钮位置
            int infoSlot = configManager.getMenus().getInt("menus.sell.buttons.info.slot");
            int sellSlot = configManager.getMenus().getInt("menus.sell.buttons.sell.slot");
            int backSlot = configManager.getMenus().getInt("menus.sell.buttons.back.slot");
            
            // 检查是否点击的是按钮
            if (clickedItem != null && clickedItem.getType() != Material.AIR) {
                if (slot == infoSlot || slot == sellSlot || slot == backSlot) {
                    event.setCancelled(true);
                    handleSellMenuClick(player, inventory, clickedItem);
                    return;
                }
            }
            
            // slot >= 54 表示玩家点击的是自己的背包，允许
            if (slot >= 54) {
                return;
            }
            
            // slot < 9 或 slot >= 45 表示点击的是玻璃板区域，阻止
            if (slot < 9 || slot >= 45) {
                event.setCancelled(true);
                return;
            }
            
            // slot >= 9 && slot < 45 表示点击的是中间区域，允许放置物品
            // 但需要检查是否是按钮位置
            if (slot == infoSlot || slot == sellSlot || slot == backSlot) {
                event.setCancelled(true);
                return;
            }
            
            // 允许在中间行放置物品
        } else {
            // 其他菜单取消所有点击事件
            event.setCancelled(true);
            
            if (clickedItem == null || clickedItem.getType() == Material.AIR) {
                return;
            }

            switch (menuType) {
                case "main":
                    handleMainMenuClick(player, clickedItem);
                    break;
                case "exchange":
                    // 兑换菜单点击处理
                    int exchangeSlot = event.getRawSlot();
                    // 读取按钮位置配置
                    int backSlot = configManager.getMenus().getInt("menus.exchange.buttons.back.slot", 8);
                    int prevPageSlot = configManager.getMenus().getInt("menus.exchange.buttons.prev_page.slot", 45);
                    int nextPageSlot = configManager.getMenus().getInt("menus.exchange.buttons.next_page.slot", 53);
                    int pageInfoSlot = configManager.getMenus().getInt("menus.exchange.buttons.page_info.slot", 49);
                    
                    if (exchangeSlot == backSlot) { // 返回按钮
                        event.setCancelled(true);
                        openMainMenu(player);
                    } else if (exchangeSlot == prevPageSlot) { // 上一页按钮
                        event.setCancelled(true);
                        int currentPage = playerExchangePages.getOrDefault(player.getUniqueId(), 1);
                        if (currentPage > 1) {
                            openExchangeMenu(player, currentPage - 1);
                        }
                    } else if (exchangeSlot == nextPageSlot) { // 下一页按钮
                        event.setCancelled(true);
                        int currentPage = playerExchangePages.getOrDefault(player.getUniqueId(), 1);
                        openExchangeMenu(player, currentPage + 1);
                    } else if (exchangeSlot == pageInfoSlot) { // 页码信息按钮
                        event.setCancelled(true);
                        // 页码信息按钮不做任何操作
                    } else {
                        // 检查是否点击的是物品显示区域
                        List<Integer> displaySlots = configManager.getMenus().getIntegerList("menus.exchange.display.slots");
                        if (displaySlots.isEmpty()) {
                            // 默认值：第二行到第五行
                            displaySlots = Arrays.asList(9, 10, 11, 12, 13, 14, 15, 16, 17,
                                                         18, 19, 20, 21, 22, 23, 24, 25, 26,
                                                         27, 28, 29, 30, 31, 32, 33, 34, 35,
                                                         36, 37, 38, 39, 40, 41, 42, 43, 44);
                        }
                        if (displaySlots.contains(exchangeSlot)) {
                            handleExchangeMenuClick(player, clickedItem, event.isRightClick());
                        }
                    }
                    break;
                case "browse":
                        // 浏览菜单点击处理
                        int browseSlot = event.getRawSlot();
                        // 读取按钮位置配置
                        int browseBackSlot = configManager.getMenus().getInt("menus.browse.buttons.back.slot", 45);
                        int browsePrevPageSlot = configManager.getMenus().getInt("menus.browse.buttons.prev_page.slot", 46);
                        int browseNextPageSlot = configManager.getMenus().getInt("menus.browse.buttons.next_page.slot", 52);
                        int browsePageInfoSlot = configManager.getMenus().getInt("menus.browse.buttons.page_info.slot", 49);
                        
                        if (browseSlot == browseBackSlot) { // 返回按钮
                            event.setCancelled(true);
                            openMainMenu(player);
                        } else if (browseSlot == browsePrevPageSlot) { // 上一页按钮
                            event.setCancelled(true);
                            int currentPage = playerBrowsePages.getOrDefault(player.getUniqueId(), 1);
                            if (currentPage > 1) {
                                openBrowseMenu(player, currentPage - 1);
                            }
                        } else if (browseSlot == browseNextPageSlot) { // 下一页按钮
                            event.setCancelled(true);
                            int currentPage = playerBrowsePages.getOrDefault(player.getUniqueId(), 1);
                            openBrowseMenu(player, currentPage + 1);
                        } else if (browseSlot == browsePageInfoSlot) { // 页码信息按钮
                            event.setCancelled(true);
                            // 页码信息按钮不做任何操作
                        }
                        break;
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Player player = (Player) event.getWhoClicked();
        Inventory inventory = event.getInventory();

        if (!playerInventories.containsKey(player.getUniqueId()) || playerInventories.get(player.getUniqueId()) != inventory) {
            return;
        }

        String menuType = playerMenuTypes.get(player.getUniqueId());
        if (menuType == null) {
            return;
        }

        if (menuType.equals("sell")) {
            // 检查拖拽的槽位
            for (int slot : event.getRawSlots()) {
                // 阻止拖拽到顶部和底部玻璃板区域
                if (slot < 9 || slot >= 45) {
                    event.setCancelled(true);
                    return;
                }
                // 阻止拖拽到按钮位置
                if (slot == configManager.getMenus().getInt("menus.sell.buttons.info.slot") ||
                        slot == configManager.getMenus().getInt("menus.sell.buttons.sell.slot") ||
                        slot == configManager.getMenus().getInt("menus.sell.buttons.back.slot")) {
                    event.setCancelled(true);
                    return;
                }
            }
            // 允许拖拽到中间区域（9-44）
        } else {
            // 其他菜单取消所有拖拽事件
            event.setCancelled(true);
        }
    }

    private void handleMainMenuClick(Player player, ItemStack clickedItem) {
        String displayName = clickedItem.getItemMeta().getDisplayName();
        String sellDisplayName = ColorUtil.translateColorCodes( configManager.getMenus().getString("menus.main.buttons.sell.display_name"));
        String exchangeDisplayName = ColorUtil.translateColorCodes( configManager.getMenus().getString("menus.main.buttons.exchange.display_name"));
        String browseDisplayName = ColorUtil.translateColorCodes( configManager.getMenus().getString("menus.main.buttons.browse.display_name"));
        String closeDisplayName = ColorUtil.translateColorCodes( configManager.getMenus().getString("menus.main.buttons.close.display_name"));
        
        if (displayName.equals(sellDisplayName)) {
            openSellMenu(player);
        } else if (displayName.equals(exchangeDisplayName)) {
            openExchangeMenu(player);
        } else if (displayName.equals(browseDisplayName)) {
            openBrowseMenu(player);
        } else if (displayName.equals(closeDisplayName)) {
            player.closeInventory();
        }
    }

    private void handleSellMenuClick(Player player, Inventory inventory, ItemStack clickedItem) {
        String displayName = clickedItem.getItemMeta().getDisplayName();
        String sellDisplayName = ColorUtil.translateColorCodes( configManager.getMenus().getString("menus.sell.buttons.sell.display_name"));
        String backDisplayName = ColorUtil.translateColorCodes( configManager.getMenus().getString("menus.sell.buttons.back.display_name"));
        
        if (displayName.equals(sellDisplayName)) {
            // 处理出售
            final double[] totalEMC = {0};
            final List<ItemStack> returnItems = new ArrayList<>();
            final List<ItemStack> itemsToRemove = new ArrayList<>();
            final List<String> materialNames = new ArrayList<>();
            final List<Integer> amounts = new ArrayList<>();

            // 收集需要处理的物品
            for (int i = 0; i < inventory.getSize(); i++) {
                // 只处理中间行（9-44）的物品
                if (i < 9 || i >= 45) {
                    continue;
                }
                
                if (i == configManager.getMenus().getInt("menus.sell.buttons.info.slot") ||
                        i == configManager.getMenus().getInt("menus.sell.buttons.sell.slot") ||
                        i == configManager.getMenus().getInt("menus.sell.buttons.back.slot")) {
                    continue;
                }

                ItemStack item = inventory.getItem(i);
                if (item != null && item.getType() != Material.AIR) {
                    String materialName = item.getType().name();
                    if (configManager.getItems().contains("items." + materialName)) {
                        double emcPerItem = configManager.getItems().getDouble("items." + materialName + ".emc");
                        int amount = item.getAmount();
                        totalEMC[0] += emcPerItem * amount;
                        materialNames.add(materialName);
                        amounts.add(amount);
                        itemsToRemove.add(item);
                    } else {
                        returnItems.add(item);
                    }
                    inventory.setItem(i, null);
                }
            }

            // 异步处理数据库操作
            if (totalEMC[0] > 0) {
                final double finalTotalEMC = totalEMC[0];
                final List<String> finalMaterialNames = materialNames;
                final List<Integer> finalAmounts = amounts;
                
                runAsync(player, () -> {
                    // 计算税费
                    double taxRate = configManager.getConfig().getDouble("sell_tax", 0.05);
                    if (player.hasPermission("alemcexchange.notax")) {
                        taxRate = 0.0;
                    } else if (player.hasPermission("alemcexchange.premium")) {
                        taxRate = configManager.getConfig().getDouble("tax_rates.alemcexchange.premium", 0.01);
                    } else if (player.hasPermission("alemcexchange.vip")) {
                        taxRate = configManager.getConfig().getDouble("tax_rates.alemcexchange.vip", 0.03);
                    }

                    double tax = finalTotalEMC * taxRate;
                    final double netEMC = finalTotalEMC - tax;

                    try {
                        // 处理出售进度和解锁
                        for (int i = 0; i < finalMaterialNames.size(); i++) {
                            String materialName = finalMaterialNames.get(i);
                            int amount = finalAmounts.get(i);
                            
                            // 检查物品是否已解锁
                            if (!databaseManager.isUnlocked(player.getUniqueId(), materialName)) {
                                int requiredSell = configManager.getItems().getInt("items." + materialName + ".required_sell");
                                if (requiredSell > 0) {
                                    int currentProgress = databaseManager.getSellProgress(player.getUniqueId(), materialName);
                                    int addAmount = Math.min(amount, requiredSell - currentProgress);
                                    if (addAmount > 0) {
                                        databaseManager.addSellProgress(player.getUniqueId(), materialName, addAmount);
                                    }
                                }
                                // 检查是否解锁
                                checkUnlock(player, materialName);
                            }
                        }

                        // 增加EMC余额
                        databaseManager.addEMCBalance(player.getUniqueId(), netEMC);
                        
                        // 在主线程中发送消息，适配 Paper 和 Folia
                        runSync(player, () -> {
                            String message = configManager.getLang().getString("prefix") + 
                                    configManager.getLang().getString("menu.sell.success").replace("{amount}", String.format("%.2f", netEMC));
                            player.sendMessage(ColorUtil.translateColorCodes( message));
                        });
                    } catch (SQLException | ClassNotFoundException e) {
                        // 在主线程中发送错误消息，适配 Paper 和 Folia
                        runSync(player, () -> {
                            player.sendMessage(ColorUtil.translateColorCodes( configManager.getLang().getString("prefix") + configManager.getLang().getString("menu.sell.failed")));
                            // 出售失败，将物品返还给玩家
                            for (ItemStack item : itemsToRemove) {
                                Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
                                for (ItemStack leftoverItem : leftover.values()) {
                                    player.getWorld().dropItemNaturally(player.getLocation(), leftoverItem);
                                }
                            }
                        });
                        plugin.getLogger().severe("Error handling sell: " + e.getMessage());
                    }
                });
            }

            // 返回不可出售的物品
            for (ItemStack item : returnItems) {
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
                for (ItemStack leftoverItem : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftoverItem);
                }
            }
        } else if (displayName.equals(backDisplayName)) {
            openMainMenu(player);
        }
    }

    private void handleExchangeMenuClick(Player player, ItemStack clickedItem, boolean isRightClick) {
        if (clickedItem.getItemMeta() == null) {
            return;
        }

        String displayName = clickedItem.getItemMeta().getDisplayName();
        String infoDisplayName = ColorUtil.translateColorCodes( configManager.getMenus().getString("menus.exchange.buttons.info.display_name"));
        String backDisplayName = ColorUtil.translateColorCodes( configManager.getMenus().getString("menus.exchange.buttons.back.display_name"));
        
        if (displayName.equals(infoDisplayName)) {
            return;
        } else if (displayName.equals(backDisplayName)) {
            openMainMenu(player);
            return;
        }

        Material material = clickedItem.getType();
        String materialName = material.name();
        try {
            double emcPerItem = configManager.getItems().getDouble("items." + materialName + ".emc");
            int amount = isRightClick ? 64 : 1;
            double totalEMC = emcPerItem * amount;

            if (!databaseManager.hasSufficientEMC(player.getUniqueId(), totalEMC)) {
                player.sendMessage(ColorUtil.translateColorCodes( configManager.getLang().getString("prefix") + configManager.getLang().getString("command.insufficient-funds")));
                return;
            }

            int maxStackSize = material.getMaxStackSize();
            int emptySlots = 0;
            int existingPartialSlotSpace = 0;

            ItemStack[] contents = player.getInventory().getContents();
            for (int i = 0; i < 36; i++) {
                ItemStack invItem = contents[i];
                if (invItem == null) {
                    emptySlots++;
                } else if (invItem.getType() == material) {
                    int currentAmount = invItem.getAmount();
                    if (currentAmount < maxStackSize) {
                        existingPartialSlotSpace += (maxStackSize - currentAmount);
                    }
                }
            }

            int canAdd = (emptySlots * maxStackSize) + existingPartialSlotSpace;

            if (configManager.isDebugEnabled()) {
                plugin.getLogger().info("[DEBUG] 购买检查 - 玩家: " + player.getName() + ", 材料: " + materialName + ", 请求: " + amount + ", 可放入: " + canAdd + ", 空格子: " + emptySlots + ", 部分空间: " + existingPartialSlotSpace);
            }

            if (canAdd < amount) {
                player.sendMessage(ColorUtil.translateColorCodes( configManager.getLang().getString("prefix") + configManager.getLang().getString("menu.exchange.inventory-full")));
                return;
            }

            databaseManager.addEMCBalance(player.getUniqueId(), -totalEMC);
            clearPlayerCache(player.getUniqueId());

            if (configManager.isDebugEnabled()) {
                plugin.getLogger().info("[DEBUG] 购买执行 - 玩家: " + player.getName() + ", 材料: " + materialName + ", 数量: " + amount + ", EMC: " + totalEMC);
            }

            ItemStack itemToAdd = new ItemStack(material, amount);
            player.getInventory().addItem(itemToAdd);

            if (configManager.isDebugEnabled()) {
                plugin.getLogger().info("[DEBUG] 购买完成 - 玩家: " + player.getName() + ", 材料: " + materialName + ", 数量: " + amount);
            }

            String message = configManager.getLang().getString("prefix") +
                    configManager.getLang().getString("menu.exchange.purchase-success").replace("{amount}", String.format("%.2f", totalEMC));
            player.sendMessage(ColorUtil.translateColorCodes( message));
        } catch (SQLException | ClassNotFoundException e) {
            plugin.getLogger().warning("Error processing purchase: " + e.getMessage());
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        Inventory inventory = event.getInventory();
        String menuType = playerMenuTypes.get(player.getUniqueId());
        
        // 如果是出售菜单，将物品返回给玩家
        if ("sell".equals(menuType) && inventory != null) {
            // 遍历出售菜单的中间区域（索引9-44），将物品返回给玩家
            for (int i = 9; i < 45; i++) {
                ItemStack item = inventory.getItem(i);
                if (item != null && item.getType() != Material.AIR) {
                    player.getInventory().addItem(item);
                }
            }
        }
        
        playerInventories.remove(player.getUniqueId());
        playerMenuTypes.remove(player.getUniqueId());
        playerBrowsePages.remove(player.getUniqueId());
        playerExchangePages.remove(player.getUniqueId());
    }

    private ItemStack createMenuItem(Material material, String displayName, List<String> lore) {
        // 生成缓存键
        String cacheKey = material.name() + ":" + displayName + ":" + lore.toString();
        
        // 检查缓存
        if (menuItemsCache.containsKey(cacheKey)) {
            return menuItemsCache.get(cacheKey);
        }
        
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.translateColorCodes( displayName));
            List<String> coloredLore = new ArrayList<>();
            for (String line : lore) {
                coloredLore.add(ColorUtil.translateColorCodes( line));
            }
            meta.setLore(coloredLore);
            item.setItemMeta(meta);
        }
        
        // 缓存物品
        menuItemsCache.put(cacheKey, item);
        return item;
    }
    
    // 获取缓存的玩家余额
    private double getCachedBalance(UUID playerUUID) {
        if (playerBalanceCache.containsKey(playerUUID)) {
            // 更新时间戳
            playerCacheTimestamps.put(playerUUID, System.currentTimeMillis());
            return playerBalanceCache.get(playerUUID);
        }
        
        try {
            double balance = databaseManager.getEMCBalance(playerUUID);
            playerBalanceCache.put(playerUUID, balance);
            playerCacheTimestamps.put(playerUUID, System.currentTimeMillis());
            return balance;
        } catch (SQLException | ClassNotFoundException e) {
            plugin.getLogger().severe("Error getting balance: " + e.getMessage());
            return 0.0;
        }
    }
    
    // 批量获取缓存的解锁状态
    private Map<String, Boolean> getCachedUnlockedStatuses(UUID playerUUID, List<String> materials) {
        if (playerUnlockedCache.containsKey(playerUUID)) {
            Map<String, Boolean> cachedStatuses = playerUnlockedCache.get(playerUUID);
            boolean allCached = true;
            for (String material : materials) {
                if (!cachedStatuses.containsKey(material)) {
                    allCached = false;
                    break;
                }
            }
            if (allCached) {
                // 更新时间戳
                playerCacheTimestamps.put(playerUUID, System.currentTimeMillis());
                Map<String, Boolean> result = new HashMap<>();
                for (String material : materials) {
                    result.put(material, cachedStatuses.get(material));
                }
                return result;
            }
        }
        
        try {
            Map<String, Boolean> statuses = databaseManager.getUnlockedStatuses(playerUUID, materials);
            playerUnlockedCache.computeIfAbsent(playerUUID, k -> new HashMap<>()).putAll(statuses);
            playerCacheTimestamps.put(playerUUID, System.currentTimeMillis());
            return statuses;
        } catch (SQLException | ClassNotFoundException e) {
            plugin.getLogger().severe("Error getting unlocked statuses: " + e.getMessage());
            Map<String, Boolean> defaultStatuses = new HashMap<>();
            for (String material : materials) {
                defaultStatuses.put(material, false);
            }
            return defaultStatuses;
        }
    }
    
    // 批量获取缓存的挖掘进度
    private Map<String, Integer> getCachedMineProgresses(UUID playerUUID, List<String> materials) {
        if (playerMineProgressCache.containsKey(playerUUID)) {
            Map<String, Integer> cachedProgresses = playerMineProgressCache.get(playerUUID);
            boolean allCached = true;
            for (String material : materials) {
                if (!cachedProgresses.containsKey(material)) {
                    allCached = false;
                    break;
                }
            }
            if (allCached) {
                Map<String, Integer> result = new HashMap<>();
                for (String material : materials) {
                    result.put(material, cachedProgresses.get(material));
                }
                return result;
            }
        }
        
        try {
            Map<String, Integer> progresses = databaseManager.getMineProgresses(playerUUID, materials);
            playerMineProgressCache.computeIfAbsent(playerUUID, k -> new HashMap<>()).putAll(progresses);
            return progresses;
        } catch (SQLException | ClassNotFoundException e) {
            plugin.getLogger().severe("Error getting mine progresses: " + e.getMessage());
            Map<String, Integer> defaultProgresses = new HashMap<>();
            for (String material : materials) {
                defaultProgresses.put(material, 0);
            }
            return defaultProgresses;
        }
    }
    
    // 批量获取缓存的出售进度
    private Map<String, Integer> getCachedSellProgresses(UUID playerUUID, List<String> materials) {
        if (playerSellProgressCache.containsKey(playerUUID)) {
            Map<String, Integer> cachedProgresses = playerSellProgressCache.get(playerUUID);
            boolean allCached = true;
            for (String material : materials) {
                if (!cachedProgresses.containsKey(material)) {
                    allCached = false;
                    break;
                }
            }
            if (allCached) {
                Map<String, Integer> result = new HashMap<>();
                for (String material : materials) {
                    result.put(material, cachedProgresses.get(material));
                }
                return result;
            }
        }
        
        try {
            Map<String, Integer> progresses = databaseManager.getSellProgresses(playerUUID, materials);
            playerSellProgressCache.computeIfAbsent(playerUUID, k -> new HashMap<>()).putAll(progresses);
            return progresses;
        } catch (SQLException | ClassNotFoundException e) {
            plugin.getLogger().severe("Error getting sell progresses: " + e.getMessage());
            Map<String, Integer> defaultProgresses = new HashMap<>();
            for (String material : materials) {
                defaultProgresses.put(material, 0);
            }
            return defaultProgresses;
        }
    }
    
    // 清理玩家缓存
    public void clearPlayerCache(UUID playerUUID) {
        playerBalanceCache.remove(playerUUID);
        playerUnlockedCache.remove(playerUUID);
        playerMineProgressCache.remove(playerUUID);
        playerSellProgressCache.remove(playerUUID);
        playerCacheTimestamps.remove(playerUUID);
    }
    
    // 清理所有缓存
    public void clearAllCache() {
        menuItemsCache.clear();
        playerBalanceCache.clear();
        playerUnlockedCache.clear();
        playerMineProgressCache.clear();
        playerSellProgressCache.clear();
        playerCacheTimestamps.clear();
    }

    private void checkUnlock(Player player, String materialName) throws SQLException, ClassNotFoundException {
        int requiredMine = configManager.getItems().getInt("items." + materialName + ".required_mine");
        int requiredSell = configManager.getItems().getInt("items." + materialName + ".required_sell");

        int mineProgress = databaseManager.getMineProgress(player.getUniqueId(), materialName);
        int sellProgress = databaseManager.getSellProgress(player.getUniqueId(), materialName);

        boolean mineCondition = requiredMine == 0 || mineProgress >= requiredMine;
        boolean sellCondition = requiredSell == 0 || sellProgress >= requiredSell;

        if (mineCondition && sellCondition && !databaseManager.isUnlocked(player.getUniqueId(), materialName)) {
            databaseManager.unlockItem(player.getUniqueId(), materialName);
            // 解锁后清理玩家缓存，确保下次打开菜单时能看到新解锁的物品
            clearPlayerCache(player.getUniqueId());
            // 获取物品名称，如果配置了name选项则使用配置的名称，否则使用材质名称
            String itemDisplayName = configManager.getItems().getString("items." + materialName + ".name", materialName);
            final String message = configManager.getLang().getString("prefix") + 
                    configManager.getLang().getString("mine.unlocked").replace("{item}", itemDisplayName);
            // 在主线程中发送消息，适配 Paper 和 Folia
            runSync(player, () -> {
                player.sendMessage(ColorUtil.translateColorCodes( message));
            });
        }
    }
    
    /**
     * 运行异步任务，适配 Paper 和 Folia
     */
    private void runAsync(Player player, Runnable task) {
        try {
            // 尝试使用 Folia 的调度器 API
            Class<?> scheduledTaskClass = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");
            Class<?> regionizedServerClass = Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            Object server = Bukkit.getServer();
            if (regionizedServerClass.isInstance(server)) {
                // 使用 Folia 调度器
                Class<?> schedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.Scheduler");
                Object scheduler = regionizedServerClass.getMethod("getGlobalRegionScheduler").invoke(server);
                schedulerClass.getMethod("runAsync", JavaPlugin.class, Runnable.class).invoke(scheduler, plugin, task);
            } else {
                // 如果是 Paper 服务器，使用标准 Bukkit 调度器
                Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            }
        } catch (Exception e) {
            // 如果是 Paper 服务器，使用标准 Bukkit 调度器
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }
    
    /**
     * 运行同步任务，适配 Paper 和 Folia
     */
    private void runSync(Player player, Runnable task) {
        try {
            // 尝试使用 Folia 的调度器 API
            Class<?> scheduledTaskClass = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");
            Class<?> regionizedServerClass = Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            Object server = Bukkit.getServer();
            if (regionizedServerClass.isInstance(server)) {
                // 使用 Folia 调度器
                Class<?> schedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.Scheduler");
                Object scheduler = regionizedServerClass.getMethod("getGlobalRegionScheduler").invoke(server);
                schedulerClass.getMethod("run", JavaPlugin.class, Runnable.class, Object.class).invoke(scheduler, plugin, task, null);
            } else {
                // 如果是 Paper 服务器，使用标准 Bukkit 调度器
                Bukkit.getScheduler().runTask(plugin, task);
            }
        } catch (Exception e) {
            // 如果是 Paper 服务器，使用标准 Bukkit 调度器
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

}
