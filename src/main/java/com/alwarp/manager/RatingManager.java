package com.alwarp.manager;

import com.alwarp.ALwarp;
import com.alwarp.model.Rating;
import com.alwarp.scheduler.FoliaScheduler;
import com.alwarp.storage.StorageManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 评分管理器。
 * 管理地标评分的CRUD、冷却时间和平均分计算。
 */
public class RatingManager {

    private final ALwarp plugin;
    private final StorageManager storage;
    private final FoliaScheduler scheduler;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<Integer, StorageManager.RatingStats> ratingStatsCache = new ConcurrentHashMap<>();
    private boolean enabled = true;
    private boolean reportsEnabled = true;
    private int cooldownSeconds = 30;
    private int minScore = 1;
    private int maxScore = 5;
    private int reportDeleteThreshold = 3;

    public RatingManager(ALwarp plugin, StorageManager storage, FoliaScheduler scheduler) {
        this.plugin = plugin;
        this.storage = storage;
        this.scheduler = scheduler;
    }

    public void loadConfig() {
        this.enabled = plugin.getConfig().getBoolean("rating.enabled", true);
        this.minScore = Math.max(1, Math.min(5, plugin.getConfig().getInt("rating.min_score", 1)));
        this.maxScore = Math.max(1, Math.min(5, plugin.getConfig().getInt("rating.max_score", 5)));
        if (minScore > maxScore) {
            int oldMin = minScore;
            minScore = maxScore;
            maxScore = oldMin;
        }
        this.reportsEnabled = plugin.getConfig().getBoolean("rating.report.enabled", true);
        this.reportDeleteThreshold = Math.max(1, plugin.getConfig().getInt("rating.report.delete_threshold", 3));
        if (plugin.getConfig().contains("rating.cooldown_seconds")) {
            this.cooldownSeconds = Math.max(0, plugin.getConfig().getInt("rating.cooldown_seconds", 30));
            return;
        }
        this.cooldownSeconds = Math.max(0, plugin.getConfig().getInt("rating.cooldown_minutes", 5) * 60);
    }

    public void setCooldownSeconds(int seconds) {
        this.cooldownSeconds = Math.max(0, seconds);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isReportsEnabled() {
        return reportsEnabled;
    }

    public int getMinScore() {
        return minScore;
    }

    public int getMaxScore() {
        return maxScore;
    }

    public int getReportDeleteThreshold() {
        return reportDeleteThreshold;
    }

    public void loadStatsAsync(Runnable callback) {
        scheduler.runTaskAsync(() -> {
            ratingStatsCache.clear();
            ratingStatsCache.putAll(storage.getAllRatingStats());
            scheduler.runTask(callback);
        });
    }

    /**
     * 检查玩家是否可以评分（冷却时间已过）。
     */
    public boolean canRate(UUID playerUuid) {
        return getRemainingCooldownSeconds(playerUuid) <= 0;
    }

    public long getRemainingCooldownSeconds(UUID playerUuid) {
        if (cooldownSeconds <= 0) return 0;
        Long last = cooldowns.get(playerUuid);
        if (last == null) return 0;
        long remainingMillis = cooldownSeconds * 1000L - (System.currentTimeMillis() - last);
        if (remainingMillis <= 0) {
            cooldowns.remove(playerUuid);
            return 0;
        }
        return (remainingMillis + 999L) / 1000L;
    }

    /**
     * 添加或更新评分。每个玩家每个地标只能有一个评分。
     */
    public void addOrUpdateRating(int landmarkId, UUID playerUuid, String playerName,
                                   int score, String comment, Callback callback) {
        scheduler.runTaskAsync(() -> {
            if (!enabled || score < minScore || score > maxScore || !storage.hasVisitedLandmark(playerUuid, landmarkId)) {
                scheduler.runTask(() -> callback.onResult(null));
                return;
            }
            Rating existing = storage.getRatingByPlayer(landmarkId, playerUuid);
            Rating result;
            if (existing != null) {
                existing.setScore(score);
                existing.setComment(comment);
                storage.updateRating(existing);
                result = existing;
            } else {
                Rating rating = new Rating(landmarkId, playerUuid, playerName, score, comment);
                rating.setCreatedAt(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now(plugin.getTimeZone())));
                result = storage.addRating(rating);
            }
            refreshStats(landmarkId);
            cooldowns.put(playerUuid, System.currentTimeMillis());
            if (plugin.getRedisManager() != null) {
                plugin.getRedisManager().broadcastLandmarkRefresh();
            }
            scheduler.runTask(() -> callback.onResult(result));
        });
    }

    /**
     * 获取地标的平均评分（同步，使用缓存或实时查询）。
     */
    public double getAverageRating(int landmarkId) {
        StorageManager.RatingStats stats = ratingStatsCache.get(landmarkId);
        return stats != null ? stats.average() : 0.0;
    }

    /**
     * 获取地标的评分数量。
     */
    public int getRatingCount(int landmarkId) {
        StorageManager.RatingStats stats = ratingStatsCache.get(landmarkId);
        return stats != null ? stats.count() : 0;
    }

    public void clearStats(int landmarkId) {
        ratingStatsCache.remove(landmarkId);
    }

    /**
     * 获取玩家对地标的评分。
     */
    public Rating getPlayerRating(int landmarkId, UUID playerUuid) {
        return storage.getRatingByPlayer(landmarkId, playerUuid);
    }

    /** 异步删除评分 */
    public void deleteRating(int ratingId, Callback callback) {
        scheduler.runTaskAsync(() -> {
            boolean ok = storage.deleteRating(ratingId);
            if (ok) {
                ratingStatsCache.clear();
                ratingStatsCache.putAll(storage.getAllRatingStats());
                if (plugin.getRedisManager() != null) {
                    plugin.getRedisManager().broadcastLandmarkRefresh();
                }
            }
            scheduler.runTask(() -> callback.onResult(ok));
        });
    }

    public void deleteRating(int landmarkId, int ratingId, Callback callback) {
        scheduler.runTaskAsync(() -> {
            boolean ok = storage.deleteRating(ratingId);
            if (ok) {
                refreshStats(landmarkId);
                if (plugin.getRedisManager() != null) {
                    plugin.getRedisManager().broadcastLandmarkRefresh();
                }
            }
            scheduler.runTask(() -> callback.onResult(ok));
        });
    }

    public void deletePlayerRating(int landmarkId, UUID playerUuid, Callback callback) {
        scheduler.runTaskAsync(() -> {
            Rating rating = storage.getRatingByPlayer(landmarkId, playerUuid);
            boolean ok = rating != null && storage.deleteRating(rating.getId());
            if (ok) {
                cooldowns.remove(playerUuid);
                refreshStats(landmarkId);
                if (plugin.getRedisManager() != null) {
                    plugin.getRedisManager().broadcastLandmarkRefresh();
                }
            }
            scheduler.runTask(() -> callback.onResult(ok));
        });
    }

    public void deleteRatingsByPlayer(UUID playerUuid, CountCallback callback) {
        scheduler.runTaskAsync(() -> {
            int deleted = storage.deleteRatingsByPlayer(playerUuid);
            if (deleted > 0) {
                ratingStatsCache.clear();
                ratingStatsCache.putAll(storage.getAllRatingStats());
                cooldowns.remove(playerUuid);
                if (plugin.getRedisManager() != null) {
                    plugin.getRedisManager().broadcastLandmarkRefresh();
                }
            }
            scheduler.runTask(() -> callback.onResult(deleted));
        });
    }

    /** 异步获取地标所有评分 */
    public void getRatings(int landmarkId, Callback callback) {
        scheduler.runTaskAsync(() -> {
            List<Rating> list = storage.getRatingsByLandmark(landmarkId);
            scheduler.runTask(() -> callback.onResult(list));
        });
    }

    public void hasVisitedLandmark(UUID playerUuid, int landmarkId, Callback callback) {
        scheduler.runTaskAsync(() -> {
            boolean visited = storage.hasVisitedLandmark(playerUuid, landmarkId);
            scheduler.runTask(() -> callback.onResult(visited));
        });
    }

    public void reportRating(int landmarkId, int ratingId, UUID reporterUuid, Callback callback) {
        scheduler.runTaskAsync(() -> {
            ReportResult result = reportRatingSync(landmarkId, ratingId, reporterUuid);
            scheduler.runTask(() -> callback.onResult(result));
        });
    }

    private ReportResult reportRatingSync(int landmarkId, int ratingId, UUID reporterUuid) {
        if (!reportsEnabled) {
            return new ReportResult(ReportStatus.DISABLED, 0, reportDeleteThreshold, false);
        }
        if (landmarkId <= 0 || ratingId <= 0 || reporterUuid == null) {
            return new ReportResult(ReportStatus.NOT_FOUND, 0, reportDeleteThreshold, false);
        }

        Rating target = null;
        for (Rating rating : storage.getRatingsByLandmark(landmarkId)) {
            if (rating.getId() == ratingId) {
                target = rating;
                break;
            }
        }
        if (target == null) {
            return new ReportResult(ReportStatus.NOT_FOUND, 0, reportDeleteThreshold, false);
        }
        if (reporterUuid.equals(target.getPlayerUuid())) {
            int count = storage.getRatingReportCount(ratingId);
            return new ReportResult(ReportStatus.OWN_REVIEW, count, reportDeleteThreshold, false);
        }

        boolean added = storage.addRatingReport(ratingId, reporterUuid);
        int count = storage.getRatingReportCount(ratingId);
        if (!added) {
            return new ReportResult(ReportStatus.ALREADY_REPORTED, count, reportDeleteThreshold, false);
        }

        if (count >= reportDeleteThreshold) {
            boolean deleted = storage.deleteRating(ratingId);
            if (deleted) {
                refreshStats(landmarkId);
                if (plugin.getRedisManager() != null) {
                    plugin.getRedisManager().broadcastLandmarkRefresh();
                }
                return new ReportResult(ReportStatus.DELETED, count, reportDeleteThreshold, true);
            }
        }
        return new ReportResult(ReportStatus.REPORTED, count, reportDeleteThreshold, false);
    }

    public interface Callback {
        void onResult(Object result);
    }

    public interface CountCallback {
        void onResult(int count);
    }

    public enum ReportStatus {
        DISABLED,
        NOT_FOUND,
        OWN_REVIEW,
        ALREADY_REPORTED,
        REPORTED,
        DELETED
    }

    public record ReportResult(ReportStatus status, int count, int threshold, boolean deleted) {}

    private void refreshStats(int landmarkId) {
        int count = storage.getRatingCount(landmarkId);
        if (count <= 0) {
            ratingStatsCache.remove(landmarkId);
            return;
        }
        ratingStatsCache.put(landmarkId, new StorageManager.RatingStats(count, storage.getAverageRating(landmarkId)));
    }
}
