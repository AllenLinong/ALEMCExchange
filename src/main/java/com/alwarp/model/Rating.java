package com.alwarp.model;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * 评分实体类。
 * 对应数据库 ratings 表。
 */
public class Rating {

    private int id;
    private int landmarkId;
    private UUID playerUuid;
    private String playerName;
    private int score;
    private String comment;
    private Timestamp createdAt;

    public Rating() {
    }

    public Rating(int landmarkId, UUID playerUuid, String playerName, int score, String comment) {
        this.landmarkId = landmarkId;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.score = score;
        this.comment = comment;
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

    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
}
