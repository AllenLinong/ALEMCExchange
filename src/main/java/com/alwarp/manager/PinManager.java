package com.alwarp.manager;

import com.alwarp.ALwarp;
import com.alwarp.model.LandmarkPin;
import com.alwarp.scheduler.FoliaScheduler;
import com.alwarp.storage.StorageManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PinManager {

    private final ALwarp plugin;
    private final StorageManager storage;
    private final FoliaScheduler scheduler;
    private final Map<Integer, LandmarkPin> activePins = new ConcurrentHashMap<>();

    public PinManager(ALwarp plugin, StorageManager storage, FoliaScheduler scheduler) {
        this.plugin = plugin;
        this.storage = storage;
        this.scheduler = scheduler;
    }

    public void loadAllAsync(Runnable callback) {
        scheduler.runTaskAsync(() -> {
            Timestamp now = now();
            storage.deleteExpiredPins(now);
            activePins.clear();
            for (LandmarkPin pin : storage.getActivePins(now)) {
                if (plugin.getLandmarkManager().getLandmark(pin.getLandmarkId()) != null) {
                    activePins.put(pin.getSlotIndex(), pin);
                } else {
                    storage.deletePinsByLandmark(pin.getLandmarkId());
                }
            }
            scheduler.runTask(callback);
        });
    }

    public void startCleanupTask() {
        scheduler.runTaskTimerAsync(() -> {
            Timestamp now = now();
            int removed = storage.deleteExpiredPins(now);
            if (removed <= 0) return;
            activePins.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
            if (plugin.getRedisManager() != null) {
                plugin.getRedisManager().broadcastLandmarkRefresh();
            }
        }, 20L * 60L, 20L * 60L * 5L);
    }

    public LandmarkPin getActivePin(int slotIndex) {
        LandmarkPin pin = activePins.get(slotIndex);
        if (pin == null) return null;
        if (pin.isExpired(now())) {
            activePins.remove(slotIndex, pin);
            cleanupExpiredAsync();
            return null;
        }
        return pin;
    }

    public List<LandmarkPin> getActivePins() {
        cleanupExpiredCache();
        List<LandmarkPin> pins = new ArrayList<>(activePins.values());
        pins.sort(Comparator.comparingInt(LandmarkPin::getSlotIndex));
        return pins;
    }

    public void pinLandmark(int slotIndex, int landmarkId, UUID buyerUuid, String buyerName,
                            long durationSeconds, Callback callback) {
        scheduler.runTaskAsync(() -> {
            Timestamp now = now();
            storage.deleteExpiredPins(now);
            LandmarkPin existing = storage.getActivePinBySlot(slotIndex, now);
            if (existing != null) {
                activePins.put(slotIndex, existing);
                scheduler.runTask(() -> callback.onResult(null));
                return;
            }

            LandmarkPin pin = new LandmarkPin(
                    slotIndex,
                    landmarkId,
                    buyerUuid,
                    buyerName,
                    Timestamp.from(Instant.now().plusSeconds(Math.max(1L, durationSeconds)))
            );
            pin.setCreatedAt(now);
            LandmarkPin result = storage.addPin(pin);
            if (result != null) {
                activePins.put(slotIndex, result);
                if (plugin.getRedisManager() != null) {
                    plugin.getRedisManager().broadcastLandmarkRefresh();
                }
            }
            scheduler.runTask(() -> callback.onResult(result));
        });
    }

    public void removePinBySlot(int slotIndex, Callback callback) {
        activePins.remove(slotIndex);
        scheduler.runTaskAsync(() -> {
            boolean removed = storage.removePinBySlot(slotIndex);
            if (removed && plugin.getRedisManager() != null) {
                plugin.getRedisManager().broadcastLandmarkRefresh();
            }
            scheduler.runTask(() -> callback.onResult(removed));
        });
    }

    public void deletePinsByLandmark(int landmarkId, Callback callback) {
        clearCachedLandmark(landmarkId);
        scheduler.runTaskAsync(() -> {
            int removed = storage.deletePinsByLandmark(landmarkId);
            if (removed > 0 && plugin.getRedisManager() != null) {
                plugin.getRedisManager().broadcastLandmarkRefresh();
            }
            scheduler.runTask(() -> callback.onResult(removed));
        });
    }

    public void clearCachedLandmark(int landmarkId) {
        activePins.entrySet().removeIf(entry -> entry.getValue().getLandmarkId() == landmarkId);
    }

    public void clearCache() {
        activePins.clear();
    }

    private void cleanupExpiredCache() {
        Timestamp now = now();
        boolean removed = activePins.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        if (removed) cleanupExpiredAsync();
    }

    private void cleanupExpiredAsync() {
        Timestamp now = now();
        scheduler.runTaskAsync(() -> {
            int removed = storage.deleteExpiredPins(now);
            if (removed > 0 && plugin.getRedisManager() != null) {
                plugin.getRedisManager().broadcastLandmarkRefresh();
            }
        });
    }

    private Timestamp now() {
        return new Timestamp(System.currentTimeMillis());
    }

    public interface Callback {
        void onResult(Object result);
    }
}
