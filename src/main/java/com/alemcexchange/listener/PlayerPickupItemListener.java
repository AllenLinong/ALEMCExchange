package com.alemcexchange.listener;

import com.alemcexchange.config.ConfigManager;
import com.alemcexchange.database.DatabaseManager;
import com.alemcexchange.util.ColorUtil;
import com.alemcexchange.util.MenuManager;
import com.alemcexchange.util.SaleDelivery;
import com.alemcexchange.util.SchedulerUtil;
import org.bukkit.Material;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerPickupItemListener implements Listener {

    private static final double DAILY_LIMIT_EPSILON = 0.0000001;

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final DatabaseManager databaseManager;
    private final SchedulerUtil schedulerUtil;
    private MenuManager menuManager;
    private final SaleDelivery saleDelivery;
    private final Map<Player, ConcurrentHashMap<Material, PickupCount>> pickupCounters = new ConcurrentHashMap<>();
    private final Set<UUID> settling = ConcurrentHashMap.newKeySet();
    private static final long IDLE_FLUSH_MILLIS = 5000L;

    public PlayerPickupItemListener(JavaPlugin plugin, ConfigManager configManager, DatabaseManager databaseManager,
                                    SaleDelivery saleDelivery) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.databaseManager = databaseManager;
        this.saleDelivery = saleDelivery;
        this.schedulerUtil = new SchedulerUtil(plugin);
        schedulerUtil.runTimerAsync(this::flushPickupBatches, 10L, 10L);
    }

    public void setMenuManager(MenuManager menuManager) {
        this.menuManager = menuManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || !configManager.isAutoSellEnabled()
                || !player.hasPermission("alemcexchange.autosell")) return;
        ItemStack item = event.getItem().getItemStack();
        Material material = item.getType();
        if (!configManager.hasItem(material.name()) || !item.isSimilar(new ItemStack(material))) return;
        long now = System.currentTimeMillis();
        int amount = item.getAmount();
        pickupCounters.computeIfAbsent(player, key -> new ConcurrentHashMap<>())
                .compute(material, (key, previous) -> new PickupCount(
                        previous == null ? amount : (int) Math.min(Integer.MAX_VALUE, (long) previous.amount() + amount), now));
    }

    private void flushPickupBatches() {
        long now = System.currentTimeMillis();
        int threshold = Math.max(1, configManager.getConfig().getInt("autosell.batch_threshold", 64));
        pickupCounters.forEach((player, counts) -> {
            UUID uuid = player.getUniqueId();
            Map<Material, PickupCount> ready = new LinkedHashMap<>();
            counts.forEach((material, count) -> {
                if (count.amount() >= threshold || now - count.lastPickup() >= IDLE_FLUSH_MILLIS) ready.put(material, count);
            });
            if (ready.isEmpty() || !settling.add(uuid)) return;
            boolean handedOff = false;
            try {
                if (!configManager.isAutoSellEnabled() || !databaseManager.isAutoSellEnabled(uuid)) {
                    ready.forEach(counts::remove);
                    return;
                }
                Map<String, Boolean> unlocked = databaseManager.getUnlockedStatuses(uuid, ready.keySet().stream().map(Material::name).toList());
                Map<Material, Integer> batch = new LinkedHashMap<>();
                ready.forEach((material, count) -> {
                    if (unlocked.getOrDefault(material.name(), false)) {
                        counts.computeIfPresent(material, (key, latest) -> {
                            batch.put(key, latest.amount());
                            return null;
                        });
                    } else if (now - count.lastPickup() >= IDLE_FLUSH_MILLIS) {
                        counts.remove(material, count);
                    }
                });
                if (batch.isEmpty()) return;
                handedOff = schedulerUtil.runAtEntity(player, () -> removeBatch(player, uuid, batch), () -> settling.remove(uuid));
            } catch (SQLException | ClassNotFoundException | RuntimeException exception) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not prepare auto sell batch", exception);
            } finally {
                if (!handedOff) settling.remove(uuid);
            }
        });
    }

    private void removeBatch(Player player, UUID uuid, Map<Material, Integer> batch) {
        Map<Material, Integer> removed = new LinkedHashMap<>();
        boolean handedOff = false;
        try {
            if (!player.isOnline() || !pickupCounters.containsKey(player) || !configManager.isAutoSellEnabled()
                    || !player.hasPermission("alemcexchange.autosell")) return;
            double taxRate = configManager.getTaxRateForPlayer(player);
            double dailyLimit = configManager.isDailyLimitEnabled() ? configManager.getDailyLimitForPlayer(player) : -1;
            ItemStack[] contents = player.getInventory().getStorageContents();
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack item = contents[slot];
                if (item == null) continue;
                Material material = item.getType();
                int remaining = batch.getOrDefault(material, 0);
                if (remaining <= 0 || !item.isSimilar(new ItemStack(material))) continue;
                int take = Math.min(remaining, item.getAmount());
                int left = item.getAmount() - take;
                if (left == 0) player.getInventory().setItem(slot, null);
                else {
                    item.setAmount(left);
                    player.getInventory().setItem(slot, item);
                }
                removed.merge(material, take, Integer::sum);
                batch.put(material, remaining - take);
            }
            if (removed.isEmpty()) return;
            handedOff = schedulerUtil.runAsync(() -> settleBatch(uuid, removed, taxRate, dailyLimit));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not schedule auto sell batch", exception);
        } finally {
            if (!handedOff) {
                settling.remove(uuid);
                if (!removed.isEmpty()) saleDelivery.deliver(uuid, () -> toStacks(removed), current -> {}, () -> {});
            }
        }
    }

    private void settleBatch(UUID uuid, Map<Material, Integer> removed, double taxRate, double dailyLimit) {
        Map<Material, Integer> sold = new LinkedHashMap<>();
        double consumed = 0;
        double net = 0;
        double remainingAfterSell = -1;
        boolean credited = false;
        boolean limited = false;
        boolean disabled = false;
        String date = null;
        try {
            if (!databaseManager.isAutoSellEnabled(uuid)) return;
            Map<Material, Double> prices = new LinkedHashMap<>();
            double requested = 0;
            for (var entry : removed.entrySet()) {
                double price = configManager.getItemEMC(entry.getKey().name());
                double value = price * entry.getValue();
                if (value <= 0 || !Double.isFinite(requested + value)) continue;
                prices.put(entry.getKey(), price);
                requested += value;
            }
            if (requested <= 0) return;
            double available = requested;
            if (dailyLimit >= 0) {
                date = databaseManager.getEffectiveDate();
                consumed = databaseManager.consumeDailyEMCLimit(uuid, date, requested, dailyLimit);
                available = consumed;
            }
            double gross = 0;
            for (var entry : prices.entrySet()) {
                Material material = entry.getKey();
                double price = entry.getValue();
                int amount = removed.get(material);
                int count = available >= requested || amount * price <= available - gross
                        ? amount : Math.max(0, (int) ((available - gross) / price));
                if (count <= 0) continue;
                sold.put(material, count);
                gross += count * price;
            }
            if (date != null && consumed > gross) {
                databaseManager.addDailyEMCEarned(uuid, date, gross - consumed);
                consumed = gross;
            }
            limited = gross < requested;
            if (gross > 0) {
                net = gross - gross * taxRate;
                databaseManager.addEMCBalance(uuid, net);
                credited = true;
                if (menuManager != null) menuManager.clearPlayerCache(uuid);
            }
            if (date != null) {
                remainingAfterSell = Math.max(0, dailyLimit - databaseManager.getDailyEMCEarned(uuid, date));
                if (gross <= 0 || remainingAfterSell <= DAILY_LIMIT_EPSILON) {
                    databaseManager.setAutoSellEnabled(uuid, false);
                    disabled = true;
                }
            }
        } catch (SQLException | ClassNotFoundException | RuntimeException exception) {
            if (!credited && date != null && consumed > 0) {
                try {
                    databaseManager.addDailyEMCEarned(uuid, date, -consumed);
                } catch (SQLException | ClassNotFoundException refundException) {
                    exception.addSuppressed(refundException);
                }
            }
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Could not settle auto sell batch", exception);
        } finally {
            if (credited) sold.forEach((material, count) -> removed.computeIfPresent(material, (key, amount) -> amount - count));
            sendResult(uuid, removed, credited, net, limited, dailyLimit, remainingAfterSell, disabled);
        }
    }

    private void sendResult(UUID uuid, Map<Material, Integer> returned, boolean credited, double net, boolean limited,
                            double dailyLimit, double remaining, boolean disabled) {
        saleDelivery.deliver(uuid, () -> toStacks(returned), player -> {
            String prefix = configManager.getLang().getString("prefix", "");
            if (credited && configManager.isAutoSellMessageEnabled()) {
                String message = configManager.getLang().getString("autosell.sold", "").replace("{amount}", String.format("%.2f", net));
                player.sendMessage(ColorUtil.translateColorCodes(prefix + message));
                if (limited && remaining >= 0) {
                    String limit = configManager.getLang().getString("daily_limit.remaining", "")
                            .replace("{remaining}", String.format("%.2f", remaining))
                            .replace("{limit}", String.format("%.2f", dailyLimit));
                    player.sendMessage(ColorUtil.translateColorCodes(prefix + limit));
                }
            }
            if (disabled) {
                String message = configManager.getLang().getString("autosell.disabled_daily_limit", "");
                player.sendMessage(ColorUtil.translateColorCodes(prefix + message));
            }
        }, () -> settling.remove(uuid));
    }

    private List<ItemStack> toStacks(Map<Material, Integer> amounts) {
        List<ItemStack> items = new ArrayList<>();
        amounts.forEach((material, amount) -> {
            while (amount > 0) {
                int stackSize = Math.min(amount, material.getMaxStackSize());
                items.add(new ItemStack(material, stackSize));
                amount -= stackSize;
            }
        });
        return items;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        clearPickupCounts(event.getPlayer());
    }

    public void clearPickupCounts(Player player) {
        pickupCounters.remove(player);
    }

    private record PickupCount(int amount, long lastPickup) {}
}
