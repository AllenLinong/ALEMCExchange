package com.alwarp.storage;

import com.alwarp.model.*;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 数据存储接口。
 * 定义所有CRUD操作，由SQLiteStorage和MySQLStorage实现。
 */
public interface StorageManager {

    record RatingStats(int count, double average) {}

    record FavoriteSnapshot(UUID playerUuid, int landmarkId, Timestamp createdAt) {}

    record IncomeSnapshot(UUID ownerUuid, String ownerName, double amount, Timestamp updatedAt) {}

    record PlayerRecord(UUID playerUuid, String playerName) {}

    record PlayerSkin(UUID playerUuid, String playerName, String textureValue, String textureSignature) {}

    void init();

    void close();

    // ─── 地标操作 ───

    Landmark createLandmark(Landmark landmark);

    Landmark importLandmarkSnapshot(Landmark landmark);

    int getNextAvailableLandmarkId();

    boolean updateLandmark(Landmark landmark);

    boolean deleteLandmark(int id);

    int deleteLandmarksByOwner(UUID ownerUuid);

    Landmark getLandmarkById(int id);

    List<Landmark> getLandmarksByOwner(UUID ownerUuid);

    List<Landmark> getLandmarksByCategory(int categoryId);

    List<Landmark> getAllLandmarks();

    List<Landmark> getLandmarksSortedByPopularity(int limit, int offset);

    List<Landmark> getLandmarksByServer(String serverName);

    List<Landmark> searchLandmarks(String query);

    int getLandmarkCount();

    void incrementVisitCount(int landmarkId);

    void adjustLandmarkHeat(int landmarkId, int delta);

    void recordLandmarkVisit(UUID playerUuid, int landmarkId);

    boolean hasVisitedLandmark(UUID playerUuid, int landmarkId);

    // ─── 收藏操作 ───

    boolean addFavorite(UUID playerUuid, int landmarkId);

    boolean importFavoriteSnapshot(FavoriteSnapshot favorite);

    boolean removeFavorite(UUID playerUuid, int landmarkId);

    int removeFavoritesByPlayer(UUID playerUuid);

    int removeFavoritesByLandmark(int landmarkId);

    List<Integer> getFavorites(UUID playerUuid);

    Map<UUID, List<Integer>> getAllFavorites();

    List<FavoriteSnapshot> getAllFavoriteSnapshots();

    // ─── 分类操作 ───

    Category createCategory(Category category);

    boolean updateCategory(Category category);

    boolean deleteCategory(int id);

    Category getCategoryById(int id);

    Category getCategoryByName(String name);

    List<Category> getAllCategories();

    // ─── 评分操作 ───

    Rating addRating(Rating rating);

    boolean updateRating(Rating rating);

    boolean deleteRating(int id);

    int deleteRatingsByPlayer(UUID playerUuid);

    int deleteRatingsByLandmark(int landmarkId);

    Rating getRatingByPlayer(int landmarkId, UUID playerUuid);

    List<Rating> getRatingsByLandmark(int landmarkId);

    double getAverageRating(int landmarkId);

    int getRatingCount(int landmarkId);

    Map<Integer, RatingStats> getAllRatingStats();

    boolean addRatingReport(int ratingId, UUID playerUuid);

    int getRatingReportCount(int ratingId);

    // ─── 交易操作 ───

    Transaction createTransaction(Transaction transaction);

    int deleteTransactionsByLandmark(int landmarkId);

    List<Transaction> getTransactionsByLandmark(int landmarkId);

    List<Transaction> getTransactionsByPlayer(UUID playerUuid);

    boolean addPendingIncome(UUID ownerUuid, String ownerName, double amount);

    double getPendingIncome(UUID ownerUuid);

    double claimPendingIncome(UUID ownerUuid);

    List<IncomeSnapshot> getAllIncomeSnapshots();

    boolean importIncomeSnapshot(IncomeSnapshot income);

    // ─── 管理组操作 ───

    LandmarkAdmin addManager(LandmarkAdmin admin);

    boolean removeManager(int landmarkId, UUID playerUuid);

    List<LandmarkAdmin> getManagersByLandmark(int landmarkId);

    boolean isManager(int landmarkId, UUID playerUuid);

    List<Integer> getManagedLandmarkIds(UUID playerUuid);

    void deleteAllManagers(int landmarkId);

    LandmarkBlacklist addBlacklist(LandmarkBlacklist blacklist);

    boolean removeBlacklist(int landmarkId, UUID playerUuid);

    List<LandmarkBlacklist> getBlacklistByLandmark(int landmarkId);

    boolean isBlacklisted(int landmarkId, UUID playerUuid);

    void deleteAllBlacklists(int landmarkId);

    LandmarkPin addPin(LandmarkPin pin);

    LandmarkPin importPinSnapshot(LandmarkPin pin);

    boolean removePinBySlot(int slotIndex);

    int deleteExpiredPins(Timestamp now);

    int deletePinsByLandmark(int landmarkId);

    void deleteAllPins();

    LandmarkPin getActivePinBySlot(int slotIndex, Timestamp now);

    List<LandmarkPin> getActivePins(Timestamp now);

    List<LandmarkPin> getAllPinSnapshots();

    default void upsertPlayerRecord(UUID playerUuid, String playerName) {
        upsertPlayerRecord(playerUuid, playerName, null, null);
    }

    void upsertPlayerRecord(UUID playerUuid, String playerName, String textureValue, String textureSignature);

    PlayerRecord getPlayerRecordByName(String playerName);

    List<PlayerSkin> getRecentPlayerSkins(int limit);

}
