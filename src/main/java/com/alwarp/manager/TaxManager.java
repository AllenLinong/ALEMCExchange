package com.alwarp.manager;

import com.alwarp.ALwarp;
import com.alwarp.model.Landmark;
import com.alwarp.model.Transaction;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public class TaxManager {

    private final ALwarp plugin;
    private boolean enabled;
    private double defaultRate;
    private double minimumTax;
    private boolean managedLandmarkFreeTeleport;

    public TaxManager(ALwarp plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        this.enabled = plugin.getConfig().getBoolean("tax.enabled", true);
        this.defaultRate = plugin.getConfig().getDouble("tax.rate", 0.1);
        this.minimumTax = plugin.getConfig().getDouble("tax.minimum_tax", 0.01);
        this.managedLandmarkFreeTeleport = plugin.getConfig().getBoolean("economy.managed_landmark_free_teleport",
                plugin.getConfig().getBoolean("economy.owner_free_teleport", false));
    }

    public boolean processPayment(Player player, Landmark landmark) {
        PaymentReceipt payment = reservePayment(player, landmark);
        return payment != null && finalizePayment(player, landmark, payment);
    }

    public PaymentReceipt reservePayment(Player player, Landmark landmark) {
        return reservePayment(player, getPaymentTerms(player, landmark));
    }

    public PaymentTerms getPaymentTerms(Player player, Landmark landmark) {
        if (!shouldCharge(player, landmark)) {
            return PaymentTerms.free();
        }
        return new PaymentTerms(true, landmark.getPrice(), 0.0, landmark.getPrice());
    }

    public PaymentReceipt reservePayment(Player player, PaymentTerms terms) {
        Economy economy = plugin.getEconomy();
        if (economy == null || terms == null || !terms.charged()) {
            return PaymentReceipt.free();
        }

        if (!economy.has(player, terms.price())) {
            return null;
        }

        economy.withdrawPlayer(player, terms.price());
        return new PaymentReceipt(true, terms.price(), 0.0, terms.price(),
                player.getUniqueId(), player.getName());
    }

    public boolean finalizePayment(Player player, Landmark landmark, PaymentReceipt payment) {
        if (payment == null) return false;
        if (!payment.charged()) return true;

        if (!plugin.getStorage().addPendingIncome(landmark.getOwnerUuid(), landmark.getOwnerName(), payment.price())) {
            refundPayment(player, payment);
            return false;
        }
        plugin.getIncomeManager().addCachedPendingIncome(landmark.getOwnerUuid(), payment.price());
        if (plugin.getRedisManager() != null) {
            plugin.getRedisManager().broadcastIncomeRefresh();
        }

        TransactionManager txManager = plugin.getTransactionManager();
        if (txManager != null) {
            txManager.recordTransaction(new Transaction(
                    landmark.getId(), player.getUniqueId(), player.getName(),
                    payment.price(), 0.0, Transaction.Type.INCOME));
        }

        return true;
    }

    public void finalizePaymentAsync(Player player, Landmark landmark, PaymentReceipt payment, PaymentCallback callback) {
        if (callback == null) return;
        if (payment == null || landmark == null) {
            runPaymentCallback(player, () -> callback.onResult(false));
            return;
        }
        if (!payment.charged()) {
            runPaymentCallback(player, () -> callback.onResult(true));
            return;
        }

        UUIDSnapshot snapshot = new UUIDSnapshot(
                landmark.getId(),
                landmark.getOwnerUuid(),
                landmark.getOwnerName(),
                payment.playerUuid(),
                payment.playerName(),
                payment.price());

        plugin.getScheduler().runTaskAsync(() -> {
            boolean success = false;
            try {
                success = plugin.getStorage().addPendingIncome(
                        snapshot.ownerUuid(), snapshot.ownerName(), snapshot.price());
                if (success) {
                    plugin.getIncomeManager().addCachedPendingIncome(snapshot.ownerUuid(), snapshot.price());
                    if (plugin.getRedisManager() != null) {
                        plugin.getRedisManager().broadcastIncomeRefresh();
                    }

                    Transaction transaction = new Transaction(
                            snapshot.landmarkId(), snapshot.playerUuid(), snapshot.playerName(),
                            snapshot.price(), 0.0, Transaction.Type.INCOME);
                    if (plugin.getStorage().createTransaction(transaction) == null) {
                        plugin.getLogger().warning("Failed to record payment transaction for landmark "
                                + snapshot.landmarkId() + ", player " + snapshot.playerName());
                    }
                } else {
                    plugin.getLogger().warning("Failed to add pending income for landmark "
                            + snapshot.landmarkId() + ", owner " + snapshot.ownerName()
                            + ". The player will be refunded.");
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Payment finalization failed for landmark "
                        + snapshot.landmarkId() + ", player " + snapshot.playerName()
                        + ": " + e.getMessage());
            }

            boolean finalSuccess = success;
            runPaymentCallback(player, () -> {
                if (!finalSuccess) {
                    refundPayment(player, payment);
                }
                callback.onResult(finalSuccess);
            });
        });
    }

    private void runPaymentCallback(Player player, Runnable task) {
        if (player != null && player.isOnline()) {
            plugin.runAtPlayer(player, task);
        } else {
            plugin.getScheduler().runTask(task);
        }
    }

    public void refundPayment(Player player, PaymentReceipt payment) {
        if (payment == null || !payment.charged()) return;
        Economy economy = plugin.getEconomy();
        if (economy != null) {
            if (player != null && player.isOnline()) {
                economy.depositPlayer(player, payment.price());
            } else if (payment.playerUuid() != null) {
                OfflinePlayer offlinePlayer = plugin.getServer().getOfflinePlayer(payment.playerUuid());
                economy.depositPlayer(offlinePlayer, payment.price());
            } else if (payment.playerName() != null && !payment.playerName().isBlank()) {
                economy.depositPlayer(payment.playerName(), payment.price());
            }
        }
    }

    public ClaimPreview previewClaim(Player player, double grossAmount) {
        double gross = Math.max(0.0, grossAmount);
        double rate = getPlayerTaxRate(player);
        double tax = calculateTaxByRate(gross, rate);
        return new ClaimPreview(gross, tax, Math.max(0.0, gross - tax), rate);
    }

    public double calculateTax(double amount, Landmark landmark) {
        Player owner = landmark != null ? plugin.getServer().getPlayer(landmark.getOwnerUuid()) : null;
        double rate = owner != null ? getPlayerTaxRate(owner) : getFallbackTaxRate();
        return calculateTaxByRate(amount, rate);
    }

    public double getPlayerTaxRate(Player player) {
        if (!enabled) return 0.0;
        if (player == null) return defaultRate;
        if (player.hasPermission("alwarp.tax.bypass") || player.hasPermission("alwarp.bypass.tax")) return 0.0;

        double minRate = Double.MAX_VALUE;
        for (org.bukkit.permissions.PermissionAttachmentInfo perm : player.getEffectivePermissions()) {
            String node = perm.getPermission();
            if (node.startsWith("alwarp.tax.")) {
                try {
                    double rate = Double.parseDouble(node.substring("alwarp.tax.".length())) / 100.0;
                    if (rate < minRate) minRate = rate;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return minRate != Double.MAX_VALUE ? minRate : defaultRate;
    }

    private double calculateTaxByRate(double amount, double rate) {
        if (!enabled || amount <= 0.0 || rate <= 0.0) return 0.0;
        double tax = amount * rate;
        if (minimumTax > 0.0) {
            tax = Math.max(tax, minimumTax);
        }
        return Math.min(amount, tax);
    }

    private double getFallbackTaxRate() {
        return enabled ? defaultRate : 0.0;
    }

    public boolean shouldCharge(Player player, Landmark landmark) {
        if (player == null || landmark == null || !landmark.isPaid()) return false;
        if (player.isOp() || player.hasPermission("alwarp.free") || player.hasPermission("alwarp.admin")) return false;
        if (managedLandmarkFreeTeleport && isOwnerOrLandmarkManager(player, landmark)) return false;
        return true;
    }

    private boolean isOwnerOrLandmarkManager(Player player, Landmark landmark) {
        if (player.getUniqueId().equals(landmark.getOwnerUuid())) return true;
        return plugin.getManagerManager().isManager(landmark.getId(), player.getUniqueId());
    }

    public boolean isEnabled() { return enabled; }
    public double getDefaultRate() { return defaultRate; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public record PaymentReceipt(
            boolean charged,
            double price,
            double tax,
            double netIncome,
            java.util.UUID playerUuid,
            String playerName) {
        public static PaymentReceipt free() {
            return new PaymentReceipt(false, 0.0, 0.0, 0.0, null, null);
        }
    }

    public record PaymentTerms(boolean charged, double price, double tax, double netIncome) {
        public static PaymentTerms free() {
            return new PaymentTerms(false, 0.0, 0.0, 0.0);
        }
    }

    public record ClaimPreview(double gross, double tax, double net, double rate) {}

    public interface PaymentCallback {
        void onResult(boolean success);
    }

    private record UUIDSnapshot(
            int landmarkId,
            java.util.UUID ownerUuid,
            String ownerName,
            java.util.UUID playerUuid,
            String playerName,
            double price) {}
}
