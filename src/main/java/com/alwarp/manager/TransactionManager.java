package com.alwarp.manager;

import com.alwarp.ALwarp;
import com.alwarp.model.Transaction;
import com.alwarp.scheduler.FoliaScheduler;
import com.alwarp.storage.StorageManager;

import java.util.List;
import java.util.UUID;

/**
 * 交易记录管理器。
 * 管理交易记录的创建和查询。
 */
public class TransactionManager {

    private final ALwarp plugin;
    private final StorageManager storage;
    private final FoliaScheduler scheduler;

    public TransactionManager(ALwarp plugin, StorageManager storage, FoliaScheduler scheduler) {
        this.plugin = plugin;
        this.storage = storage;
        this.scheduler = scheduler;
    }

    /**
     * 异步记录交易。
     */
    public void recordTransaction(Transaction transaction) {
        scheduler.runTaskAsync(() -> storage.createTransaction(transaction));
    }

    /**
     * 查询地标的交易记录。
     */
    public void getTransactionsByLandmark(int landmarkId, Callback callback) {
        scheduler.runTaskAsync(() -> {
            List<Transaction> list = storage.getTransactionsByLandmark(landmarkId);
            scheduler.runTask(() -> callback.onResult(list));
        });
    }

    /**
     * 查询玩家的交易记录。
     */
    public void getTransactionsByPlayer(UUID playerUuid, Callback callback) {
        scheduler.runTaskAsync(() -> {
            List<Transaction> list = storage.getTransactionsByPlayer(playerUuid);
            scheduler.runTask(() -> callback.onResult(list));
        });
    }

    public interface Callback {
        void onResult(Object result);
    }
}
