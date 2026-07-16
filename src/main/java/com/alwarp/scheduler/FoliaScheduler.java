package com.alwarp.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Paper/Folia统一调度器抽象层。
 * 通过静态IS_FOLIA检测在Paper和Folia的调度API之间自动分发。
 * 对外统一使用tick单位；异步路径内部做tick→ms换算。
 */
public final class FoliaScheduler {

    private static final boolean IS_FOLIA;

    static {
        boolean folia = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException ignored) {
        }
        IS_FOLIA = folia;
    }

    private final Plugin plugin;
    private int nextTaskId = 1;
    private final Map<Integer, Object> taskHandles = new ConcurrentHashMap<>();

    public FoliaScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isFolia() {
        return IS_FOLIA;
    }

    private int registerTask(Object handle) {
        int id = nextTaskId++;
        taskHandles.put(id, handle);
        return id;
    }

    // ─── 全局同步调度 ───

    public void runTask(Runnable task) {
        if (IS_FOLIA) {
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public void runTaskLater(Runnable task, long delayTicks) {
        if (IS_FOLIA) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), Math.max(1L, delayTicks));
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    public void runTaskTimer(Runnable task, long delayTicks, long periodTicks) {
        if (IS_FOLIA) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> task.run(),
                    Math.max(1L, delayTicks), Math.max(1L, periodTicks));
        } else {
            Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        }
    }

    // ─── 异步调度（数据库IO等） ───

    public void runTaskAsync(Runnable task) {
        if (IS_FOLIA) {
            Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    public void runTaskLaterAsync(Runnable task, long delayTicks) {
        long delayMs = Math.max(0L, delayTicks) * 50L;
        if (IS_FOLIA) {
            Bukkit.getAsyncScheduler().runDelayed(plugin, t -> task.run(), delayMs, TimeUnit.MILLISECONDS);
        } else {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks);
        }
    }

    public void runTaskTimerAsync(Runnable task, long delayTicks, long periodTicks) {
        long delayMs = Math.max(0L, delayTicks) * 50L;
        long periodMs = Math.max(1L, periodTicks) * 50L;
        if (IS_FOLIA) {
            Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> task.run(), delayMs, periodMs, TimeUnit.MILLISECONDS);
        } else {
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks);
        }
    }

    // ─── 区域绑定调度 ───

    public void runAtLocation(Location location, Runnable task) {
        if (IS_FOLIA) {
            Bukkit.getRegionScheduler().run(plugin, location, t -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public void runAtEntity(Entity entity, Runnable task) {
        if (IS_FOLIA) {
            entity.getScheduler().run(plugin, t -> task.run(), null);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public void runAtEntityLater(Entity entity, Runnable task, long delayTicks) {
        if (IS_FOLIA) {
            entity.getScheduler().runDelayed(plugin, t -> task.run(), null, Math.max(1L, delayTicks));
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    public int runTaskTimerAtEntity(Entity entity, Runnable task, long delay, long period) {
        if (IS_FOLIA) {
            var st = entity.getScheduler().runAtFixedRate(plugin, t -> task.run(), null,
                    Math.max(1L, delay), Math.max(1L, period));
            return registerTask(st);
        } else {
            var bt = Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
            return registerTask(bt);
        }
    }

    // ─── 任务管理 ───

    public void cancelTask(int taskId) {
        Object handle = taskHandles.remove(taskId);
        if (handle == null) return;
        if (IS_FOLIA) {
            ((io.papermc.paper.threadedregions.scheduler.ScheduledTask) handle).cancel();
        } else {
            ((org.bukkit.scheduler.BukkitTask) handle).cancel();
        }
    }

    public void cancelAll() {
        if (IS_FOLIA) {
            for (Object handle : taskHandles.values()) {
                ((io.papermc.paper.threadedregions.scheduler.ScheduledTask) handle).cancel();
            }
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
        } else {
            Bukkit.getScheduler().cancelTasks(plugin);
        }
        taskHandles.clear();
    }
}
