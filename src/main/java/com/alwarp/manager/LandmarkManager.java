package com.alwarp.manager;

import com.alwarp.ALwarp;
import com.alwarp.model.Landmark;
import com.alwarp.scheduler.FoliaScheduler;
import com.alwarp.storage.StorageManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 地标管理器。
 * 提供地标的CRUD操作、内存缓存和业务逻辑。
 */
public class LandmarkManager {

    private final ALwarp plugin;
    private final StorageManager storage;
    private final FoliaScheduler scheduler;
    private final Map<Integer, Landmark> cache = new ConcurrentHashMap<>();
    private volatile List<Landmark> cachedAllByHeat = List.of();
    private volatile Map<Integer, List<Landmark>> cachedByCategory = Map.of();
    private volatile Map<UUID, List<Landmark>> cachedByOwner = Map.of();

    public LandmarkManager(ALwarp plugin, StorageManager storage, FoliaScheduler scheduler) {
        this.plugin = plugin;
        this.storage = storage;
        this.scheduler = scheduler;
    }

    private void rebuildViews() {
        List<Landmark> all = new ArrayList<>(cache.values());
        Map<Integer, List<Landmark>> byCategory = new HashMap<>();
        Map<UUID, List<Landmark>> byOwner = new HashMap<>();

        for (Landmark lm : cache.values()) {
            byCategory.computeIfAbsent(lm.getCategoryId(), k -> new ArrayList<>()).add(lm);
            byOwner.computeIfAbsent(lm.getOwnerUuid(), k -> new ArrayList<>()).add(lm);
        }

        all.sort(this::compareByHeat);
        for (List<Landmark> landmarks : byCategory.values()) {
            landmarks.sort(this::compareByHeat);
        }
        for (List<Landmark> landmarks : byOwner.values()) {
            landmarks.sort(this::compareByCreatedAt);
        }

        cachedAllByHeat = Collections.unmodifiableList(all);
        cachedByCategory = freezeLandmarkMap(byCategory);
        cachedByOwner = freezeLandmarkMapByOwner(byOwner);
    }

    private Map<Integer, List<Landmark>> freezeLandmarkMap(Map<Integer, List<Landmark>> source) {
        Map<Integer, List<Landmark>> result = new HashMap<>();
        for (Map.Entry<Integer, List<Landmark>> entry : source.entrySet()) {
            result.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(result);
    }

    private Map<UUID, List<Landmark>> freezeLandmarkMapByOwner(Map<UUID, List<Landmark>> source) {
        Map<UUID, List<Landmark>> result = new HashMap<>();
        for (Map.Entry<UUID, List<Landmark>> entry : source.entrySet()) {
            result.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(result);
    }

    private int compareByHeat(Landmark a, Landmark b) {
        int heat = Integer.compare(b.getWeeklyVisits(), a.getWeeklyVisits());
        return heat != 0 ? heat : Integer.compare(a.getId(), b.getId());
    }

    private int compareByCreatedAt(Landmark a, Landmark b) {
        if (a.getCreatedAt() == null && b.getCreatedAt() == null) return Integer.compare(a.getId(), b.getId());
        if (a.getCreatedAt() == null) return 1;
        if (b.getCreatedAt() == null) return -1;
        int date = b.getCreatedAt().compareTo(a.getCreatedAt());
        return date != 0 ? date : Integer.compare(a.getId(), b.getId());
    }

    /**
     * 从数据库加载所有地标到缓存。
     */
    public void loadAllAsync(Runnable callback) {
        scheduler.runTaskAsync(() -> {
            cache.clear();
            List<Landmark> all = storage.getAllLandmarks();
            for (Landmark lm : all) cache.put(lm.getId(), lm);
            rebuildViews();
            scheduler.runTask(callback);
        });
    }

    /**
     * 异步加载地标（缓存未命中时）。
     */
    public void getLandmarkAsync(int id, Callback callback) {
        Landmark cached = cache.get(id);
        if (cached != null) {
            callback.onResult(cached);
            return;
        }
        scheduler.runTaskAsync(() -> {
            Landmark lm = storage.getLandmarkById(id);
            if (lm != null) {
                cache.put(lm.getId(), lm);
                rebuildViews();
            }
            scheduler.runTask(() -> callback.onResult(lm));
        });
    }

    public Landmark getLandmark(int id) {
        return cache.get(id);
    }

    public List<Landmark> getCachedLandmarks() {
        return new ArrayList<>(cache.values());
    }

    public List<Landmark> getLandmarksByOwner(UUID ownerUuid) {
        return new ArrayList<>(cachedByOwner.getOrDefault(ownerUuid, List.of()));
    }

    public List<Landmark> getLandmarksByCategory(int categoryId) {
        return new ArrayList<>(cachedByCategory.getOrDefault(categoryId, List.of()));
    }

    public List<Landmark> getAllLandmarks() {
        return new ArrayList<>(cachedAllByHeat);
    }

    public List<Landmark> searchLandmarks(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        List<Landmark> result = new ArrayList<>();
        for (Landmark lm : cachedAllByHeat) {
            String name = lm.getName();
            String owner = lm.getOwnerName();
            if ((name != null && name.toLowerCase(Locale.ROOT).contains(lower))
                    || (owner != null && owner.toLowerCase(Locale.ROOT).contains(lower))) {
                result.add(lm);
            }
        }
        return result;
    }

    /**
     * 创建地标。同步操作（数据库写入在异步线程）。
     */
    public void createLandmark(Landmark landmark, Callback callback) {
        scheduler.runTaskAsync(() -> {
            Landmark result = storage.createLandmark(landmark);
            if (result != null) {
                storage.recordLandmarkVisit(result.getOwnerUuid(), result.getId());
                cache.put(result.getId(), result);
                rebuildViews();
                if (plugin.getRedisManager() != null) {
                    plugin.getRedisManager().broadcastLandmarkUpdate(result);
                }
            }
            scheduler.runTask(() -> callback.onResult(result));
        });
    }

    /**
     * 更新地标。
     */
    public void updateLandmark(Landmark landmark, Callback callback) {
        scheduler.runTaskAsync(() -> {
            boolean success = storage.updateLandmark(landmark);
            if (success) {
                cache.put(landmark.getId(), landmark);
                rebuildViews();
                if (plugin.getRedisManager() != null) {
                    plugin.getRedisManager().broadcastLandmarkUpdate(landmark);
                }
            }
            scheduler.runTask(() -> callback.onResult(success ? landmark : null));
        });
    }

    /**
     * 删除地标。
     */
    public void deleteLandmark(int id, Callback callback) {
        scheduler.runTaskAsync(() -> {
            boolean success = storage.deleteLandmark(id);
            if (success) {
                cache.remove(id);
                rebuildViews();
                clearAttachedCaches(id);
                if (plugin.getRedisManager() != null) {
                    plugin.getRedisManager().broadcastLandmarkDelete(id);
                }
            }
            scheduler.runTask(() -> callback.onResult(success));
        });
    }

    public void deleteLandmarksByOwner(UUID ownerUuid, Callback callback) {
        scheduler.runTaskAsync(() -> {
            List<Integer> removedIds = cache.values().stream()
                    .filter(landmark -> ownerUuid.equals(landmark.getOwnerUuid()))
                    .map(Landmark::getId)
                    .toList();
            int deleted = storage.deleteLandmarksByOwner(ownerUuid);
            if (deleted > 0) {
                cache.entrySet().removeIf(entry -> ownerUuid.equals(entry.getValue().getOwnerUuid()));
                rebuildViews();
                for (int id : removedIds) {
                    clearAttachedCaches(id);
                }
                if (plugin.getRedisManager() != null) {
                    plugin.getRedisManager().broadcastLandmarkRefresh();
                }
            }
            scheduler.runTask(() -> callback.onResult(deleted));
        });
    }

    private void clearAttachedCaches(int landmarkId) {
        plugin.getManagerManager().clearCachedManagers(landmarkId);
        plugin.getBlacklistManager().clearCachedBlacklists(landmarkId);
        plugin.getPinManager().clearCachedLandmark(landmarkId);
        storage.deletePinsByLandmark(landmarkId);
        plugin.getFavoritesManager().clearCachedLandmark(landmarkId);
        plugin.getRatingManager().clearStats(landmarkId);
    }

    /**
     * 增加访问计数。
     */
    public void incrementVisitCount(int id) {
        Landmark lm = cache.get(id);
        if (lm != null) {
            lm.setVisitCount(lm.getVisitCount() + 1);
        }
        scheduler.runTaskAsync(() -> storage.incrementVisitCount(id));
    }

    public void incrementVisitCount(UUID playerUuid, int id) {
        Landmark lm = cache.get(id);
        if (lm != null) {
            lm.setVisitCount(lm.getVisitCount() + 1);
        }
        scheduler.runTaskAsync(() -> {
            storage.incrementVisitCount(id);
            storage.recordLandmarkVisit(playerUuid, id);
        });
    }

    public void adjustHeat(int id, int delta) {
        adjustHeatCache(id, delta);
        scheduler.runTaskAsync(() -> storage.adjustLandmarkHeat(id, delta));
    }

    public void adjustHeatCache(int id, int delta) {
        Landmark lm = cache.get(id);
        if (lm != null) {
            lm.setWeeklyVisits(Math.max(0, lm.getWeeklyVisits() + delta));
            rebuildViews();
        }
    }

    public void adjustHeatCacheBulk(Map<Integer, Integer> deltas) {
        boolean changed = false;
        for (Map.Entry<Integer, Integer> entry : deltas.entrySet()) {
            Landmark lm = cache.get(entry.getKey());
            if (lm == null) continue;
            lm.setWeeklyVisits(Math.max(0, lm.getWeeklyVisits() + entry.getValue()));
            changed = true;
        }
        if (changed) rebuildViews();
    }

    public void syncHeatToFavoriteCounts(Map<Integer, Integer> favoriteCounts) {
        Map<Integer, Integer> deltas = new HashMap<>();
        for (Landmark lm : cache.values()) {
            int target = Math.max(0, favoriteCounts.getOrDefault(lm.getId(), 0));
            int delta = target - lm.getWeeklyVisits();
            if (delta == 0) continue;
            lm.setWeeklyVisits(target);
            deltas.put(lm.getId(), delta);
        }
        if (deltas.isEmpty()) return;

        rebuildViews();
        scheduler.runTaskAsync(() -> {
            for (Map.Entry<Integer, Integer> entry : deltas.entrySet()) {
                storage.adjustLandmarkHeat(entry.getKey(), entry.getValue());
            }
        });
    }

    public void setHeatToFavoriteCount(int id, int favoriteCount) {
        Landmark lm = cache.get(id);
        if (lm == null) return;

        int target = Math.max(0, favoriteCount);
        int delta = target - lm.getWeeklyVisits();
        if (delta == 0) return;

        lm.setWeeklyVisits(target);
        rebuildViews();
        scheduler.runTaskAsync(() -> storage.adjustLandmarkHeat(id, delta));
    }

    /**
     * 刷新缓存（重新加载所有数据）。
     */
    public void refreshCache(Runnable callback) {
        scheduler.runTaskAsync(() -> {
            cache.clear();
            List<Landmark> all = storage.getAllLandmarks();
            for (Landmark lm : all) cache.put(lm.getId(), lm);
            rebuildViews();
            scheduler.runTask(callback);
        });
    }

    /**
     * 获取玩家拥有的地标数量。
     */
    public int getPlayerLandmarkCount(UUID uuid) {
        return cachedByOwner.getOrDefault(uuid, List.of()).size();
    }

    /**
     * 检查地标名称是否唯一（缓存中不存在同名地标）。
     */
    public boolean isNameUnique(String name) {
        for (Landmark lm : cache.values()) {
            if (lm.getName().equalsIgnoreCase(name)) return false;
        }
        return true;
    }

    /**
     * 更新缓存中的地标。
     */
    public void updateCache(Landmark landmark) {
        cache.put(landmark.getId(), landmark);
        rebuildViews();
    }

    /**
     * 导入地标（直接写入数据库并加入缓存）。
     */
    public void importLandmark(Landmark landmark, Callback callback) {
        scheduler.runTaskAsync(() -> {
            Landmark result = storage.createLandmark(landmark);
            if (result != null) {
                cache.put(result.getId(), result);
                rebuildViews();
            }
            scheduler.runTask(() -> callback.onResult(result));
        });
    }

    public interface Callback {
        void onResult(Object result);
    }
}
