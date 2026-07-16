package com.alwarp.model;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * 地标管理组成员实体类。
 * 对应数据库 landmark_managers 表。
 */
public class LandmarkAdmin {

    private int id;
    private int landmarkId;
    private UUID playerUuid;
    private String playerName;
    private Timestamp addedAt;

    public LandmarkAdmin() {
    }

    public LandmarkAdmin(int landmarkId, UUID playerUuid, String playerName) {
        this.landmarkId = landmarkId;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
    }

    // ─── Getters and Setters ───

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getLandmarkId() { return landmarkId; }
    public void setLandmarkId(int landmarkId) { this.landmarkId = landmarkId; }

    public UUID getPlayerUuid() { return playerUuid; }
    public void setPlayerUuid(UUID playerUuid) { this.playerUuid = playerUuid; }

    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }

    public Timestamp getAddedAt() { return addedAt; }
    public void setAddedAt(Timestamp addedAt) { this.addedAt = addedAt; }
}
