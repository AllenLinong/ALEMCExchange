package com.alemcexchange.listener;

import com.alemcexchange.config.ConfigManager;
import com.alemcexchange.database.DatabaseManager;
import com.alemcexchange.util.ColorUtil;
import com.alemcexchange.util.SchedulerUtil;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerPickupItemListener implements Listener {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final DatabaseManager databaseManager;
    private final SchedulerUtil schedulerUtil;
    private com.alemcexchange.util.MenuManager menuManager;
    private final ConcurrentHashMap<String, Boolean> autoSellCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Player, ConcurrentHashMap<Material, Integer>> pickupCounters = new ConcurrentHashMap<>();

    public PlayerPickupItemListener(JavaPlugin plugin, ConfigManager configManager, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.databaseManager = databaseManager;
        this.schedulerUtil = new SchedulerUtil(plugin);
    }

    public void setMenuManager(com.alemcexchange.util.MenuManager menuManager) {
        this.menuManager = menuManager;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (event.isCancelled()) {
            return;
        }

        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        Item itemEntity = event.getItem();
        Material material = itemEntity.getItemStack().getType();
        String materialName = material.name();
        int amount = event.getItem().getItemStack().getAmount();

        if (!configManager.isAutoSellEnabled()) {
            return;
        }

        if (!player.hasPermission("alemcexchange.autosell")) {
            return;
        }

        if (!configManager.getItems().contains("items." + materialName)) {
            return;
        }

        schedulerUtil.runTaskLater(() -> {
            if (!player.isOnline()) {
                return;
            }

            ItemStack itemInInventory = new ItemStack(material, amount);
            if (!player.getInventory().containsAtLeast(itemInInventory, amount)) {
                return;
            }

            schedulerUtil.runAsync(() -> {
                try {
                    boolean isAutoSellEnabledForPlayer = isAutoSellEnabled(player);
                    if (!isAutoSellEnabledForPlayer) {
                        return;
                    }

                    if (databaseManager.isUnlocked(player.getUniqueId(), materialName)) {
                        int batchThreshold = configManager.getConfig().getInt("autosell.batch_threshold", 64);

                        ConcurrentHashMap<Material, Integer> playerCounters = pickupCounters.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
                        int currentCount = playerCounters.getOrDefault(material, 0);
                        int newCount = currentCount + amount;
                        playerCounters.put(material, newCount);

                        if (newCount >= batchThreshold) {
                            processAutoSell(player, material, newCount);
                            playerCounters.remove(material);
                        }
                    }
                } catch (SQLException | ClassNotFoundException e) {
                    plugin.getLogger().severe("Error handling auto sell: " + e.getMessage());
                }
            });
        }, 2L);
    }

    private void processAutoSell(Player player, Material material, int amount) {
        String materialName = material.name();

        schedulerUtil.runTask(() -> {
            int removed = removeItemsFromInventory(player, material, amount);

            if (removed == 0) {
                plugin.getLogger().warning("Could not remove any items for auto sell");
                return;
            }

            schedulerUtil.runAsync(() -> {
                try {
                    double emcPerItem = configManager.getItems().getDouble("items." + materialName + ".emc");

                    if (emcPerItem <= 0) {
                        plugin.getLogger().warning("Invalid EMC price for " + materialName + ": " + emcPerItem);
                        return;
                    }

                    double totalEMC = emcPerItem * removed;

                    double taxRate = configManager.getConfig().getDouble("sell_tax", 0.05);
                    if (player.hasPermission("alemcexchange.notax")) {
                        taxRate = 0.0;
                    } else if (player.hasPermission("alemcexchange.premium")) {
                        taxRate = configManager.getConfig().getDouble("tax_rates.alemcexchange.premium", 0.01);
                    } else if (player.hasPermission("alemcexchange.vip")) {
                        taxRate = configManager.getConfig().getDouble("tax_rates.alemcexchange.vip", 0.03);
                    }

                    double tax = totalEMC * taxRate;
                    double netEMC = totalEMC - tax;

                    databaseManager.addEMCBalance(player.getUniqueId(), netEMC);
                    databaseManager.addSellProgress(player.getUniqueId(), materialName, removed);
                    if (menuManager != null) {
                        menuManager.clearPlayerCache(player.getUniqueId());
                    }
                    checkUnlock(player, materialName);

                    autoSellCache.remove(player.getUniqueId().toString());

                    if (configManager.isAutoSellMessageEnabled()) {
                        String message = configManager.getLang().getString("prefix") +
                                configManager.getLang().getString("autosell.sold").replace("{amount}", String.format("%.2f", netEMC));
                        schedulerUtil.runTask(() -> {
                            player.sendMessage(ColorUtil.translateColorCodes(message));
                        });
                    }
                } catch (SQLException | ClassNotFoundException e) {
                    plugin.getLogger().severe("Error processing auto sell: " + e.getMessage());
                }
            });
        });
    }

    private int removeItemsFromInventory(Player player, Material material, int amount) {
        int removed = 0;
        ItemStack[] inventory = player.getInventory().getContents();

        for (int i = 0; i < 36 && removed < amount; i++) {
            ItemStack item = inventory[i];
            if (item != null && item.getType() == material) {
                int itemAmount = item.getAmount();
                int toRemove = Math.min(itemAmount, amount - removed);

                if (itemAmount == toRemove) {
                    player.getInventory().setItem(i, null);
                } else {
                    item.setAmount(itemAmount - toRemove);
                    player.getInventory().setItem(i, item);
                }

                removed += toRemove;
            }
        }

        return removed;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        ConcurrentHashMap<Material, Integer> playerCounters = pickupCounters.get(player);
        if (playerCounters != null && !playerCounters.isEmpty()) {
            double taxRate = configManager.getConfig().getDouble("sell_tax", 0.05);
            if (player.hasPermission("alemcexchange.notax")) {
                taxRate = 0.0;
            } else if (player.hasPermission("alemcexchange.premium")) {
                taxRate = configManager.getConfig().getDouble("tax_rates.alemcexchange.premium", 0.01);
            } else if (player.hasPermission("alemcexchange.vip")) {
                taxRate = configManager.getConfig().getDouble("tax_rates.alemcexchange.vip", 0.03);
            }
            final double finalTaxRate = taxRate;

            for (java.util.Map.Entry<Material, Integer> entry : playerCounters.entrySet()) {
                Material material = entry.getKey();
                int amount = entry.getValue();

                int removed = removeItemsFromInventory(player, material, amount);

                if (removed > 0) {
                    final int finalRemoved = removed;
                    schedulerUtil.runAsync(() -> {
                        try {
                            double emcPerItem = configManager.getItems().getDouble("items." + material.name() + ".emc");
                            double totalEMC = emcPerItem * finalRemoved;

                            double tax = totalEMC * finalTaxRate;
                            double netEMC = totalEMC - tax;

                            databaseManager.addEMCBalance(player.getUniqueId(), netEMC);
                            databaseManager.addSellProgress(player.getUniqueId(), material.name(), finalRemoved);

                            plugin.getLogger().info("Auto-sold " + finalRemoved + " " + material.name() + " for player " + player.getName() + " on quit: " + netEMC + " EMC (tax rate: " + (finalTaxRate * 100) + "%)");
                        } catch (SQLException | ClassNotFoundException e) {
                            plugin.getLogger().severe("Error processing auto sell on quit: " + e.getMessage());
                        }
                    });
                }
            }
        }

        pickupCounters.remove(player);
    }

    private boolean isAutoSellEnabled(Player player) {
        String playerUUID = player.getUniqueId().toString();
        return autoSellCache.computeIfAbsent(playerUUID, uuid -> {
            try {
                return databaseManager.isAutoSellEnabled(player.getUniqueId());
            } catch (SQLException | ClassNotFoundException e) {
                plugin.getLogger().severe("Error checking auto sell status: " + e.getMessage());
                return false;
            }
        });
    }

    public void refreshAutoSellCache() {
        autoSellCache.clear();
    }

    private void checkUnlock(Player player, String materialName) throws SQLException, ClassNotFoundException {
        int requiredMine = configManager.getItems().getInt("items." + materialName + ".required_mine");
        int requiredSell = configManager.getItems().getInt("items." + materialName + ".required_sell");

        if (menuManager != null) {
            menuManager.clearPlayerCache(player.getUniqueId());
        }

        int mineProgress = databaseManager.getMineProgress(player.getUniqueId(), materialName);
        int sellProgress = databaseManager.getSellProgress(player.getUniqueId(), materialName);

        boolean mineCondition = requiredMine == 0 || mineProgress >= requiredMine;
        boolean sellCondition = requiredSell == 0 || sellProgress >= requiredSell;

        if (mineCondition && sellCondition && !databaseManager.isUnlocked(player.getUniqueId(), materialName)) {
            databaseManager.unlockItem(player.getUniqueId(), materialName);
            if (menuManager != null) {
                menuManager.clearPlayerCache(player.getUniqueId());
            }
            String itemDisplayName = configManager.getItems().getString("items." + materialName + ".name", materialName);
            String message = configManager.getLang().getString("prefix") +
                    configManager.getLang().getString("mine.unlocked").replace("{item}", itemDisplayName);
            schedulerUtil.runTask(() -> {
                player.sendMessage(ColorUtil.translateColorCodes(message));
            });
        }
    }
}
