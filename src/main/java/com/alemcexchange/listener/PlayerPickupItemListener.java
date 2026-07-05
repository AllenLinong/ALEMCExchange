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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

            final int finalRemoved = removed;
            schedulerUtil.runAsync(() -> {
                try {
                    double emcPerItem = configManager.getItems().getDouble("items." + materialName + ".emc");

                    if (emcPerItem <= 0) {
                        plugin.getLogger().warning("Invalid EMC price for " + materialName + ": " + emcPerItem);
                        return;
                    }

                    double totalEMC = emcPerItem * finalRemoved;

                    // 检查每日限额
                    double allowedEMC = totalEMC;
                    int actualSold = finalRemoved;
                    boolean limited = false;

                    if (configManager.isDailyLimitEnabled()) {
                        double dailyLimit = configManager.getDailyLimitForPlayer(player);
                        if (dailyLimit != -1) {
                            String effectiveDate = databaseManager.getEffectiveDate();
                            double alreadyEarned = databaseManager.getDailyEMCEarned(player.getUniqueId(), effectiveDate);
                            double remaining = dailyLimit - alreadyEarned;

                            if (remaining <= 0) {
                                // 今日限额已用完，退还物品
                                final ItemStack returnItem = new ItemStack(material, finalRemoved);
                                schedulerUtil.runTask(() -> {
                                    Map<Integer, ItemStack> leftover = player.getInventory().addItem(returnItem);
                                    for (ItemStack leftoverItem : leftover.values()) {
                                        player.getWorld().dropItemNaturally(player.getLocation(), leftoverItem);
                                    }
                                    String message = configManager.getLang().getString("prefix") +
                                            configManager.getLang().getString("daily_limit.reached");
                                    player.sendMessage(ColorUtil.translateColorCodes(message));
                                });
                                return;
                            } else if (remaining < totalEMC) {
                                // 部分超出限额
                                actualSold = (int) (remaining / emcPerItem);
                                if (actualSold <= 0) {
                                    final ItemStack returnItem = new ItemStack(material, finalRemoved);
                                    schedulerUtil.runTask(() -> {
                                        Map<Integer, ItemStack> leftover = player.getInventory().addItem(returnItem);
                                        for (ItemStack leftoverItem : leftover.values()) {
                                            player.getWorld().dropItemNaturally(player.getLocation(), leftoverItem);
                                        }
                                        String message = configManager.getLang().getString("prefix") +
                                                configManager.getLang().getString("daily_limit.reached");
                                        player.sendMessage(ColorUtil.translateColorCodes(message));
                                    });
                                    return;
                                }
                                allowedEMC = emcPerItem * actualSold;
                                // 退还多余物品
                                final int unsoldAmount = finalRemoved - actualSold;
                                final ItemStack returnItem = new ItemStack(material, unsoldAmount);
                                schedulerUtil.runTask(() -> {
                                    Map<Integer, ItemStack> leftover = player.getInventory().addItem(returnItem);
                                    for (ItemStack leftoverItem : leftover.values()) {
                                        player.getWorld().dropItemNaturally(player.getLocation(), leftoverItem);
                                    }
                                });
                                limited = true;
                            }
                        }
                    }

                    double taxRate = configManager.getTaxRateForPlayer(player);

                    double tax = allowedEMC * taxRate;
                    double netEMC = allowedEMC - tax;

                    // 记录每日获取量（使用税前EMC，避免税率导致小数零头）
                    if (configManager.isDailyLimitEnabled()) {
                        String effectiveDate = databaseManager.getEffectiveDate();
                        databaseManager.addDailyEMCEarned(player.getUniqueId(), effectiveDate, allowedEMC);
                    }

                    databaseManager.addEMCBalance(player.getUniqueId(), netEMC);
                    databaseManager.addSellProgress(player.getUniqueId(), materialName, actualSold);
                    if (menuManager != null) {
                        menuManager.clearPlayerCache(player.getUniqueId());
                    }
                    checkUnlock(player, materialName);

                    autoSellCache.remove(player.getUniqueId().toString());

                    final boolean wasLimited = limited;
                    if (configManager.isAutoSellMessageEnabled()) {
                        String message = configManager.getLang().getString("prefix") +
                                configManager.getLang().getString("autosell.sold").replace("{amount}", String.format("%.2f", netEMC));
                        schedulerUtil.runTask(() -> {
                            player.sendMessage(ColorUtil.translateColorCodes(message));
                            if (wasLimited) {
                                try {
                                    double dailyLimitVal = configManager.getDailyLimitForPlayer(player);
                                    if (dailyLimitVal != -1) {
                                        String effectiveDate = databaseManager.getEffectiveDate();
                                        double earnedToday = databaseManager.getDailyEMCEarned(player.getUniqueId(), effectiveDate);
                                        double remainingToday = dailyLimitVal - earnedToday;
                                        String limitMsg = configManager.getLang().getString("prefix") +
                                                configManager.getLang().getString("daily_limit.remaining")
                                                    .replace("{remaining}", String.format("%.2f", Math.max(0, remainingToday)))
                                                    .replace("{limit}", String.format("%.2f", dailyLimitVal));
                                        player.sendMessage(ColorUtil.translateColorCodes(limitMsg));
                                    }
                                } catch (Exception ignored) {}
                            }
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

            // 先收集所有待处理的物品和数量
            List<java.util.Map.Entry<Material, Integer>> entries = new ArrayList<>(playerCounters.entrySet());

            schedulerUtil.runAsync(() -> {
                try {
                    // 计算每日限额剩余
                    double remainingLimit = -1; // -1 表示不限制
                    if (configManager.isDailyLimitEnabled()) {
                        double dailyLimit = configManager.getDailyLimitForPlayer(player);
                        if (dailyLimit != -1) {
                            String effectiveDate = databaseManager.getEffectiveDate();
                            double alreadyEarned = databaseManager.getDailyEMCEarned(player.getUniqueId(), effectiveDate);
                            remainingLimit = Math.max(0, dailyLimit - alreadyEarned);
                        }
                    }

                    for (java.util.Map.Entry<Material, Integer> entry : entries) {
                        Material material = entry.getKey();
                        int amount = entry.getValue();
                        String materialName = material.name();

                        if (!configManager.getItems().contains("items." + materialName)) {
                            continue;
                        }

                        double emcPerItem = configManager.getItems().getDouble("items." + materialName + ".emc");
                        if (emcPerItem <= 0) continue;

                        // 根据限额计算实际可出售数量
                        int actualSold;
                        double actualEMC;

                        if (remainingLimit == -1) {
                            // 不限制
                            actualSold = amount;
                            actualEMC = emcPerItem * actualSold;
                        } else if (remainingLimit <= 0) {
                            // 限额用完，跳过
                            continue;
                        } else {
                            double totalForThis = emcPerItem * amount;
                            if (totalForThis <= remainingLimit) {
                                actualSold = amount;
                                actualEMC = totalForThis;
                                remainingLimit -= actualEMC;
                            } else {
                                actualSold = (int) (remainingLimit / emcPerItem);
                                if (actualSold <= 0) continue;
                                actualEMC = emcPerItem * actualSold;
                                remainingLimit -= actualEMC;
                            }
                        }

                        // 在主线程移除物品
                        final int finalActualSold = actualSold;
                        final int[] removed = {0};
                        schedulerUtil.runTask(() -> {
                            removed[0] = removeItemsFromInventory(player, material, finalActualSold);
                        });
                        // 等待主线程执行完成（简化处理：直接同步移除，因为玩家已退出）
                        // 实际上在玩家退出时同步移除更安全
                        int actuallyRemoved = removeItemsFromInventorySync(player, material, finalActualSold);

                        if (actuallyRemoved > 0) {
                            double tax = actualEMC * finalTaxRate;
                            double netEMC = actualEMC - tax;

                            // 记录每日获取量（使用税前EMC，避免税率导致小数零头）
                            if (configManager.isDailyLimitEnabled()) {
                                String effectiveDate = databaseManager.getEffectiveDate();
                                databaseManager.addDailyEMCEarned(player.getUniqueId(), effectiveDate, actualEMC);
                            }

                            databaseManager.addEMCBalance(player.getUniqueId(), netEMC);
                            databaseManager.addSellProgress(player.getUniqueId(), materialName, actuallyRemoved);

                            plugin.getLogger().info("Auto-sold " + actuallyRemoved + " " + materialName + " for player " + player.getName() + " on quit: " + netEMC + " EMC (tax rate: " + (finalTaxRate * 100) + "%)");
                        }

                        // 退还未出售的物品
                        if (actuallyRemoved < amount) {
                            final int unsold = amount - actuallyRemoved;
                            if (unsold > 0) {
                                ItemStack returnItem = new ItemStack(material, unsold);
                                Map<Integer, ItemStack> leftover = player.getInventory().addItem(returnItem);
                                for (ItemStack is : leftover.values()) {
                                    player.getWorld().dropItemNaturally(player.getLocation(), is);
                                }
                            }
                        }
                    }
                } catch (SQLException | ClassNotFoundException e) {
                    plugin.getLogger().severe("Error processing auto sell on quit: " + e.getMessage());
                }
            });
        }

        pickupCounters.remove(player);
    }

    /**
     * 同步移除物品（用于玩家退出时）
     */
    private int removeItemsFromInventorySync(Player player, Material material, int amount) {
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
