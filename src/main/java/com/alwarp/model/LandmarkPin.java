package com.alwarp.model;

import java.sql.Timestamp;
import java.util.UUID;

public class LandmarkPin {

    private int id;
    private int slotIndex;
    private int landmarkId;
    private UUID buyerUuid;
    private String buyerName;
    private Timestamp createdAt;
    private Timestamp expiresAt;

    public LandmarkPin() {
    }

    public LandmarkPin(int slotIndex, int landmarkId, UUID buyerUuid, String buyerName, Timestamp expiresAt) {
        this.slotIndex = slotIndex;
        this.landmarkId = landmarkId;
        this.buyerUuid = buyerUuid;
        this.buyerName = buyerName;
        this.expiresAt = expiresAt;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getSlotIndex() { return slotIndex; }
    public void setSlotIndex(int slotIndex) { this.slotIndex = slotIndex; }

    public int getLandmarkId() { return landmarkId; }
    public void setLandmarkId(int landmarkId) { this.landmarkId = landmarkId; }

    public UUID getBuyerUuid() { return buyerUuid; }
    public void setBuyerUuid(UUID buyerUuid) { this.buyerUuid = buyerUuid; }

    public String getBuyerName() { return buyerName; }
    public void setBuyerName(String buyerName) { this.buyerName = buyerName; }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }

    public Timestamp getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Timestamp expiresAt) { this.expiresAt = expiresAt; }

    public boolean isExpired(Timestamp now) {
        return expiresAt != null && now != null && !expiresAt.after(now);
    }
}
