package com.alwarp.manager;

import com.alwarp.ALwarp;
import com.alwarp.model.Landmark;
import com.alwarp.scheduler.FoliaScheduler;
import com.alwarp.storage.StorageManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 收藏管理器。管理玩家收藏的地标ID列表。
 */
public class FavoritesManager {

    private static final int HEAT_PER_FAVORITE = 1;

    private final ALwarp plugin;
    private final StorageManager storage;
    private final FoliaScheduler scheduler;
    private final Map<UUID, Set<Integer>> favorites = new ConcurrentHashMap<>();

    public FavoritesManager(ALwarp plugin, StorageManager storage, FoliaScheduler scheduler) {
        this.plugin = plugin;
        this.storage = storage;
        this.scheduler = scheduler;
    }

    public void loadAllAsync(Runnable callback) {
        scheduler.runTaskAsync(() -> {
            Map<UUID, List<Integer>> loaded = storage.getAllFavorites();
            favorites.clear();
            for (Map.Entry<UUID, List<Integer>> entry : loaded.entrySet()) {
                Set<Integer> set = ConcurrentHashMap.newKeySet();
                for (int landmarkId : entry.getValue()) {
                    Landmark landmark = plugin.getLandmarkManager().getLandmark(landmarkId);
                    if (landmark != null && !landmark.isPrivate()
                            && !storage.isBlacklisted(landmarkId, entry.getKey())) {
                        set.add(landmarkId);
                    } else if (landmark != null) {
                        storage.removeFavorite(entry.getKey(), landmarkId);
                    }
                }
                if (!set.isEmpty()) favorites.put(entry.getKey(), set);
            }
            syncLandmarkHeatToFavoriteCounts();
            scheduler.runTask(callback);
        });
    }

    private void syncLandmarkHeatToFavoriteCounts() {
        Map<Integer, Integer> counts = new HashMap<>();
        for (Set<Integer> set : favorites.values()) {
            for (int landmarkId : set) {
                counts.merge(landmarkId, 1, Integer::sum);
            }
        }
        plugin.getLandmarkManager().syncHeatToFavoriteCounts(counts);
    }

    public boolean isFavorite(UUID uuid, int landmarkId) {
        Set<Integer> set = favorites.get(uuid);
        return set != null && set.contains(landmarkId);
    }

    public boolean toggle(UUID uuid, int landmarkId) {
        Landmark landmark = plugin.getLandmarkManager().getLandmark(landmarkId);
        if (landmark != null && landmark.isPrivate()) return false;
        if (plugin.getBlacklistManager().isBlacklisted(landmarkId, uuid)) return false;
        favorites.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
        Set<Integer> set = favorites.get(uuid);
        if (set.remove(landmarkId)) {
            plugin.getLandmarkManager().adjustHeatCache(landmarkId, -HEAT_PER_FAVORITE);
            persistRemove(uuid, landmarkId, true);
            return false;
        } else {
            set.add(landmarkId);
            plugin.getLandmarkManager().adjustHeatCache(landmarkId, HEAT_PER_FAVORITE);
            persistAdd(uuid, landmarkId, true);
            return true;
        }
    }

    public void add(UUID uuid, int landmarkId) {
        Landmark landmark = plugin.getLandmarkManager().getLandmark(landmarkId);
        if (landmark != null && landmark.isPrivate()) return;
        if (plugin.getBlacklistManager().isBlacklisted(landmarkId, uuid)) return;
        if (favorites.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet()).add(landmarkId)) {
            plugin.getLandmarkManager().adjustHeatCache(landmarkId, HEAT_PER_FAVORITE);
            persistAdd(uuid, landmarkId, true);
        }
    }

    public void remove(UUID uuid, int landmarkId) {
        Set<Integer> set = favorites.get(uuid);
        if (set != null && set.remove(landmarkId)) {
            plugin.getLandmarkManager().adjustHeatCache(landmarkId, -HEAT_PER_FAVORITE);
            persistRemove(uuid, landmarkId, true);
        }
    }

    public void removeFavoritesByLandmark(int landmarkId, Callback callback) {
        int removedFromCache = 0;
        for (Map.Entry<UUID, Set<Integer>> entry : favorites.entrySet()) {
            Set<Integer> set = entry.getValue();
            if (set.remove(landmarkId)) removedFromCache++;
            if (set.isEmpty()) favorites.remove(entry.getKey(), set);
        }

        plugin.getLandmarkManager().setHeatToFavoriteCount(landmarkId, 0);
        int cacheCount = removedFromCache;
        scheduler.runTaskAsync(() -> {
            int deleted = storage.removeFavoritesByLandmark(landmarkId);
            if (Math.max(deleted, cacheCount) > 0 && plugin.getRedisManager() != null) {
                plugin.getRedisManager().broadcastLandmarkRefresh();
            }
            scheduler.runTask(() -> callback.onResult(Math.max(deleted, cacheCount)));
        });
    }

    public void clearCachedLandmark(int landmarkId) {
        for (Map.Entry<UUID, Set<Integer>> entry : favorites.entrySet()) {
            Set<Integer> set = entry.getValue();
            set.remove(landmarkId);
            if (set.isEmpty()) favorites.remove(entry.getKey(), set);
        }
    }

    public List<Integer> getFavorites(UUID uuid) {
        Set<Integer> set = favorites.get(uuid);
        return set != null ? new ArrayList<>(set) : List.of();
    }

    public void clear(UUID uuid) {
        favorites.remove(uuid);
    }

    public void deleteFavoritesByPlayer(UUID uuid, Callback callback) {
        Set<Integer> current = favorites.get(uuid);
        Set<Integer> toAdjust = current != null ? new HashSet<>(current) : Set.of();

        scheduler.runTaskAsync(() -> {
            int deleted = storage.removeFavoritesByPlayer(uuid);
            if (deleted > 0) {
                for (int landmarkId : toAdjust) {
                    storage.adjustLandmarkHeat(landmarkId, -HEAT_PER_FAVORITE);
                }
                if (plugin.getRedisManager() != null) {
                    plugin.getRedisManager().broadcastLandmarkRefresh();
                }
                scheduler.runTask(() -> {
                    favorites.remove(uuid);
                    Map<Integer, Integer> deltas = new HashMap<>();
                    for (int landmarkId : toAdjust) deltas.put(landmarkId, -HEAT_PER_FAVORITE);
                    plugin.getLandmarkManager().adjustHeatCacheBulk(deltas);
                    callback.onResult(deleted);
                });
            } else {
                scheduler.runTask(() -> callback.onResult(deleted));
            }
        });
    }

    private void persistAdd(UUID uuid, int landmarkId, boolean updateHeat) {
        scheduler.runTaskAsync(() -> {
            boolean changed = storage.addFavorite(uuid, landmarkId);
            if (updateHeat && changed) {
                storage.adjustLandmarkHeat(landmarkId, HEAT_PER_FAVORITE);
                if (plugin.getRedisManager() != null) {
                    plugin.getRedisManager().broadcastLandmarkRefresh();
                }
            } else if (updateHeat && !changed) {
                scheduler.runTask(() -> plugin.getLandmarkManager().adjustHeatCache(landmarkId, -HEAT_PER_FAVORITE));
            }
        });
    }

    private void persistRemove(UUID uuid, int landmarkId, boolean updateHeat) {
        scheduler.runTaskAsync(() -> {
            boolean changed = storage.removeFavorite(uuid, landmarkId);
            if (updateHeat && changed) {
                storage.adjustLandmarkHeat(landmarkId, -HEAT_PER_FAVORITE);
                if (plugin.getRedisManager() != null) {
                    plugin.getRedisManager().broadcastLandmarkRefresh();
                }
            } else if (updateHeat && !changed) {
                scheduler.runTask(() -> plugin.getLandmarkManager().adjustHeatCache(landmarkId, HEAT_PER_FAVORITE));
            }
        });
    }

    public interface Callback {
        void onResult(int count);
    }
}
