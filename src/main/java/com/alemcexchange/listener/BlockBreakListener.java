package com.alemcexchange.listener;

import com.alemcexchange.config.ConfigManager;
import com.alemcexchange.database.DatabaseManager;
import com.alemcexchange.util.ColorUtil;
import com.alemcexchange.util.SchedulerUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.HashMap;
import java.util.Map;

import java.sql.SQLException;

public class BlockBreakListener implements Listener {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final DatabaseManager databaseManager;
    private final SchedulerUtil schedulerUtil;
    private com.alemcexchange.util.MenuManager menuManager;
    private final Map<BlockLocation, Long> playerPlacedBlocks = new HashMap<>();
    private static final long BLOCK_EXPIRY_TIME = 30 * 60 * 1000;

    public BlockBreakListener(JavaPlugin plugin, ConfigManager configManager, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.databaseManager = databaseManager;
        this.schedulerUtil = new SchedulerUtil(plugin);
        startCleanupTask();
    }

    public void setMenuManager(com.alemcexchange.util.MenuManager menuManager) {
        this.menuManager = menuManager;
    }

    private void startCleanupTask() {
        schedulerUtil.runAsync(() -> {
            long currentTime = System.currentTimeMillis();
            playerPlacedBlocks.entrySet().removeIf(entry -> currentTime - entry.getValue() > BLOCK_EXPIRY_TIME);
        });
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        BlockLocation blockLocation = new BlockLocation(event.getBlock().getLocation());
        playerPlacedBlocks.put(blockLocation, System.currentTimeMillis());
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        final Player player = event.getPlayer();
        final Material material = event.getBlock().getType();
        final String materialName = material.name();

        BlockLocation blockLocation = new BlockLocation(event.getBlock().getLocation());
        if (playerPlacedBlocks.containsKey(blockLocation)) {
            playerPlacedBlocks.remove(blockLocation);
            return;
        }

        if (configManager.getItems().contains("items." + materialName)) {
            schedulerUtil.runAsync(() -> {
                try {
                    if (!databaseManager.isUnlocked(player.getUniqueId(), materialName)) {
                        databaseManager.addMineProgress(player.getUniqueId(), materialName, 1);
                        // 清理进度缓存
                        if (menuManager != null) {
                            menuManager.clearPlayerCache(player.getUniqueId());
                        }
                        checkUnlock(player, materialName);
                    }
                } catch (SQLException | ClassNotFoundException e) {
                    plugin.getLogger().severe("Error handling block break: " + e.getMessage());
                }
            });
        }
    }

    private void checkUnlock(Player player, String materialName) throws SQLException, ClassNotFoundException {
        int requiredMine = configManager.getItems().getInt("items." + materialName + ".required_mine");
        int requiredSell = configManager.getItems().getInt("items." + materialName + ".required_sell");

        // 先清理缓存，确保获取最新的进度数据
        if (menuManager != null) {
            menuManager.clearPlayerCache(player.getUniqueId());
        }

        int mineProgress = databaseManager.getMineProgress(player.getUniqueId(), materialName);
        int sellProgress = databaseManager.getSellProgress(player.getUniqueId(), materialName);

        boolean mineCondition = requiredMine == 0 || mineProgress >= requiredMine;
        boolean sellCondition = requiredSell == 0 || sellProgress >= requiredSell;

        if (mineCondition && sellCondition && !databaseManager.isUnlocked(player.getUniqueId(), materialName)) {
            databaseManager.unlockItem(player.getUniqueId(), materialName);
            // 解锁后再次清理缓存
            if (menuManager != null) {
                menuManager.clearPlayerCache(player.getUniqueId());
            }
            String itemDisplayName = configManager.getItems().getString("items." + materialName + ".name", materialName);
            final String message = configManager.getLang().getString("prefix") + 
                    configManager.getLang().getString("mine.unlocked").replace("{item}", itemDisplayName);
            schedulerUtil.runSync(player, () -> {
                player.sendMessage(ColorUtil.translateColorCodes(message));
            });
        }
    }

    private static class BlockLocation {
        private final String world;
        private final int x;
        private final int y;
        private final int z;

        public BlockLocation(org.bukkit.Location location) {
            this.world = location.getWorld().getName();
            this.x = location.getBlockX();
            this.y = location.getBlockY();
            this.z = location.getBlockZ();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            BlockLocation that = (BlockLocation) obj;
            return x == that.x && y == that.y && z == that.z && world.equals(that.world);
        }

        @Override
        public int hashCode() {
            int result = world.hashCode();
            result = 31 * result + x;
            result = 31 * result + y;
            result = 31 * result + z;
            return result;
        }
    }
}
