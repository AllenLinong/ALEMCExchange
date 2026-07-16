package com.alwarp.manager;

import com.alwarp.ALwarp;
import com.alwarp.model.Landmark;
import com.alwarp.manager.TaxManager.PaymentReceipt;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传送管理器。处理延迟传送、倒计时、伤害取消。
 *
 * 延迟优先级（取最小值）：alwarp.delay.bypass > alwarp.delay.<N> > 默认配置
 */
public class TeleportManager {

    private final ALwarp plugin;
    private final Map<UUID, TeleportTask> activeTasks = new ConcurrentHashMap<>();

    public TeleportManager(ALwarp plugin) {
        this.plugin = plugin;
    }

    /**
     * 发起延迟传送。
     * @return true=已开始倒计时, false=已立即传送
     */
    public boolean requestTeleport(Player player, Landmark landmark) {
        if (!plugin.canAccessLandmarkServer(player, landmark)) {
            player.sendMessage(plugin.getMessageUtil().get("server_permission_denied"));
            return false;
        }
        if (plugin.getBlacklistManager().isBlacklisted(landmark.getId(), player.getUniqueId())) {
            player.sendMessage(plugin.getMessageUtil().get("landmark_blacklisted"));
            return false;
        }
        int delay = getTeleportDelay(player);

        if (delay <= 0) {
            doTeleport(player, landmark);
            return false;
        }

        // 取消已有任务
        cancelTeleport(player);

        // 启动倒计时
        int taskId = plugin.getScheduler().runTaskTimerAtEntity(player, new Runnable() {
            int remaining = delay;

            @Override
            public void run() {
                remaining--;
                if (remaining <= 0) {
                    cancelTeleport(player);
                    doTeleport(player, landmark);
                } else {
                    player.sendMessage(plugin.getMessageUtil().get(
                            "teleport_countdown", "%d", String.valueOf(remaining)));
                }
            }
        }, 20L, 20L); // 1秒后首次执行，避免和初始提示同时刷屏

        activeTasks.put(player.getUniqueId(), new TeleportTask(taskId));
        player.sendMessage(plugin.getMessageUtil().get("teleport_countdown", "%d", String.valueOf(delay)));
        return true;
    }

    /**
     * 取消传送倒计时。
     */
    public void cancelTeleport(Player player) {
        TeleportTask task = activeTasks.remove(player.getUniqueId());
        if (task != null) {
            plugin.getScheduler().cancelTask(task.taskId);
        }
    }

    public boolean hasPendingTeleport(UUID uuid) {
        return activeTasks.containsKey(uuid);
    }

    public boolean isRemoteLandmark(Landmark landmark) {
        String serverName = landmark.getServerName();
        return serverName != null
                && !serverName.isBlank()
                && !serverName.equalsIgnoreCase(plugin.getServerId());
    }

    public boolean canReachLandmark(Landmark landmark) {
        if (!isRemoteLandmark(landmark)) return true;
        return plugin.getRedisManager() != null
                && plugin.getRedisManager().isEnabled()
                && plugin.getRedisManager().isServerOnline(landmark.getServerName());
    }

    // ─── 内部方法 ───

    private void doTeleport(Player player, Landmark landmark) {
        if (!plugin.canAccessLandmarkServer(player, landmark)) {
            player.sendMessage(plugin.getMessageUtil().get("server_permission_denied"));
            return;
        }
        if (plugin.getBlacklistManager().isBlacklisted(landmark.getId(), player.getUniqueId())) {
            player.sendMessage(plugin.getMessageUtil().get("landmark_blacklisted"));
            return;
        }
        if (isRemoteLandmark(landmark)) {
            doCrossServerTeleport(player, landmark);
            return;
        }

        PaymentReceipt payment = plugin.getTaxManager().reservePayment(player, landmark);
        if (payment == null) {
            player.sendMessage(plugin.getMessageUtil().get("no_money"));
            return;
        }

        World world = plugin.getServer().getWorld(landmark.getWorld());
        if (world == null) {
            plugin.getTaxManager().refundPayment(player, payment);
            player.sendMessage(plugin.getMessageUtil().get("landmark_not_found"));
            return;
        }
        org.bukkit.Location loc = new org.bukkit.Location(
                world,
                landmark.getX() + 0.5, landmark.getY(), landmark.getZ() + 0.5
        );
        player.teleportAsync(loc).thenAccept(success -> {
            plugin.runAtPlayer(player, () -> completeTeleport(player, landmark, payment, success));
        });
    }

    private void doCrossServerTeleport(Player player, Landmark landmark) {
        if (!canReachLandmark(landmark)) {
            player.sendMessage(plugin.getMessageUtil().get("server_offline"));
            return;
        }

        plugin.getRedisManager().sendTeleportRequest(player.getUniqueId(), player.getName(), landmark);
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(landmark.getServerName());
        player.sendPluginMessage(plugin, "BungeeCord", out.toByteArray());
        player.sendMessage(plugin.getMessageUtil().get("cross_server_teleport",
                "%server%", plugin.getServerDisplayName(landmark.getServerName())));
    }

    public void completeTeleport(Player player, Landmark landmark, PaymentReceipt payment, boolean success) {
        if (!success) {
            plugin.getTaxManager().refundPayment(player, payment);
            player.sendMessage(plugin.getMessageUtil().get("teleport_failed_refunded"));
            return;
        }

        plugin.getTaxManager().finalizePaymentAsync(player, landmark, payment, paid -> {
            if (!paid) {
                if (player.isOnline()) {
                    player.sendMessage(plugin.getMessageUtil().get("payment_failed"));
                }
                return;
            }

            if (payment.charged() && player.isOnline()) {
                player.sendMessage(plugin.getMessageUtil().get("payment_success"));
            }
            plugin.getLandmarkManager().incrementVisitCount(player.getUniqueId(), landmark.getId());
        });
    }

    /**
     * 根据权限获取传送延迟（秒），取最小值。0=立即。
     */
    private int getTeleportDelay(Player player) {
        if (player.hasPermission("alwarp.delay.bypass")) return 0;

        int min = Integer.MAX_VALUE;
        for (org.bukkit.permissions.PermissionAttachmentInfo perm : player.getEffectivePermissions()) {
            String node = perm.getPermission();
            if (node.startsWith("alwarp.delay.")) {
                try {
                    int n = Integer.parseInt(node.substring("alwarp.delay.".length()));
                    if (n < min) min = n;
                } catch (NumberFormatException ignored) {}
            }
        }
        if (min != Integer.MAX_VALUE) return min;
        return plugin.getConfig().getInt("landmark.teleport_delay", 3);
    }

    private record TeleportTask(int taskId) {}
}
