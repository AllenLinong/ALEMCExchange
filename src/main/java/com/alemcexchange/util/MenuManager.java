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
import java.util.concurrent.ConcurrentHashMap;

public class MenuManager implements Listener {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final DatabaseManager databaseManager;
    private final SchedulerUtil schedulerUtil;
    private final ConcurrentHashMap<UUID, Inventory> playerInventories = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> playerMenuTypes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> playerBrowsePages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> playerExchangePages = new ConcurrentHashMap<>();
    private final int ITEMS_PER_PAGE = 36;
    
    private final ConcurrentHashMap<String, ItemStack> menuItemsCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Double> playerBalanceCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Map<String, Boolean>> playerUnlockedCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Map<String, Integer>> playerMineProgressCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Map<String, Integer>> playerSellProgressCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> playerCacheTimestamps = new ConcurrentHashMap<>();

    public MenuManager(JavaPlugin plugin, ConfigManager configManager, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.databaseManager = databaseManager;
        this.schedulerUtil = new SchedulerUtil(plugin);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        
        startCacheCleanupTask();
    }
    
    private void startCacheCleanupTask() {
        schedulerUtil.runTimerAsync(() -> {
            if (menuItemsCache.size() > 1000) {
                Map<String, ItemStack> newCache = new ConcurrentHashMap<>();
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
            }
            
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
        }, 0L, 5 * 60 * 20L);
    }

    public void openMainMenu(Player player) {
        clearPlayerCache(player.getUniqueId());

        Inventory inventory = Bukkit.createInventory(player, 27, ColorUtil.translateColorCodes(configManager.getMenus().getString("menus.main.title")));

        double balance = getCachedBalance(player.getUniqueId());

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

        List<Integer> glassSlots = configManager.getMenus().getIntegerList("menus.sell.glass.slots");
        if (glassSlots.isEmpty()) {
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

        ItemStack infoItem = createMenuItem(
                Material.valueOf(configManager.getMenus().getString("menus.sell.buttons.info.material")),
                configManager.getMenus().getString("menus.sell.buttons.info.display_name"),
                configManager.getMenus().getStringList("menus.sell.buttons.info.lore")
        );
        inventory.setItem(configManager.getMenus().getInt("menus.sell.buttons.info.slot"), infoItem);

        List<String> sellLore = new ArrayList<>(configManager.getMenus().getStringList("menus.sell.buttons.sell.lore"));
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

        List<Integer> glassSlots = configManager.getMenus().getIntegerList("menus.exchange.glass.slots");
        if (glassSlots.isEmpty()) {
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

        List<Integer> displaySlots = configManager.getMenus().getIntegerList("menus.exchange.display.slots");
        if (displaySlots.isEmpty()) {
            displaySlots = Arrays.asList(9, 10, 11, 12, 13, 14, 15, 16, 17,
                                         18, 19, 20, 21, 22, 23, 24, 25, 26,
                                         27, 28, 29, 30, 31, 32, 33, 34, 35,
                                         36, 37, 38, 39, 40, 41, 42, 43, 44);
        }
        int itemsPerPage = displaySlots.size();

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

        ItemStack backItem = createMenuItem(
                Material.valueOf(configManager.getMenus().getString("menus.exchange.buttons.back.material")),
                configManager.getMenus().getString("menus.exchange.buttons.back.display_name"),
                configManager.getMenus().getStringList("menus.exchange.buttons.back.lore")
        );
        inventory.setItem(configManager.getMenus().getInt("menus.exchange.buttons.back.slot"), backItem);

        List<String> allMaterials = new ArrayList<>(configManager.getItems().getConfigurationSection("items").getKeys(false));
        Map<String, Boolean> unlockedStatuses = getCachedUnlockedStatuses(player.getUniqueId(), allMaterials);
        List<String> unlockedMaterials = new ArrayList<>();
        for (String materialName : allMaterials) {
            if (unlockedStatuses.getOrDefault(materialName, false)) {
                unlockedMaterials.add(materialName);
            }
        }

        int totalItems = unlockedMaterials.size();
        int totalPages = (totalItems + itemsPerPage - 1) / itemsPerPage;

        int startIndex = (page - 1) * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, totalItems);

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
                slotIndex++;
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid material: " + materialName);
            }
        }

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

        List<Integer> glassSlots = configManager.getMenus().getIntegerList("menus.browse.glass.slots");
        if (glassSlots.isEmpty()) {
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

        List<Integer> displaySlots = configManager.getMenus().getIntegerList("menus.browse.display.slots");
        if (displaySlots.isEmpty()) {
            displaySlots = Arrays.asList(9, 10, 11, 12, 13, 14, 15, 16, 17,
                                         18, 19, 20, 21, 22, 23, 24, 25, 26,
                                         27, 28, 29, 30, 31, 32, 33, 34, 35,
                                         36, 37, 38, 39, 40, 41, 42, 43, 44);
        }
        int itemsPerPage = displaySlots.size();

        ItemStack infoItem = createMenuItem(
                Material.valueOf(configManager.getMenus().getString("menus.browse.buttons.info.material")),
                configManager.getMenus().getString("menus.browse.buttons.info.display_name"),
                configManager.getMenus().getStringList("menus.browse.buttons.info.lore")
        );
        inventory.setItem(configManager.getMenus().getInt("menus.browse.buttons.info.slot"), infoItem);

        ItemStack backItem = createMenuItem(
                Material.valueOf(configManager.getMenus().getString("menus.browse.buttons.back.material")),
                configManager.getMenus().getString("menus.browse.buttons.back.display_name"),
                configManager.getMenus().getStringList("menus.browse.buttons.back.lore")
        );
        inventory.setItem(configManager.getMenus().getInt("menus.browse.buttons.back.slot"), backItem);

        List<String> materials = new ArrayList<>(configManager.getItems().getConfigurationSection("items").getKeys(false));
        int totalItems = materials.size();
        int totalPages = (totalItems + itemsPerPage - 1) / itemsPerPage;

        int startIndex = (page - 1) * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, totalItems);

        List<String> currentPageMaterials = materials.subList(startIndex, endIndex);
        Map<String, Boolean> unlockedStatuses = getCachedUnlockedStatuses(player.getUniqueId(), currentPageMaterials);
        Map<String, Integer> mineProgresses = getCachedMineProgresses(player.getUniqueId(), currentPageMaterials);
        Map<String, Integer> sellProgresses = getCachedSellProgresses(player.getUniqueId(), currentPageMaterials);

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
                slotIndex++;
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid material: " + materialName);
            }
        }

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
            
            int infoSlot = configManager.getMenus().getInt("menus.sell.buttons.info.slot");
            int sellSlot = configManager.getMenus().getInt("menus.sell.buttons.sell.slot");
            int backSlot = configManager.getMenus().getInt("menus.sell.buttons.back.slot");
            
            if (clickedItem != null && clickedItem.getType() != Material.AIR) {
                if (slot == infoSlot || slot == sellSlot || slot == backSlot) {
                    event.setCancelled(true);
                    handleSellMenuClick(player, inventory, clickedItem);
                    return;
                }
            }
            
            if (slot >= 54) {
                return;
            }
            
            if (slot < 9 || slot >= 45) {
                event.setCancelled(true);
                return;
            }
            
            if (slot == infoSlot || slot == sellSlot || slot == backSlot) {
                event.setCancelled(true);
                return;
            }
        } else {
            event.setCancelled(true);
            
            if (clickedItem == null || clickedItem.getType() == Material.AIR) {
                return;
            }

            switch (menuType) {
                case "main":
                    handleMainMenuClick(player, clickedItem);
                    break;
                case "exchange":
                    int exchangeSlot = event.getRawSlot();
                    int backSlot = configManager.getMenus().getInt("menus.exchange.buttons.back.slot", 8);
                    int prevPageSlot = configManager.getMenus().getInt("menus.exchange.buttons.prev_page.slot", 45);
                    int nextPageSlot = configManager.getMenus().getInt("menus.exchange.buttons.next_page.slot", 53);
                    int pageInfoSlot = configManager.getMenus().getInt("menus.exchange.buttons.page_info.slot", 49);
                    
                    if (exchangeSlot == backSlot) {
                        event.setCancelled(true);
                        openMainMenu(player);
                    } else if (exchangeSlot == prevPageSlot) {
                        event.setCancelled(true);
                        int currentPage = playerExchangePages.getOrDefault(player.getUniqueId(), 1);
                        if (currentPage > 1) {
                            openExchangeMenu(player, currentPage - 1);
                        }
                    } else if (exchangeSlot == nextPageSlot) {
                        event.setCancelled(true);
                        int currentPage = playerExchangePages.getOrDefault(player.getUniqueId(), 1);
                        openExchangeMenu(player, currentPage + 1);
                    } else if (exchangeSlot == pageInfoSlot) {
                        event.setCancelled(true);
                    } else {
                        List<Integer> displaySlots = configManager.getMenus().getIntegerList("menus.exchange.display.slots");
                        if (displaySlots.isEmpty()) {
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
                        int browseSlot = event.getRawSlot();
                        int browseBackSlot = configManager.getMenus().getInt("menus.browse.buttons.back.slot", 45);
                        int browsePrevPageSlot = configManager.getMenus().getInt("menus.browse.buttons.prev_page.slot", 46);
                        int browseNextPageSlot = configManager.getMenus().getInt("menus.browse.buttons.next_page.slot", 52);
                        int browsePageInfoSlot = configManager.getMenus().getInt("menus.browse.buttons.page_info.slot", 49);
                        
                        if (browseSlot == browseBackSlot) {
                            event.setCancelled(true);
                            openMainMenu(player);
                        } else if (browseSlot == browsePrevPageSlot) {
                            event.setCancelled(true);
                            int currentPage = playerBrowsePages.getOrDefault(player.getUniqueId(), 1);
                            if (currentPage > 1) {
                                openBrowseMenu(player, currentPage - 1);
                            }
                        } else if (browseSlot == browseNextPageSlot) {
                            event.setCancelled(true);
                            int currentPage = playerBrowsePages.getOrDefault(player.getUniqueId(), 1);
                            openBrowseMenu(player, currentPage + 1);
                        } else if (browseSlot == browsePageInfoSlot) {
                            event.setCancelled(true);
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
            for (int slot : event.getRawSlots()) {
                if (slot < 9 || slot >= 45) {
                    event.setCancelled(true);
                    return;
                }
                if (slot == configManager.getMenus().getInt("menus.sell.buttons.info.slot") ||
                        slot == configManager.getMenus().getInt("menus.sell.buttons.sell.slot") ||
                        slot == configManager.getMenus().getInt("menus.sell.buttons.back.slot")) {
                    event.setCancelled(true);
                    return;
                }
            }
        } else {
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
            final double[] totalEMC = {0};
            final List<ItemStack> returnItems = new ArrayList<>();
            final List<ItemStack> itemsToRemove = new ArrayList<>();
            final List<String> materialNames = new ArrayList<>();
            final List<Integer> amounts = new ArrayList<>();

            for (int i = 0; i < inventory.getSize(); i++) {
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

            if (totalEMC[0] > 0) {
                final double finalTotalEMC = totalEMC[0];
                final List<String> finalMaterialNames = materialNames;
                final List<Integer> finalAmounts = amounts;
                
                schedulerUtil.runAsync(() -> {
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
                        for (int i = 0; i < finalMaterialNames.size(); i++) {
                            String materialName = finalMaterialNames.get(i);
                            int amount = finalAmounts.get(i);
                            
                            if (!databaseManager.isUnlocked(player.getUniqueId(), materialName)) {
                                int requiredSell = configManager.getItems().getInt("items." + materialName + ".required_sell");
                                if (requiredSell > 0) {
                                    int currentProgress = databaseManager.getSellProgress(player.getUniqueId(), materialName);
                                    int addAmount = Math.min(amount, requiredSell - currentProgress);
                                    if (addAmount > 0) {
                                        databaseManager.addSellProgress(player.getUniqueId(), materialName, addAmount);
                                    }
                                }
                                checkUnlock(player, materialName);
                            }
                        }

                        databaseManager.addEMCBalance(player.getUniqueId(), netEMC);
                        
                        schedulerUtil.runTask(() -> {
                            String message = configManager.getLang().getString("prefix") + 
                                    configManager.getLang().getString("menu.sell.success").replace("{amount}", String.format("%.2f", netEMC));
                            player.sendMessage(ColorUtil.translateColorCodes( message));
                        });
                    } catch (SQLException | ClassNotFoundException e) {
                        schedulerUtil.runTask(() -> {
                            player.sendMessage(ColorUtil.translateColorCodes( configManager.getLang().getString("prefix") + configManager.getLang().getString("menu.sell.failed")));
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
                plugin.getLogger().info("[DEBUG] purchase - player: " + player.getName() + ", material: " + materialName + ", req: " + amount + ", canAdd: " + canAdd + ", empty: " + emptySlots + ", partial: " + existingPartialSlotSpace);
            }

            if (canAdd < amount) {
                player.sendMessage(ColorUtil.translateColorCodes( configManager.getLang().getString("prefix") + configManager.getLang().getString("menu.exchange.inventory-full")));
                return;
            }

            databaseManager.addEMCBalance(player.getUniqueId(), -totalEMC);
            clearPlayerCache(player.getUniqueId());

            ItemStack itemToAdd = new ItemStack(material, amount);
            player.getInventory().addItem(itemToAdd);

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
        
        if ("sell".equals(menuType) && inventory != null) {
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
        String cacheKey = material.name() + ":" + displayName + ":" + lore.toString();
        
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
        
        menuItemsCache.put(cacheKey, item);
        return item;
    }
    
    private double getCachedBalance(UUID playerUUID) {
        if (playerBalanceCache.containsKey(playerUUID)) {
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
    
    public void clearPlayerCache(UUID playerUUID) {
        playerBalanceCache.remove(playerUUID);
        playerUnlockedCache.remove(playerUUID);
        playerMineProgressCache.remove(playerUUID);
        playerSellProgressCache.remove(playerUUID);
        playerCacheTimestamps.remove(playerUUID);
    }
    
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
            clearPlayerCache(player.getUniqueId());
            String itemDisplayName = configManager.getItems().getString("items." + materialName + ".name", materialName);
            final String message = configManager.getLang().getString("prefix") + 
                    configManager.getLang().getString("mine.unlocked").replace("{item}", itemDisplayName);
            schedulerUtil.runTask(() -> {
                player.sendMessage(ColorUtil.translateColorCodes( message));
            });
        }
    }
}
