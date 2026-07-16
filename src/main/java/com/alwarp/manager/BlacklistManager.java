package com.alwarp.manager;

import com.alwarp.ALwarp;
import com.alwarp.model.Landmark;
import com.alwarp.model.LandmarkBlacklist;
import com.alwarp.scheduler.FoliaScheduler;
import com.alwarp.storage.StorageManager;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BlacklistManager {

    private final ALwarp plugin;
    private final StorageManager storage;
    private final FoliaScheduler scheduler;
    private final Map<Integer, Set<UUID>> blacklistCache = new ConcurrentHashMap<>();
    private final Set<Integer> loadedLandmarks = ConcurrentHashMap.newKeySet();

    public BlacklistManager(ALwarp plugin, StorageManager storage, FoliaScheduler scheduler) {
        this.plugin = plugin;
        this.storage = storage;
        this.scheduler = scheduler;
    }

    public void loadAllAsync(Runnable callback) {
        scheduler.runTaskAsync(() -> {
            blacklistCache.clear();
            loadedLandmarks.clear();
            for (Landmark landmark : plugin.getLandmarkManager().getCachedLandmarks()) {
                List<LandmarkBlacklist> blacklists = storage.getBlacklistByLandmark(landmark.getId());
                rebuildLandmarkCache(landmark.getId(), blacklists);
            }
            scheduler.runTask(callback);
        });
    }

    public void addBlacklist(int landmarkId, UUID playerUuid, String playerName, Callback callback) {
        scheduler.runTaskAsync(() -> {
            if (storage.isBlacklisted(landmarkId, playerUuid)) {
                scheduler.runTask(() -> callback.onResult(null));
                return;
            }
            LandmarkBlacklist blacklist = new LandmarkBlacklist(landmarkId, playerUuid, playerName);
            LandmarkBlacklist result = storage.addBlacklist(blacklist);
            if (result != null) {
                if (storage.isManager(landmarkId, playerUuid)) {
                    storage.removeManager(landmarkId, playerUuid);
                    plugin.getManagerManager().removeCachedManager(landmarkId, playerUuid);
                }
                blacklistCache.computeIfAbsent(landmarkId, k -> ConcurrentHashMap.newKeySet()).add(playerUuid);
                loadedLandmarks.add(landmarkId);
                if (plugin.getRedisManager() != null) {
                    plugin.getRedisManager().broadcastLandmarkRefresh();
                }
            }
            scheduler.runTask(() -> {
                if (result != null) {
                    plugin.getFavoritesManager().remove(playerUuid, landmarkId);
                }
                callback.onResult(result);
            });
        });
    }

    public void removeBlacklist(int landmarkId, UUID playerUuid, Callback callback) {
        scheduler.runTaskAsync(() -> {
            boolean success = storage.removeBlacklist(landmarkId, playerUuid);
            if (success) {
                Set<UUID> blacklists = blacklistCache.get(landmarkId);
                if (blacklists != null) blacklists.remove(playerUuid);
                if (plugin.getRedisManager() != null) {
                    plugin.getRedisManager().broadcastLandmarkRefresh();
                }
            }
            scheduler.runTask(() -> callback.onResult(success));
        });
    }

    public void getBlacklist(int landmarkId, Callback callback) {
        scheduler.runTaskAsync(() -> {
            List<LandmarkBlacklist> list = storage.getBlacklistByLandmark(landmarkId);
            rebuildLandmarkCache(landmarkId, list);
            scheduler.runTask(() -> callback.onResult(list));
        });
    }

    public boolean isBlacklisted(int landmarkId, UUID playerUuid) {
        Set<UUID> blacklists = blacklistCache.get(landmarkId);
        if (blacklists != null) return blacklists.contains(playerUuid);
        if (loadedLandmarks.contains(landmarkId)) return false;
        boolean blacklisted = storage.isBlacklisted(landmarkId, playerUuid);
        if (blacklisted) {
            blacklistCache.computeIfAbsent(landmarkId, k -> ConcurrentHashMap.newKeySet()).add(playerUuid);
        }
        loadedLandmarks.add(landmarkId);
        return blacklisted;
    }

    public boolean isBlacklistedCached(int landmarkId, UUID playerUuid) {
        Set<UUID> blacklists = blacklistCache.get(landmarkId);
        return blacklists != null && blacklists.contains(playerUuid);
    }

    public void removeCachedBlacklist(int landmarkId, UUID playerUuid) {
        Set<UUID> blacklists = blacklistCache.get(landmarkId);
        if (blacklists != null) blacklists.remove(playerUuid);
    }

    public void clearCachedBlacklists(int landmarkId) {
        blacklistCache.put(landmarkId, ConcurrentHashMap.newKeySet());
        loadedLandmarks.add(landmarkId);
    }

    public void deleteAllBlacklists(int landmarkId) {
        blacklistCache.put(landmarkId, ConcurrentHashMap.newKeySet());
        loadedLandmarks.add(landmarkId);
        scheduler.runTaskAsync(() -> storage.deleteAllBlacklists(landmarkId));
    }

    private void rebuildLandmarkCache(int landmarkId, List<LandmarkBlacklist> blacklists) {
        Set<UUID> cache = ConcurrentHashMap.newKeySet();
        for (LandmarkBlacklist blacklist : blacklists) cache.add(blacklist.getPlayerUuid());
        blacklistCache.put(landmarkId, cache);
        loadedLandmarks.add(landmarkId);
    }

    public interface Callback {
        void onResult(Object result);
    }
}
