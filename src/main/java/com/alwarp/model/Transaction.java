package com.alwarp.model;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * 交易记录实体类。
 * 对应数据库 transactions 表。
 */
public class Transaction {

    public enum Type {
        INCOME, EXPENSE
    }

    private int id;
    private int landmarkId;
    private UUID playerUuid;
    private String playerName;
    private double amount;
    private double taxAmount;
    private Type type;
    private Timestamp createdAt;

    public Transaction() {
    }

    public Transaction(int landmarkId, UUID playerUuid, String playerName,
                       double amount, double taxAmount, Type type) {
        this.landmarkId = landmarkId;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.amount = amount;
        this.taxAmount = taxAmount;
        this.type = type;
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

    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }

    public double getTaxAmount() { return taxAmount; }
    public void setTaxAmount(double taxAmount) { this.taxAmount = taxAmount; }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }

    /**
     * 获取实际收入（扣除税费后）。
     */
    public double getNetAmount() {
        return amount - taxAmount;
    }
}
