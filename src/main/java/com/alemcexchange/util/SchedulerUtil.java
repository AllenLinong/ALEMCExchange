package com.alemcexchange.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Consumer;
import java.util.logging.Level;

public class SchedulerUtil {

    private final JavaPlugin plugin;
    private final boolean isFolia;
    private Object globalRegionScheduler;
    private Object asyncScheduler;
    private Object regionScheduler;

    public SchedulerUtil(JavaPlugin plugin) {
        this.plugin = plugin;
        boolean folia = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = initFoliaSchedulers();
        } catch (ClassNotFoundException e) {
            folia = false;
        }
        this.isFolia = folia;
        if (folia) {
            plugin.getLogger().info("Folia detected, using regionized schedulers");
        }
    }

    public boolean isFolia() {
        return isFolia;
    }

    private boolean initFoliaSchedulers() {
        try {
            Object server = Bukkit.getServer();
            globalRegionScheduler = server.getClass().getMethod("getGlobalRegionScheduler").invoke(server);
            asyncScheduler = server.getClass().getMethod("getAsyncScheduler").invoke(server);
            regionScheduler = server.getClass().getMethod("getRegionScheduler").invoke(server);
            return globalRegionScheduler != null && asyncScheduler != null;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize Folia schedulers", e);
            return false;
        }
    }

    public void runAsync(Runnable task) {
        if (isFolia) {
            try {
                asyncScheduler.getClass()
                        .getMethod("runNow", Plugin.class, Consumer.class)
                        .invoke(asyncScheduler, plugin, (Consumer<Object>) s -> task.run());
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Folia runAsync failed", e);
            }
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    public void runTask(Runnable task) {
        if (isFolia) {
            try {
                globalRegionScheduler.getClass()
                        .getMethod("run", Plugin.class, Consumer.class)
                        .invoke(globalRegionScheduler, plugin, (Consumer<Object>) s -> task.run());
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Folia runTask failed", e);
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public void runTaskLater(Runnable task, long delay) {
        if (isFolia) {
            try {
                globalRegionScheduler.getClass()
                        .getMethod("runDelayed", Plugin.class, Consumer.class, long.class)
                        .invoke(globalRegionScheduler, plugin, (Consumer<Object>) s -> task.run(), delay);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Folia runTaskLater failed", e);
            }
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delay);
        }
    }

    public void runTimerAsync(Runnable task, long delay, long period) {
        if (isFolia) {
            try {
                asyncScheduler.getClass()
                        .getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, java.util.concurrent.TimeUnit.class)
                        .invoke(asyncScheduler, plugin, (Consumer<Object>) s -> task.run(),
                                delay * 50L, period * 50L, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Folia runTimerAsync failed", e);
            }
        } else {
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period);
        }
    }

    public void runAtLocation(Location location, Runnable task) {
        if (isFolia) {
            try {
                regionScheduler.getClass()
                        .getMethod("run", Plugin.class, Location.class, Consumer.class)
                        .invoke(regionScheduler, plugin, location, (Consumer<Object>) s -> task.run());
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Folia runAtLocation failed", e);
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public void runAtEntity(Entity entity, Runnable task, Runnable fallback) {
        if (isFolia) {
            try {
                Object entityScheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
                entityScheduler.getClass()
                        .getMethod("run", Plugin.class, Consumer.class, Runnable.class)
                        .invoke(entityScheduler, plugin, (Consumer<Object>) s -> task.run(), fallback);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Folia runAtEntity failed", e);
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public void cancelTasks() {
        if (isFolia) {
            try {
                globalRegionScheduler.getClass()
                        .getMethod("cancelTasks", Plugin.class)
                        .invoke(globalRegionScheduler, plugin);
                asyncScheduler.getClass()
                        .getMethod("cancelTasks", Plugin.class)
                        .invoke(asyncScheduler, plugin);
            } catch (Exception ignored) {
            }
        } else {
            Bukkit.getScheduler().cancelTasks(plugin);
        }
    }
}
