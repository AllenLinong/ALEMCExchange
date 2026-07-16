package com.alwarp.manager;

import com.alwarp.ALwarp;
import com.alwarp.model.Landmark;
import com.alwarp.model.LandmarkAdmin;
import com.alwarp.scheduler.FoliaScheduler;
import com.alwarp.storage.StorageManager;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理组成员管理器。
 * 管理地标管理员的增删查。
 */
public class ManagerManager {

    private final ALwarp plugin;
    private final StorageManager storage;
    private final FoliaScheduler scheduler;
    private final Map<Integer, Set<UUID>> managerCache = new ConcurrentHashMap<>();
    private final Set<Integer> loadedLandmarks = ConcurrentHashMap.newKeySet();

    public ManagerManager(ALwarp plugin, StorageManager storage, FoliaScheduler scheduler) {
        this.plugin = plugin;
        this.storage = storage;
        this.scheduler = scheduler;
    }

    /**
     * 启动时异步预加载所有地标管理员关系，供传送收费判断走内存。
     */
    public void loadAllAsync(Runnable callback) {
        scheduler.runTaskAsync(() -> {
            managerCache.clear();
            loadedLandmarks.clear();
            for (Landmark landmark : plugin.getLandmarkManager().getCachedLandmarks()) {
                List<LandmarkAdmin> managers = storage.getManagersByLandmark(landmark.getId());
                rebuildLandmarkCache(landmark.getId(), managers);
            }
            scheduler.runTask(callback);
        });
    }

    /**
     * 添加管理员。
     */
    public void addManager(int landmarkId, UUID playerUuid, String playerName, Callback callback) {
        scheduler.runTaskAsync(() -> {
            if (storage.isManager(landmarkId, playerUuid)) {
                scheduler.runTask(() -> callback.onResult(null)); // 已经是管理员
                return;
            }
            LandmarkAdmin admin = new LandmarkAdmin(landmarkId, playerUuid, playerName);
            LandmarkAdmin result = storage.addManager(admin);
            if (result != null) {
                if (storage.isBlacklisted(landmarkId, playerUuid)) {
                    storage.removeBlacklist(landmarkId, playerUuid);
                    plugin.getBlacklistManager().removeCachedBlacklist(landmarkId, playerUuid);
                }
                managerCache.computeIfAbsent(landmarkId, k -> ConcurrentHashMap.newKeySet()).add(playerUuid);
                loadedLandmarks.add(landmarkId);
                if (plugin.getRedisManager() != null) {
                    plugin.getRedisManager().broadcastLandmarkRefresh();
                }
            }
            scheduler.runTask(() -> callback.onResult(result));
        });
    }

    /**
     * 移除管理员。
     */
    public void removeManager(int landmarkId, UUID playerUuid, Callback callback) {
        scheduler.runTaskAsync(() -> {
            boolean success = storage.removeManager(landmarkId, playerUuid);
            if (success) {
                Set<UUID> managers = managerCache.get(landmarkId);
                if (managers != null) managers.remove(playerUuid);
                if (plugin.getRedisManager() != null) {
                    plugin.getRedisManager().broadcastLandmarkRefresh();
                }
            }
            scheduler.runTask(() -> callback.onResult(success));
        });
    }

    /**
     * 获取地标的管理员列表。
     */
    public void getManagers(int landmarkId, Callback callback) {
        scheduler.runTaskAsync(() -> {
            List<LandmarkAdmin> list = storage.getManagersByLandmark(landmarkId);
            rebuildLandmarkCache(landmarkId, list);
            scheduler.runTask(() -> callback.onResult(list));
        });
    }

    /**
     * 检查玩家是否是地标的管理员。
     */
    public void isManagerAsync(int landmarkId, UUID playerUuid, Callback callback) {
        scheduler.runTaskAsync(() -> {
            boolean isMgr = storage.isManager(landmarkId, playerUuid);
            if (isMgr) {
                managerCache.computeIfAbsent(landmarkId, k -> ConcurrentHashMap.newKeySet()).add(playerUuid);
            }
            loadedLandmarks.add(landmarkId);
            scheduler.runTask(() -> callback.onResult(isMgr));
        });
    }

    public boolean isManager(int landmarkId, UUID playerUuid) {
        Set<UUID> managers = managerCache.get(landmarkId);
        if (managers != null) return managers.contains(playerUuid);
        if (loadedLandmarks.contains(landmarkId)) return false;
        boolean isManager = storage.isManager(landmarkId, playerUuid);
        if (isManager) {
            managerCache.computeIfAbsent(landmarkId, k -> ConcurrentHashMap.newKeySet()).add(playerUuid);
        }
        loadedLandmarks.add(landmarkId);
        return isManager;
    }

    /**
     * 获取玩家管理的所有地标ID。
     */
    public boolean isManagerCached(int landmarkId, UUID playerUuid) {
        Set<UUID> managers = managerCache.get(landmarkId);
        return managers != null && managers.contains(playerUuid);
    }

    public void removeCachedManager(int landmarkId, UUID playerUuid) {
        Set<UUID> managers = managerCache.get(landmarkId);
        if (managers != null) managers.remove(playerUuid);
    }

    public void clearCachedManagers(int landmarkId) {
        managerCache.put(landmarkId, ConcurrentHashMap.newKeySet());
        loadedLandmarks.add(landmarkId);
    }

    public void getManagedLandmarkIds(UUID playerUuid, Callback callback) {
        scheduler.runTaskAsync(() -> {
            List<Integer> ids = storage.getManagedLandmarkIds(playerUuid);
            scheduler.runTask(() -> callback.onResult(ids));
        });
    }

    public void deleteAllManagers(int landmarkId) {
        managerCache.put(landmarkId, ConcurrentHashMap.newKeySet());
        loadedLandmarks.add(landmarkId);
        scheduler.runTaskAsync(() -> {
            storage.deleteAllManagers(landmarkId);
            if (plugin.getRedisManager() != null) {
                plugin.getRedisManager().broadcastLandmarkRefresh();
            }
        });
    }

    private void rebuildLandmarkCache(int landmarkId, List<LandmarkAdmin> managers) {
        Set<UUID> cache = ConcurrentHashMap.newKeySet();
        for (LandmarkAdmin manager : managers) cache.add(manager.getPlayerUuid());
        managerCache.put(landmarkId, cache);
        loadedLandmarks.add(landmarkId);
    }

    public interface Callback {
        void onResult(Object result);
    }
}
