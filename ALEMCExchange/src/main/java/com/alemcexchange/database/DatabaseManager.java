package com.alemcexchange.database;

import com.alemcexchange.config.ConfigManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.util.*;

public class DatabaseManager {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private Connection connection;
    private HikariDataSource dataSource;
    private boolean isMySQL = false;
    private String tablePrefix = "";

    public DatabaseManager(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    public void initialize() {
        try {
            connect();
            createTables();
        } catch (SQLException | ClassNotFoundException e) {
            plugin.getLogger().severe("Failed to initialize database: " + e.getMessage());
        }
    }

    private void connect() throws SQLException, ClassNotFoundException {
        String databaseType = configManager.getConfig().getString("database.type", "sqlite");
        // 读取表名前缀配置
        tablePrefix = configManager.getConfig().getString("database.table_prefix", "");

        if (databaseType.equalsIgnoreCase("mysql")) {
            isMySQL = true;
            String host = configManager.getConfig().getString("database.mysql.host", "localhost");
            int port = configManager.getConfig().getInt("database.mysql.port", 3306);
            String database = configManager.getConfig().getString("database.mysql.database", "minecraft");
            String user = configManager.getConfig().getString("database.mysql.user", "root");
            String password = configManager.getConfig().getString("database.mysql.password", "");

            // 配置 HikariCP 连接池
            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setDataSourceClassName("com.mysql.cj.jdbc.MysqlDataSource");
            hikariConfig.addDataSourceProperty("serverName", host);
            hikariConfig.addDataSourceProperty("port", port);
            hikariConfig.addDataSourceProperty("databaseName", database);
            hikariConfig.addDataSourceProperty("user", user);
            hikariConfig.addDataSourceProperty("password", password);
            
            // 从配置文件读取连接参数
            String connectionParams = configManager.getConfig().getString("database.mysql.connection-parameters", "?autoReconnect=true&useSSL=false&useUnicode=true&characterEncoding=UTF-8");
            hikariConfig.addDataSourceProperty("url", "jdbc:mysql://" + host + ":" + port + "/" + database + connectionParams);
            
            // 从配置文件读取连接池设置
            int maxPoolSize = configManager.getConfig().getInt("database.mysql.pool-settings.max-pool-size", 10);
            int minIdle = configManager.getConfig().getInt("database.mysql.pool-settings.min-idle", 5);
            long maxLifetime = configManager.getConfig().getLong("database.mysql.pool-settings.max-lifetime", 1800000);
            long keepAliveTime = configManager.getConfig().getLong("database.mysql.pool-settings.keep-alive-time", 60000);
            long connectionTimeout = configManager.getConfig().getLong("database.mysql.pool-settings.time-out", 30000);
            
            // 连接池配置
            hikariConfig.setMaximumPoolSize(maxPoolSize);
            hikariConfig.setMinimumIdle(minIdle);
            hikariConfig.setConnectionTimeout(connectionTimeout);
            hikariConfig.setIdleTimeout(600000);
            hikariConfig.setMaxLifetime(maxLifetime);
            hikariConfig.setKeepaliveTime(keepAliveTime);
            
            dataSource = new HikariDataSource(hikariConfig);
            connection = dataSource.getConnection();
        } else {
            Class.forName("org.sqlite.JDBC");
            String sqliteFile = configManager.getConfig().getString("database.sqlite.file", "alembc.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + plugin.getDataFolder() + "/" + sqliteFile);
        }

        plugin.getLogger().info("Database connected successfully!");
    }

    private void createTables() throws SQLException, ClassNotFoundException {
        // 创建玩家表
        String createPlayerTable = "CREATE TABLE IF NOT EXISTS " + tablePrefix + "players " +
                "(uuid VARCHAR(36) PRIMARY KEY, emc_balance DOUBLE, autosell_enabled BOOLEAN DEFAULT 0)";

        // 创建挖掘进度表
        String createMineProgressTable = "CREATE TABLE IF NOT EXISTS " + tablePrefix + "player_mine_progress " +
                "(uuid VARCHAR(36), material VARCHAR(64), progress INT, " +
                "PRIMARY KEY (uuid, material), FOREIGN KEY (uuid) REFERENCES " + tablePrefix + "players(uuid))";

        // 创建出售进度表
        String createSellProgressTable = "CREATE TABLE IF NOT EXISTS " + tablePrefix + "player_sell_progress " +
                "(uuid VARCHAR(36), material VARCHAR(64), progress INT, " +
                "PRIMARY KEY (uuid, material), FOREIGN KEY (uuid) REFERENCES " + tablePrefix + "players(uuid))";

        // 创建解锁物品表
        String createUnlockedItemsTable = "CREATE TABLE IF NOT EXISTS " + tablePrefix + "unlocked_items " +
                "(uuid VARCHAR(36), material VARCHAR(64), " +
                "PRIMARY KEY (uuid, material), FOREIGN KEY (uuid) REFERENCES " + tablePrefix + "players(uuid))";

        Connection conn = getConnection();
        Statement stmt = conn.createStatement();
        try {
            stmt.execute(createPlayerTable);
            stmt.execute(createMineProgressTable);
            stmt.execute(createSellProgressTable);
            stmt.execute(createUnlockedItemsTable);
        } finally {
            if (stmt != null) {
                stmt.close();
            }
        }

        plugin.getLogger().info("Database tables created successfully!");
    }

    private Connection getConnection() throws SQLException, ClassNotFoundException {
        if (dataSource != null) {
            // MySQL 连接，从连接池获取
            return dataSource.getConnection();
        } else {
            // 检查 SQLite 连接是否有效
            if (connection == null || connection.isClosed()) {
                // 重新连接
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection("jdbc:sqlite:" + plugin.getDataFolder() + "/alembc.db");
                plugin.getLogger().info("SQLite connection established!");
            }
            return connection;
        }
    }

    public void ensurePlayerExists(UUID uuid) throws SQLException, ClassNotFoundException {
        String checkPlayer = "SELECT * FROM " + tablePrefix + "players WHERE uuid = ?";
        String insertPlayer = "INSERT INTO " + tablePrefix + "players (uuid, emc_balance, autosell_enabled) VALUES (?, 0, 0)";

        if (isMySQL) {
            // MySQL 连接，使用 try-with-resources 确保连接归还到连接池
            try (Connection conn = getConnection();
                 PreparedStatement checkStmt = conn.prepareStatement(checkPlayer)) {
                checkStmt.setString(1, uuid.toString());
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (!rs.next()) {
                        try (PreparedStatement insertStmt = conn.prepareStatement(insertPlayer)) {
                            insertStmt.setString(1, uuid.toString());
                            insertStmt.executeUpdate();
                        }
                    }
                }
            }
        } else {
            // SQLite 连接，保持长连接
            Connection conn = getConnection();
            PreparedStatement checkStmt = conn.prepareStatement(checkPlayer);
            try {
                checkStmt.setString(1, uuid.toString());
                ResultSet rs = checkStmt.executeQuery();
                try {
                    if (!rs.next()) {
                        PreparedStatement insertStmt = conn.prepareStatement(insertPlayer);
                        try {
                            insertStmt.setString(1, uuid.toString());
                            insertStmt.executeUpdate();
                        } finally {
                            if (insertStmt != null) {
                                insertStmt.close();
                            }
                        }
                    }
                } finally {
                    if (rs != null) {
                        rs.close();
                    }
                }
            } finally {
                if (checkStmt != null) {
                    checkStmt.close();
                }
            }
        }
    }

    public double getEMCBalance(UUID uuid) throws SQLException, ClassNotFoundException {
        ensurePlayerExists(uuid);

        String query = "SELECT emc_balance FROM " + tablePrefix + "players WHERE uuid = ?";

        if (isMySQL) {
            // MySQL 连接，使用 try-with-resources 确保连接归还到连接池
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble("emc_balance");
                    }
                }
            }
        } else {
            // SQLite 连接，保持长连接
            Connection conn = getConnection();
            PreparedStatement stmt = conn.prepareStatement(query);
            try {
                stmt.setString(1, uuid.toString());
                ResultSet rs = stmt.executeQuery();
                try {
                    if (rs.next()) {
                        return rs.getDouble("emc_balance");
                    }
                } finally {
                    if (rs != null) {
                        rs.close();
                    }
                }
            } finally {
                if (stmt != null) {
                    stmt.close();
                }
            }
        }

        return 0.0;
    }

    public void setEMCBalance(UUID uuid, double balance) throws SQLException, ClassNotFoundException {
        ensurePlayerExists(uuid);

        String query = "UPDATE " + tablePrefix + "players SET emc_balance = ? WHERE uuid = ?";

        if (isMySQL) {
            // MySQL 连接，使用 try-with-resources 确保连接归还到连接池
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setDouble(1, balance);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            }
        } else {
            // SQLite 连接，保持长连接
            Connection conn = getConnection();
            PreparedStatement stmt = conn.prepareStatement(query);
            try {
                stmt.setDouble(1, balance);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            } finally {
                if (stmt != null) {
                    stmt.close();
                }
            }
        }
    }

    public void addEMCBalance(UUID uuid, double amount) throws SQLException, ClassNotFoundException {
        ensurePlayerExists(uuid);

        String query = "UPDATE " + tablePrefix + "players SET emc_balance = emc_balance + ? WHERE uuid = ?";

        if (isMySQL) {
            // MySQL 连接，使用 try-with-resources 确保连接归还到连接池
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setDouble(1, amount);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            }
        } else {
            // SQLite 连接，保持长连接
            Connection conn = getConnection();
            PreparedStatement stmt = conn.prepareStatement(query);
            try {
                stmt.setDouble(1, amount);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            } finally {
                if (stmt != null) {
                    stmt.close();
                }
            }
        }
    }

    public boolean hasSufficientEMC(UUID uuid, double amount) throws SQLException, ClassNotFoundException {
        return getEMCBalance(uuid) >= amount;
    }

    public int getMineProgress(UUID uuid, String material) throws SQLException, ClassNotFoundException {
        ensurePlayerExists(uuid);

        String query = "SELECT progress FROM " + tablePrefix + "player_mine_progress WHERE uuid = ? AND material = ?";

        if (isMySQL) {
            // MySQL 连接，使用 try-with-resources 确保连接归还到连接池
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, material);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("progress");
                    }
                }
            }
        } else {
            // SQLite 连接，保持长连接
            Connection conn = getConnection();
            PreparedStatement stmt = conn.prepareStatement(query);
            try {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, material);
                ResultSet rs = stmt.executeQuery();
                try {
                    if (rs.next()) {
                        return rs.getInt("progress");
                    }
                } finally {
                    if (rs != null) {
                        rs.close();
                    }
                }
            } finally {
                if (stmt != null) {
                    stmt.close();
                }
            }
        }

        return 0;
    }

    public void setMineProgress(UUID uuid, String material, int progress) throws SQLException, ClassNotFoundException {
        ensurePlayerExists(uuid);

        String checkQuery = "SELECT progress FROM " + tablePrefix + "player_mine_progress WHERE uuid = ? AND material = ?";
        String insertQuery = "INSERT INTO " + tablePrefix + "player_mine_progress (uuid, material, progress) VALUES (?, ?, ?)";
        String updateQuery = "UPDATE " + tablePrefix + "player_mine_progress SET progress = ? WHERE uuid = ? AND material = ?";

        if (isMySQL) {
            // MySQL 连接，使用 try-with-resources 确保连接归还到连接池
            try (Connection conn = getConnection();
                 PreparedStatement checkStmt = conn.prepareStatement(checkQuery)) {
                checkStmt.setString(1, uuid.toString());
                checkStmt.setString(2, material);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (rs.next()) {
                        try (PreparedStatement updateStmt = conn.prepareStatement(updateQuery)) {
                            updateStmt.setInt(1, progress);
                            updateStmt.setString(2, uuid.toString());
                            updateStmt.setString(3, material);
                            updateStmt.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement insertStmt = conn.prepareStatement(insertQuery)) {
                            insertStmt.setString(1, uuid.toString());
                            insertStmt.setString(2, material);
                            insertStmt.setInt(3, progress);
                            insertStmt.executeUpdate();
                        }
                    }
                }
            }
        } else {
            // SQLite 连接，保持长连接
            Connection conn = getConnection();
            PreparedStatement checkStmt = conn.prepareStatement(checkQuery);
            try {
                checkStmt.setString(1, uuid.toString());
                checkStmt.setString(2, material);
                ResultSet rs = checkStmt.executeQuery();
                try {
                    if (rs.next()) {
                        PreparedStatement updateStmt = conn.prepareStatement(updateQuery);
                        try {
                            updateStmt.setInt(1, progress);
                            updateStmt.setString(2, uuid.toString());
                            updateStmt.setString(3, material);
                            updateStmt.executeUpdate();
                        } finally {
                            if (updateStmt != null) {
                                updateStmt.close();
                            }
                        }
                    } else {
                        PreparedStatement insertStmt = conn.prepareStatement(insertQuery);
                        try {
                            insertStmt.setString(1, uuid.toString());
                            insertStmt.setString(2, material);
                            insertStmt.setInt(3, progress);
                            insertStmt.executeUpdate();
                        } finally {
                            if (insertStmt != null) {
                                insertStmt.close();
                            }
                        }
                    }
                } finally {
                    if (rs != null) {
                        rs.close();
                    }
                }
            } finally {
                if (checkStmt != null) {
                    checkStmt.close();
                }
            }
        }
    }

    public void addMineProgress(UUID uuid, String material, int amount) throws SQLException, ClassNotFoundException {
        // 如果物品已解锁，不再记录进度
        if (isUnlocked(uuid, material)) {
            return;
        }
        
        ensurePlayerExists(uuid);
        
        String query;
        if (isMySQL) {
            query = "INSERT INTO " + tablePrefix + "player_mine_progress (uuid, material, progress) VALUES (?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE progress = progress + ?";
        } else {
            query = "INSERT INTO " + tablePrefix + "player_mine_progress (uuid, material, progress) VALUES (?, ?, ?) " +
                    "ON CONFLICT(uuid, material) DO UPDATE SET progress = progress + ?";
        }
        
        if (isMySQL) {
            // MySQL 连接，使用 try-with-resources 确保连接归还到连接池
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, material);
                stmt.setInt(3, amount);
                stmt.setInt(4, amount);
                stmt.executeUpdate();
            }
        } else {
            // SQLite 连接，保持长连接
            Connection conn = getConnection();
            PreparedStatement stmt = conn.prepareStatement(query);
            try {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, material);
                stmt.setInt(3, amount);
                stmt.setInt(4, amount);
                stmt.executeUpdate();
            } finally {
                if (stmt != null) {
                    stmt.close();
                }
            }
        }
    }

    public int getSellProgress(UUID uuid, String material) throws SQLException, ClassNotFoundException {
        ensurePlayerExists(uuid);

        String query = "SELECT progress FROM " + tablePrefix + "player_sell_progress WHERE uuid = ? AND material = ?";

        if (isMySQL) {
            // MySQL 连接，使用 try-with-resources 确保连接归还到连接池
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, material);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("progress");
                    }
                }
            }
        } else {
            // SQLite 连接，保持长连接
            Connection conn = getConnection();
            PreparedStatement stmt = conn.prepareStatement(query);
            try {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, material);
                ResultSet rs = stmt.executeQuery();
                try {
                    if (rs.next()) {
                        return rs.getInt("progress");
                    }
                } finally {
                    if (rs != null) {
                        rs.close();
                    }
                }
            } finally {
                if (stmt != null) {
                    stmt.close();
                }
            }
        }

        return 0;
    }

    public void setSellProgress(UUID uuid, String material, int progress) throws SQLException, ClassNotFoundException {
        ensurePlayerExists(uuid);

        String checkQuery = "SELECT progress FROM " + tablePrefix + "player_sell_progress WHERE uuid = ? AND material = ?";
        String insertQuery = "INSERT INTO " + tablePrefix + "player_sell_progress (uuid, material, progress) VALUES (?, ?, ?)";
        String updateQuery = "UPDATE " + tablePrefix + "player_sell_progress SET progress = ? WHERE uuid = ? AND material = ?";

        if (isMySQL) {
            // MySQL 连接，使用 try-with-resources 确保连接归还到连接池
            try (Connection conn = getConnection();
                 PreparedStatement checkStmt = conn.prepareStatement(checkQuery)) {
                checkStmt.setString(1, uuid.toString());
                checkStmt.setString(2, material);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (rs.next()) {
                        try (PreparedStatement updateStmt = conn.prepareStatement(updateQuery)) {
                            updateStmt.setInt(1, progress);
                            updateStmt.setString(2, uuid.toString());
                            updateStmt.setString(3, material);
                            updateStmt.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement insertStmt = conn.prepareStatement(insertQuery)) {
                            insertStmt.setString(1, uuid.toString());
                            insertStmt.setString(2, material);
                            insertStmt.setInt(3, progress);
                            insertStmt.executeUpdate();
                        }
                    }
                }
            }
        } else {
            // SQLite 连接，保持长连接
            Connection conn = getConnection();
            PreparedStatement checkStmt = conn.prepareStatement(checkQuery);
            try {
                checkStmt.setString(1, uuid.toString());
                checkStmt.setString(2, material);
                ResultSet rs = checkStmt.executeQuery();
                try {
                    if (rs.next()) {
                        PreparedStatement updateStmt = conn.prepareStatement(updateQuery);
                        try {
                            updateStmt.setInt(1, progress);
                            updateStmt.setString(2, uuid.toString());
                            updateStmt.setString(3, material);
                            updateStmt.executeUpdate();
                        } finally {
                            if (updateStmt != null) {
                                updateStmt.close();
                            }
                        }
                    } else {
                        PreparedStatement insertStmt = conn.prepareStatement(insertQuery);
                        try {
                            insertStmt.setString(1, uuid.toString());
                            insertStmt.setString(2, material);
                            insertStmt.setInt(3, progress);
                            insertStmt.executeUpdate();
                        } finally {
                            if (insertStmt != null) {
                                insertStmt.close();
                            }
                        }
                    }
                } finally {
                    if (rs != null) {
                        rs.close();
                    }
                }
            } finally {
                if (checkStmt != null) {
                    checkStmt.close();
                }
            }
        }
    }

    public void addSellProgress(UUID uuid, String material, int amount) throws SQLException, ClassNotFoundException {
        // 如果物品已解锁，不再记录进度
        if (isUnlocked(uuid, material)) {
            return;
        }
        
        ensurePlayerExists(uuid);
        
        String query;
        if (isMySQL) {
            query = "INSERT INTO " + tablePrefix + "player_sell_progress (uuid, material, progress) VALUES (?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE progress = progress + ?";
        } else {
            query = "INSERT INTO " + tablePrefix + "player_sell_progress (uuid, material, progress) VALUES (?, ?, ?) " +
                    "ON CONFLICT(uuid, material) DO UPDATE SET progress = progress + ?";
        }
        
        if (isMySQL) {
            // MySQL 连接，使用 try-with-resources 确保连接归还到连接池
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, material);
                stmt.setInt(3, amount);
                stmt.setInt(4, amount);
                stmt.executeUpdate();
            }
        } else {
            // SQLite 连接，保持长连接
            Connection conn = getConnection();
            PreparedStatement stmt = conn.prepareStatement(query);
            try {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, material);
                stmt.setInt(3, amount);
                stmt.setInt(4, amount);
                stmt.executeUpdate();
            } finally {
                if (stmt != null) {
                    stmt.close();
                }
            }
        }
    }

    public boolean isUnlocked(UUID uuid, String material) throws SQLException, ClassNotFoundException {
        ensurePlayerExists(uuid);

        String query = "SELECT * FROM " + tablePrefix + "unlocked_items WHERE uuid = ? AND material = ?";

        if (isMySQL) {
            // MySQL 连接，使用 try-with-resources 确保连接归还到连接池
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, material);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next();
                }
            }
        } else {
            // SQLite 连接，保持长连接
            Connection conn = getConnection();
            PreparedStatement stmt = conn.prepareStatement(query);
            try {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, material);
                ResultSet rs = stmt.executeQuery();
                try {
                    return rs.next();
                } finally {
                    if (rs != null) {
                        rs.close();
                    }
                }
            } finally {
                if (stmt != null) {
                    stmt.close();
                }
            }
        }
    }

    public void unlockItem(UUID uuid, String material) throws SQLException, ClassNotFoundException {
        ensurePlayerExists(uuid);

        if (!isUnlocked(uuid, material)) {
            String query = "INSERT INTO " + tablePrefix + "unlocked_items (uuid, material) VALUES (?, ?)";
            
            if (isMySQL) {
                // MySQL 连接，使用 try-with-resources 确保连接归还到连接池
                try (Connection conn = getConnection();
                     PreparedStatement stmt = conn.prepareStatement(query)) {
                    stmt.setString(1, uuid.toString());
                    stmt.setString(2, material);
                    stmt.executeUpdate();
                }
            } else {
                // SQLite 连接，保持长连接
                Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(query);
                try {
                    stmt.setString(1, uuid.toString());
                    stmt.setString(2, material);
                    stmt.executeUpdate();
                } finally {
                    if (stmt != null) {
                        stmt.close();
                    }
                }
            }
        }
    }

    public void unlockAllItems(UUID uuid) throws SQLException, ClassNotFoundException {
        ensurePlayerExists(uuid);

        // 首先删除已有的解锁记录
        String deleteQuery = "DELETE FROM " + tablePrefix + "unlocked_items WHERE uuid = ?";
        
        if (isMySQL) {
            // MySQL 连接，使用 try-with-resources 确保连接归还到连接池
            try (Connection conn = getConnection();
                 PreparedStatement deleteStmt = conn.prepareStatement(deleteQuery)) {
                deleteStmt.setString(1, uuid.toString());
                deleteStmt.executeUpdate();

                // 使用批量插入来提高性能
                if (configManager.getItems().contains("items")) {
                    Set<String> materialNames = configManager.getItems().getConfigurationSection("items").getKeys(false);
                    if (materialNames.isEmpty()) {
                        return;
                    }

                    // 构建批量插入SQL
                    StringBuilder insertQueryBuilder = new StringBuilder("INSERT INTO " + tablePrefix + "unlocked_items (uuid, material) VALUES ");
                    for (int i = 0; i < materialNames.size(); i++) {
                        if (i > 0) {
                            insertQueryBuilder.append(", ");
                        }
                        insertQueryBuilder.append("(?, ?)");
                    }

                    try (PreparedStatement insertStmt = conn.prepareStatement(insertQueryBuilder.toString())) {
                        int paramIndex = 1;
                        for (String materialName : materialNames) {
                            insertStmt.setString(paramIndex++, uuid.toString());
                            insertStmt.setString(paramIndex++, materialName);
                        }
                        insertStmt.executeUpdate();
                    }
                }
            }
        } else {
            // SQLite 连接，保持长连接
            Connection conn = getConnection();
            PreparedStatement deleteStmt = conn.prepareStatement(deleteQuery);
            try {
                deleteStmt.setString(1, uuid.toString());
                deleteStmt.executeUpdate();

                // 使用批量插入来提高性能
                if (configManager.getItems().contains("items")) {
                    Set<String> materialNames = configManager.getItems().getConfigurationSection("items").getKeys(false);
                    if (materialNames.isEmpty()) {
                        return;
                    }

                    // 构建批量插入SQL
                    StringBuilder insertQueryBuilder = new StringBuilder("INSERT INTO " + tablePrefix + "unlocked_items (uuid, material) VALUES ");
                    for (int i = 0; i < materialNames.size(); i++) {
                        if (i > 0) {
                            insertQueryBuilder.append(", ");
                        }
                        insertQueryBuilder.append("(?, ?)");
                    }

                    PreparedStatement insertStmt = conn.prepareStatement(insertQueryBuilder.toString());
                    try {
                        int paramIndex = 1;
                        for (String materialName : materialNames) {
                            insertStmt.setString(paramIndex++, uuid.toString());
                            insertStmt.setString(paramIndex++, materialName);
                        }
                        insertStmt.executeUpdate();
                    } finally {
                        if (insertStmt != null) {
                            insertStmt.close();
                        }
                    }
                }
            } finally {
                if (deleteStmt != null) {
                    deleteStmt.close();
                }
            }
        }
    }

    public boolean isAutoSellEnabled(UUID uuid) throws SQLException, ClassNotFoundException {
        ensurePlayerExists(uuid);

        String query = "SELECT autosell_enabled FROM " + tablePrefix + "players WHERE uuid = ?";
        
        if (isMySQL) {
            // MySQL 连接，使用 try-with-resources 确保连接归还到连接池
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getBoolean("autosell_enabled");
                    }
                }
            }
        } else {
            // SQLite 连接，保持长连接
            Connection conn = getConnection();
            PreparedStatement stmt = conn.prepareStatement(query);
            try {
                stmt.setString(1, uuid.toString());
                ResultSet rs = stmt.executeQuery();
                try {
                    if (rs.next()) {
                        return rs.getBoolean("autosell_enabled");
                    }
                } finally {
                    if (rs != null) {
                        rs.close();
                    }
                }
            } finally {
                if (stmt != null) {
                    stmt.close();
                }
            }
        }
        return false;
    }

    public void setAutoSellEnabled(UUID uuid, boolean enabled) throws SQLException, ClassNotFoundException {
        ensurePlayerExists(uuid);

        String query = "UPDATE " + tablePrefix + "players SET autosell_enabled = ? WHERE uuid = ?";
        
        if (isMySQL) {
            // MySQL 连接，使用 try-with-resources 确保连接归还到连接池
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setBoolean(1, enabled);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            }
        } else {
            // SQLite 连接，保持长连接
            Connection conn = getConnection();
            PreparedStatement stmt = conn.prepareStatement(query);
            try {
                stmt.setBoolean(1, enabled);
                stmt.setString(2, uuid.toString());
                stmt.executeUpdate();
            } finally {
                if (stmt != null) {
                    stmt.close();
                }
            }
        }
    }

    // 批量获取多个物品的解锁状态
    public Map<String, Boolean> getUnlockedStatuses(UUID uuid, List<String> materials) throws SQLException, ClassNotFoundException {
        ensurePlayerExists(uuid);
        Map<String, Boolean> statuses = new HashMap<>();
        
        if (materials.isEmpty()) {
            return statuses;
        }
        
        StringBuilder queryBuilder = new StringBuilder("SELECT material FROM " + tablePrefix + "unlocked_items WHERE uuid = ? AND material IN (");
        for (int i = 0; i < materials.size(); i++) {
            if (i > 0) queryBuilder.append(", ");
            queryBuilder.append("?");
        }
        queryBuilder.append(")");
        
        if (isMySQL) {
            // MySQL 连接，使用 try-with-resources 确保连接归还到连接池
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(queryBuilder.toString())) {
                stmt.setString(1, uuid.toString());
                for (int i = 0; i < materials.size(); i++) {
                    stmt.setString(i + 2, materials.get(i));
                }
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        statuses.put(rs.getString("material"), true);
                    }
                }
            }
        } else {
            // SQLite 连接，保持长连接
            Connection conn = getConnection();
            PreparedStatement stmt = conn.prepareStatement(queryBuilder.toString());
            try {
                stmt.setString(1, uuid.toString());
                for (int i = 0; i < materials.size(); i++) {
                    stmt.setString(i + 2, materials.get(i));
                }
                
                ResultSet rs = stmt.executeQuery();
                try {
                    while (rs.next()) {
                        statuses.put(rs.getString("material"), true);
                    }
                } finally {
                    if (rs != null) {
                        rs.close();
                    }
                }
            } finally {
                if (stmt != null) {
                    stmt.close();
                }
            }
        }
        
        // 填充未找到的物品为 false
        for (String material : materials) {
            statuses.putIfAbsent(material, false);
        }
        
        return statuses;
    }
    
    // 批量获取多个物品的挖掘进度
    public Map<String, Integer> getMineProgresses(UUID uuid, List<String> materials) throws SQLException, ClassNotFoundException {
        ensurePlayerExists(uuid);
        Map<String, Integer> progresses = new HashMap<>();
        
        if (materials.isEmpty()) {
            return progresses;
        }
        
        StringBuilder queryBuilder = new StringBuilder("SELECT material, progress FROM " + tablePrefix + "player_mine_progress WHERE uuid = ? AND material IN (");
        for (int i = 0; i < materials.size(); i++) {
            if (i > 0) queryBuilder.append(", ");
            queryBuilder.append("?");
        }
        queryBuilder.append(")");
        
        if (isMySQL) {
            // MySQL 连接，使用 try-with-resources 确保连接归还到连接池
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(queryBuilder.toString())) {
                stmt.setString(1, uuid.toString());
                for (int i = 0; i < materials.size(); i++) {
                    stmt.setString(i + 2, materials.get(i));
                }
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        progresses.put(rs.getString("material"), rs.getInt("progress"));
                    }
                }
            }
        } else {
            // SQLite 连接，保持长连接
            Connection conn = getConnection();
            PreparedStatement stmt = conn.prepareStatement(queryBuilder.toString());
            try {
                stmt.setString(1, uuid.toString());
                for (int i = 0; i < materials.size(); i++) {
                    stmt.setString(i + 2, materials.get(i));
                }
                
                ResultSet rs = stmt.executeQuery();
                try {
                    while (rs.next()) {
                        progresses.put(rs.getString("material"), rs.getInt("progress"));
                    }
                } finally {
                    if (rs != null) {
                        rs.close();
                    }
                }
            } finally {
                if (stmt != null) {
                    stmt.close();
                }
            }
        }
        
        // 填充未找到的物品为 0
        for (String material : materials) {
            progresses.putIfAbsent(material, 0);
        }
        
        return progresses;
    }
    
    // 批量获取多个物品的出售进度
    public Map<String, Integer> getSellProgresses(UUID uuid, List<String> materials) throws SQLException, ClassNotFoundException {
        ensurePlayerExists(uuid);
        Map<String, Integer> progresses = new HashMap<>();
        
        if (materials.isEmpty()) {
            return progresses;
        }
        
        StringBuilder queryBuilder = new StringBuilder("SELECT material, progress FROM " + tablePrefix + "player_sell_progress WHERE uuid = ? AND material IN (");
        for (int i = 0; i < materials.size(); i++) {
            if (i > 0) queryBuilder.append(", ");
            queryBuilder.append("?");
        }
        queryBuilder.append(")");
        
        if (isMySQL) {
            // MySQL 连接，使用 try-with-resources 确保连接归还到连接池
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(queryBuilder.toString())) {
                stmt.setString(1, uuid.toString());
                for (int i = 0; i < materials.size(); i++) {
                    stmt.setString(i + 2, materials.get(i));
                }
                
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        progresses.put(rs.getString("material"), rs.getInt("progress"));
                    }
                }
            }
        } else {
            // SQLite 连接，保持长连接
            Connection conn = getConnection();
            PreparedStatement stmt = conn.prepareStatement(queryBuilder.toString());
            try {
                stmt.setString(1, uuid.toString());
                for (int i = 0; i < materials.size(); i++) {
                    stmt.setString(i + 2, materials.get(i));
                }
                
                ResultSet rs = stmt.executeQuery();
                try {
                    while (rs.next()) {
                        progresses.put(rs.getString("material"), rs.getInt("progress"));
                    }
                } finally {
                    if (rs != null) {
                        rs.close();
                    }
                }
            } finally {
                if (stmt != null) {
                    stmt.close();
                }
            }
        }
        
        // 填充未找到的物品为 0
        for (String material : materials) {
            progresses.putIfAbsent(material, 0);
        }
        
        return progresses;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
            if (dataSource != null) {
                dataSource.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to close database connection: " + e.getMessage());
        }
    }

}
