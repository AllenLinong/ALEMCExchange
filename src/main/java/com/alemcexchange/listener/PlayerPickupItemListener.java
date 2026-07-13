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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerPickupItemListener implements Listener {

    private static final double DAILY_LIMIT_EPSILON = 0.0000001;

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
        int amount = itemEntity.getItemStack().getAmount();

        if (!configManager.isAutoSellEnabled()) {
            return;
        }

        if (!player.hasPermission("alemcexchange.autosell")) {
            return;
        }

        if (!configManager.hasItem(materialName)) {
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
                    if (!isAutoSellEnabled(player)) {
                        return;
                    }

                    int batchThreshold = configManager.getConfig().getInt("autosell.batch_threshold", 64);
                    ConcurrentHashMap<Material, Integer> playerCounters = pickupCounters.computeIfAbsent(player, k -> new ConcurrentHashMap<>());
                    int newCount = playerCounters.getOrDefault(material, 0) + amount;
                    playerCounters.put(material, newCount);

                    if (newCount >= batchThreshold) {
                        if (databaseManager.isUnlocked(player.getUniqueId(), materialName)) {
                            processAutoSell(player, material, newCount);
                        }
                        playerCounters.remove(material);
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
            if (!player.isOnline()) {
                return;
            }

            int removed = removeItemsFromInventory(player, material, amount);
            if (removed == 0) {
                plugin.getLogger().warning("Could not remove any items for auto sell");
                return;
            }

            final int finalRemoved = removed;
            schedulerUtil.runAsync(() -> processAutoSellDatabase(player, material, materialName, finalRemoved));
        });
    }

    private void processAutoSellDatabase(Player player, Material material, String materialName, int removed) {
        double dailyConsumed = 0;
        String effectiveDate = null;
        boolean balanceCredited = false;

        try {
            double emcPerItem = configManager.getItemEMC(materialName);
            if (emcPerItem <= 0) {
                plugin.getLogger().warning("Invalid EMC price for " + materialName + ": " + emcPerItem);
                returnItemsToPlayer(player, material, removed);
                return;
            }

            double requestedEMC = emcPerItem * removed;
            double allowedEMC = requestedEMC;
            int actualSold = removed;
            boolean limited = false;
            double dailyLimitForMessage = -1;
            double remainingAfterSell = Double.MAX_VALUE;

            if (configManager.isDailyLimitEnabled()) {
                double dailyLimit = configManager.getDailyLimitForPlayer(player);
                dailyLimitForMessage = dailyLimit;
                if (dailyLimit != -1) {
                    effectiveDate = databaseManager.getEffectiveDate();
                    allowedEMC = databaseManager.consumeDailyEMCLimit(player.getUniqueId(), effectiveDate, requestedEMC, dailyLimit);
                    dailyConsumed = allowedEMC;

                    if (allowedEMC <= DAILY_LIMIT_EPSILON) {
                        returnItemsToPlayer(player, material, removed);
                        disableAutoSellForDailyLimit(player);
                        return;
                    }

                    actualSold = Math.min(removed, (int) (allowedEMC / emcPerItem));
                    double actualEMC = actualSold * emcPerItem;
                    double unusedDaily = allowedEMC - actualEMC;
                    if (unusedDaily > DAILY_LIMIT_EPSILON) {
                        databaseManager.addDailyEMCEarned(player.getUniqueId(), effectiveDate, -unusedDaily);
                        dailyConsumed -= unusedDaily;
                    }

                    allowedEMC = actualEMC;
                    limited = actualSold < removed;
                    remainingAfterSell = Math.max(0, dailyLimit - databaseManager.getDailyEMCEarned(player.getUniqueId(), effectiveDate));
                }
            }

            if (actualSold <= 0 || allowedEMC <= DAILY_LIMIT_EPSILON) {
                refundDailyLimit(player, effectiveDate, dailyConsumed);
                returnItemsToPlayer(player, material, removed);
                disableAutoSellForDailyLimit(player);
                return;
            }

            double taxRate = configManager.getTaxRateForPlayer(player);
            double tax = allowedEMC * taxRate;
            double netEMC = allowedEMC - tax;

            databaseManager.addEMCBalance(player.getUniqueId(), netEMC);
            balanceCredited = true;
            if (menuManager != null) {
                menuManager.clearPlayerCache(player.getUniqueId());
            }

            try {
                databaseManager.addSellProgress(player.getUniqueId(), materialName, actualSold);
                checkUnlock(player, materialName);
            } catch (SQLException | ClassNotFoundException e) {
                plugin.getLogger().severe("Error updating auto sell progress: " + e.getMessage());
            }

            autoSellCache.remove(player.getUniqueId().toString());

            int unsoldAmount = removed - actualSold;
            boolean shouldDisableAfterSell = remainingAfterSell <= DAILY_LIMIT_EPSILON;
            sendAutoSellResult(player, material, unsoldAmount, netEMC, limited, dailyLimitForMessage, remainingAfterSell);

            if (shouldDisableAfterSell) {
                disableAutoSellForDailyLimit(player);
            }
        } catch (SQLException | ClassNotFoundException e) {
            plugin.getLogger().severe("Error processing auto sell: " + e.getMessage());
            if (!balanceCredited) {
                refundDailyLimit(player, effectiveDate, dailyConsumed);
                returnItemsToPlayer(player, material, removed);
            }
        }
    }

    private void sendAutoSellResult(Player player, Material material, int unsoldAmount, double netEMC, boolean limited, double dailyLimit, double remainingAfterSell) {
        schedulerUtil.runTask(() -> {
            returnItemsToPlayerSync(player, material, unsoldAmount);

            if (!configManager.isAutoSellMessageEnabled()) {
                return;
            }

            String message = configManager.getLang().getString("prefix") +
                    configManager.getLang().getString("autosell.sold").replace("{amount}", String.format("%.2f", netEMC));
            player.sendMessage(ColorUtil.translateColorCodes(message));

            if (limited && dailyLimit != -1) {
                String limitMsg = configManager.getLang().getString("prefix") +
                        configManager.getLang().getString("daily_limit.remaining")
                                .replace("{remaining}", String.format("%.2f", Math.max(0, remainingAfterSell)))
                                .replace("{limit}", String.format("%.2f", dailyLimit));
                player.sendMessage(ColorUtil.translateColorCodes(limitMsg));
            }
        });
    }

    private void returnItemsToPlayer(Player player, Material material, int amount) {
        if (amount <= 0) {
            return;
        }
        schedulerUtil.runTask(() -> returnItemsToPlayerSync(player, material, amount));
    }

    private void returnItemsToPlayerSync(Player player, Material material, int amount) {
        if (amount <= 0) {
            return;
        }
        ItemStack returnItem = new ItemStack(material, amount);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(returnItem);
        for (ItemStack leftoverItem : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftoverItem);
        }
    }

    private void refundDailyLimit(Player player, String effectiveDate, double amount) {
        if (effectiveDate == null || amount <= DAILY_LIMIT_EPSILON) {
            return;
        }
        try {
            databaseManager.addDailyEMCEarned(player.getUniqueId(), effectiveDate, -amount);
        } catch (SQLException | ClassNotFoundException e) {
            plugin.getLogger().severe("Error refunding daily EMC limit: " + e.getMessage());
        }
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
        pickupCounters.remove(player);
        autoSellCache.remove(player.getUniqueId().toString());
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

    private void disableAutoSellForDailyLimit(Player player) {
        try {
            databaseManager.setAutoSellEnabled(player.getUniqueId(), false);
            autoSellCache.put(player.getUniqueId().toString(), false);
            pickupCounters.remove(player);

            if (player.isOnline()) {
                schedulerUtil.runTask(() -> {
                    String message = configManager.getLang().getString("prefix") +
                            configManager.getLang().getString(
                                    "autosell.disabled_daily_limit",
                                    "&c今日EMC获取额度已用完，自动出售已关闭！");
                    player.sendMessage(ColorUtil.translateColorCodes(message));
                });
            }
        } catch (SQLException | ClassNotFoundException e) {
            plugin.getLogger().severe("Error disabling auto sell after reaching daily limit: " + e.getMessage());
        }
    }

    private void checkUnlock(Player player, String materialName) throws SQLException, ClassNotFoundException {
        int requiredMine = configManager.getRequiredMine(materialName);
        int requiredSell = configManager.getRequiredSell(materialName);

        int mineProgress = databaseManager.getMineProgress(player.getUniqueId(), materialName);
        int sellProgress = databaseManager.getSellProgress(player.getUniqueId(), materialName);

        boolean mineCondition = requiredMine == 0 || mineProgress >= requiredMine;
        boolean sellCondition = requiredSell == 0 || sellProgress >= requiredSell;

        if (mineCondition && sellCondition && !databaseManager.isUnlocked(player.getUniqueId(), materialName)) {
            databaseManager.unlockItem(player.getUniqueId(), materialName);
            if (menuManager != null) {
                menuManager.clearPlayerCache(player.getUniqueId());
            }

            String itemDisplayName = configManager.getItemName(materialName);
            final String message = configManager.getLang().getString("prefix") +
                    configManager.getLang().getString("mine.unlocked").replace("{item}", itemDisplayName);
            schedulerUtil.runTask(() -> player.sendMessage(ColorUtil.translateColorCodes(message)));
        }
    }
}
