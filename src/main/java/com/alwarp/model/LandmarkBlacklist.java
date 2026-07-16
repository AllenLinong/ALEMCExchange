package com.alwarp.model;

import java.sql.Timestamp;
import java.util.UUID;

public class LandmarkBlacklist {

    private int id;
    private int landmarkId;
    private UUID playerUuid;
    private String playerName;
    private Timestamp addedAt;

    public LandmarkBlacklist() {
    }

    public LandmarkBlacklist(int landmarkId, UUID playerUuid, String playerName) {
        this.landmarkId = landmarkId;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
    }

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
