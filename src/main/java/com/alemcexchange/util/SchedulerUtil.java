package com.alemcexchange.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class SchedulerUtil {

    private final JavaPlugin plugin;

    public SchedulerUtil(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void runAsync(Runnable task) {
        try {
            Class<?> regionizedServerClass = Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            Object server = Bukkit.getServer();
            if (regionizedServerClass.isInstance(server)) {
                Class<?> schedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.Scheduler");
                Object scheduler = regionizedServerClass.getMethod("getGlobalRegionScheduler").invoke(server);
                schedulerClass.getMethod("runAsync", JavaPlugin.class, Runnable.class).invoke(scheduler, plugin, task);
            } else {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            }
        } catch (Exception e) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    public void runSync(Runnable task) {
        try {
            Class<?> regionizedServerClass = Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            Object server = Bukkit.getServer();
            if (regionizedServerClass.isInstance(server)) {
                Class<?> schedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.Scheduler");
                Object scheduler = regionizedServerClass.getMethod("getGlobalRegionScheduler").invoke(server);
                schedulerClass.getMethod("run", JavaPlugin.class, Runnable.class, Object.class).invoke(scheduler, plugin, task, null);
            } else {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        } catch (Exception e) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public void runSync(Player player, Runnable task) {
        try {
            Class<?> regionizedServerClass = Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            Object server = Bukkit.getServer();
            if (regionizedServerClass.isInstance(server)) {
                Class<?> schedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.Scheduler");
                Object scheduler = regionizedServerClass.getMethod("getGlobalRegionScheduler").invoke(server);
                schedulerClass.getMethod("run", JavaPlugin.class, Runnable.class, Object.class).invoke(scheduler, plugin, task, null);
            } else {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        } catch (Exception e) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
