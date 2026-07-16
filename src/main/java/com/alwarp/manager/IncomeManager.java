package com.alwarp.manager;

import com.alwarp.ALwarp;
import com.alwarp.scheduler.FoliaScheduler;
import com.alwarp.storage.StorageManager;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class IncomeManager {

    private final StorageManager storage;
    private final FoliaScheduler scheduler;
    private final ConcurrentMap<UUID, Double> pendingIncome = new ConcurrentHashMap<>();

    public IncomeManager(ALwarp plugin, StorageManager storage, FoliaScheduler scheduler) {
        this.storage = storage;
        this.scheduler = scheduler;
    }

    public void loadAllAsync(Runnable callback) {
        scheduler.runTaskAsync(() -> {
            pendingIncome.clear();
            for (StorageManager.IncomeSnapshot snapshot : storage.getAllIncomeSnapshots()) {
                setCachedPendingIncome(snapshot.ownerUuid(), snapshot.amount());
            }
            if (callback != null) {
                scheduler.runTask(callback);
            }
        });
    }

    public void refreshAsync(UUID ownerUuid, Runnable callback) {
        if (ownerUuid == null) return;
        scheduler.runTaskAsync(() -> {
            setCachedPendingIncome(ownerUuid, storage.getPendingIncome(ownerUuid));
            if (callback != null) {
                scheduler.runTask(callback);
            }
        });
    }

    public double getCachedPendingIncome(UUID ownerUuid) {
        if (ownerUuid == null) return 0.0;
        return pendingIncome.getOrDefault(ownerUuid, 0.0);
    }

    public void addCachedPendingIncome(UUID ownerUuid, double amount) {
        if (ownerUuid == null || !isUsableAmount(amount)) return;
        pendingIncome.merge(ownerUuid, amount, Double::sum);
    }

    public void setCachedPendingIncome(UUID ownerUuid, double amount) {
        if (ownerUuid == null) return;
        if (!isUsableAmount(amount)) {
            pendingIncome.remove(ownerUuid);
            return;
        }
        pendingIncome.put(ownerUuid, amount);
    }

    private boolean isUsableAmount(double amount) {
        return amount > 0.0 && !Double.isNaN(amount) && !Double.isInfinite(amount);
    }
}
