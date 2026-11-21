package com.alemcexchange.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class SaleDelivery implements Listener {
    private final SchedulerUtil scheduler;
    private final Map<UUID, Queue<Delivery>> pending = new ConcurrentHashMap<>();

    public SaleDelivery(JavaPlugin plugin, SchedulerUtil scheduler) {
        this.scheduler = scheduler;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void deliver(UUID uuid, Supplier<List<ItemStack>> returnedItems, Consumer<Player> notify, Runnable finished) {
        pending.compute(uuid, (key, queue) -> {
            if (queue == null) queue = new ArrayDeque<>();
            queue.add(new Delivery(returnedItems, notify, finished));
            return queue;
        });
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) scheduler.runAtEntity(online, () -> flush(online), null);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (pending.containsKey(player.getUniqueId())) scheduler.runAtEntity(player, () -> flush(player), null);
    }

    private void flush(Player player) {
        if (!player.isOnline()) return;
        Queue<Delivery> deliveries = pending.remove(player.getUniqueId());
        if (deliveries == null) return;
        for (Delivery delivery : deliveries) {
            try {
                List<ItemStack> items = delivery.returnedItems().get();
                if (!items.isEmpty()) {
                    var leftover = player.getInventory().addItem(items.toArray(ItemStack[]::new));
                    for (ItemStack item : leftover.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), item);
                    }
                }
                delivery.notification().accept(player);
            } finally {
                delivery.finished().run();
            }
        }
    }

    private record Delivery(Supplier<List<ItemStack>> returnedItems, Consumer<Player> notification, Runnable finished) {}
}
