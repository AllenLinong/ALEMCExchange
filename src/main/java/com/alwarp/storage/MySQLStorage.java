package com.alwarp.storage;

import com.alwarp.model.*;
import com.alwarp.scheduler.FoliaScheduler;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MySQL閺佺増宓佺€涙ê鍋嶇€圭偟骞囬妴? * 娴ｈ法鏁ら崡鏇＄箾閹恒儲膩瀵骏绱濋幍鈧張澶嬫殶閹诡喖绨遍幙宥勭稊閻㈢洝anager閺€鎯у煂瀵倹顒炵痪璺ㄢ柤閹笛嗩攽閵? */
public class MySQLStorage implements StorageManager {

    private final Plugin plugin;
    private final FoliaScheduler scheduler;
    private final String tablePrefix;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private HikariDataSource dataSource;

    public MySQLStorage(Plugin plugin, FoliaScheduler scheduler, String tablePrefix,
                        String host, int port, String database, String username, String password) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.tablePrefix = tablePrefix;
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
    }

    @Override
    public void init() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            initDataSource();
            createTables();
            if (plugin instanceof com.alwarp.ALwarp alwarp) {
                alwarp.logInfo("MySQL 数据库初始化完成", "MySQL database initialized");
            } else {
                plugin.getLogger().info("MySQL database initialized");
            }
        } catch (Exception e) {
            String prefix = plugin instanceof com.alwarp.ALwarp alwarp
                    ? alwarp.localize("MySQL 数据库初始化失败: ", "MySQL database initialization failed: ")
                    : "MySQL database initialization failed: ";
            plugin.getLogger().severe(prefix + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    private void initDataSource() {
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true"
                + "&useUnicode=true&characterEncoding=utf8&serverTimezone=UTC"
                + "&connectTimeout=10000&socketTimeout=30000&tcpKeepAlive=true";
        org.bukkit.configuration.file.FileConfiguration dbConfig = ((com.alwarp.ALwarp) plugin).getDatabaseConfig();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setPoolName("ALwarp-MySQL");
        config.setMaximumPoolSize(dbConfig.getInt("database.pool.maximum_pool_size", 20));
        config.setMinimumIdle(dbConfig.getInt("database.pool.minimum_idle", 5));
        config.setConnectionTimeout(dbConfig.getLong("database.pool.connection_timeout", 30000));
        config.setIdleTimeout(dbConfig.getLong("database.pool.idle_timeout", 600000));
        config.setMaxLifetime(dbConfig.getLong("database.pool.max_lifetime", 1800000));
        config.setKeepaliveTime(Math.min(300000L, Math.max(30000L, config.getMaxLifetime() / 2)));
        config.setConnectionTestQuery("SELECT 1");
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        dataSource = new HikariDataSource(config);
    }

    private Connection getConnection() throws SQLException {
        if (dataSource == null) {
            initDataSource();
        }
        return dataSource.getConnection();
    }

    private void createTables() throws SQLException {
        String lm = tablePrefix + "landmarks";
        String ct = tablePrefix + "categories";
        String mg = tablePrefix + "landmark_managers";
        String bl = tablePrefix + "landmark_blacklists";
        String pn = tablePrefix + "landmark_pins";
        String rt = tablePrefix + "ratings";
        String tx = tablePrefix + "transactions";
        String fv = tablePrefix + "favorites";
        String ic = tablePrefix + "incomes";
        String pr = tablePrefix + "players";
        String lv = tablePrefix + "landmark_visits";
        String rr = tablePrefix + "rating_reports";

        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + lm + " (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "name VARCHAR(100) NOT NULL," +
                    "description TEXT," +
                    "name_color VARCHAR(32) DEFAULT NULL," +
                    "description_color VARCHAR(32) DEFAULT NULL," +
                    "name_bold BOOLEAN DEFAULT FALSE," +
                    "description_bold BOOLEAN DEFAULT FALSE," +
                    "icon VARCHAR(50) DEFAULT 'COMPASS'," +
                    "icon_custom_model_data INT DEFAULT NULL," +
                    "icon_plugin_item VARCHAR(100) DEFAULT NULL," +
                    "icon_data MEDIUMTEXT DEFAULT NULL," +
                    "owner_uuid VARCHAR(36) NOT NULL," +
                    "owner_name VARCHAR(16) NOT NULL," +
                    "server_name VARCHAR(50) NOT NULL," +
                    "world VARCHAR(100) NOT NULL," +
                    "x INT NOT NULL," +
                    "y INT NOT NULL," +
                    "z INT NOT NULL," +
                    "category_id INT DEFAULT 1," +
                    "price DOUBLE DEFAULT 0.0," +
                    "visit_count INT DEFAULT 0," +
                    "weekly_visits INT DEFAULT 0," +
                    "is_private BOOLEAN DEFAULT FALSE," +
                    "is_global BOOLEAN DEFAULT TRUE," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + ct + " (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "name VARCHAR(50) NOT NULL," +
                    "icon VARCHAR(50) DEFAULT 'MAP'," +
                    "icon_custom_model_data INT DEFAULT NULL," +
                    "icon_plugin_item VARCHAR(100) DEFAULT NULL," +
                    "color VARCHAR(10) DEFAULT '&7'," +
                    "is_default BOOLEAN DEFAULT FALSE" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + mg + " (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "landmark_id INT NOT NULL," +
                    "player_uuid VARCHAR(36) NOT NULL," +
                    "player_name VARCHAR(16) NOT NULL," +
                    "added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY(landmark_id) REFERENCES " + lm + "(id) ON DELETE CASCADE" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + bl + " (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "landmark_id INT NOT NULL," +
                    "player_uuid VARCHAR(36) NOT NULL," +
                    "player_name VARCHAR(16) NOT NULL," +
                    "added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "UNIQUE KEY uq_" + tablePrefix + "blacklist_landmark_player (landmark_id, player_uuid)," +
                    "FOREIGN KEY(landmark_id) REFERENCES " + lm + "(id) ON DELETE CASCADE" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + rt + " (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "landmark_id INT NOT NULL," +
                    "player_uuid VARCHAR(36) NOT NULL," +
                    "player_name VARCHAR(16) NOT NULL," +
                    "score INT NOT NULL CHECK(score BETWEEN 1 AND 5)," +
                    "comment TEXT," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY(landmark_id) REFERENCES " + lm + "(id) ON DELETE CASCADE" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + lv + " (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "player_uuid VARCHAR(36) NOT NULL," +
                    "landmark_id INT NOT NULL," +
                    "visited_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                    "UNIQUE KEY uq_" + tablePrefix + "visit_player_landmark (player_uuid, landmark_id)," +
                    "FOREIGN KEY(landmark_id) REFERENCES " + lm + "(id) ON DELETE CASCADE" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + rr + " (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "rating_id INT NOT NULL," +
                    "player_uuid VARCHAR(36) NOT NULL," +
                    "reported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "UNIQUE KEY uq_" + tablePrefix + "report_rating_player (rating_id, player_uuid)," +
                    "FOREIGN KEY(rating_id) REFERENCES " + rt + "(id) ON DELETE CASCADE" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + tx + " (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "landmark_id INT NOT NULL," +
                    "player_uuid VARCHAR(36) NOT NULL," +
                    "player_name VARCHAR(16) NOT NULL," +
                    "amount DOUBLE NOT NULL," +
                    "tax_amount DOUBLE DEFAULT 0.0," +
                    "type VARCHAR(20) NOT NULL," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "FOREIGN KEY(landmark_id) REFERENCES " + lm + "(id) ON DELETE CASCADE" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + ic + " (" +
                    "owner_uuid VARCHAR(36) PRIMARY KEY," +
                    "owner_name VARCHAR(16) NOT NULL," +
                    "amount DOUBLE NOT NULL DEFAULT 0.0," +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + pr + " (" +
                    "player_uuid VARCHAR(36) PRIMARY KEY," +
                    "player_name VARCHAR(16) NOT NULL," +
                    "name_lower VARCHAR(16) NOT NULL UNIQUE," +
                    "skin_texture_value MEDIUMTEXT DEFAULT NULL," +
                    "skin_texture_signature TEXT DEFAULT NULL," +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + fv + " (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "player_uuid VARCHAR(36) NOT NULL," +
                    "landmark_id INT NOT NULL," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "UNIQUE KEY uq_" + tablePrefix + "fav_player_landmark (player_uuid, landmark_id)," +
                    "FOREIGN KEY(landmark_id) REFERENCES " + lm + "(id) ON DELETE CASCADE" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + pn + " (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "slot_index INT NOT NULL," +
                    "landmark_id INT NOT NULL," +
                    "buyer_uuid VARCHAR(36) NOT NULL," +
                    "buyer_name VARCHAR(16) NOT NULL," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "expires_at TIMESTAMP NOT NULL," +
                    "UNIQUE KEY uq_" + tablePrefix + "pin_slot (slot_index)," +
                    "FOREIGN KEY(landmark_id) REFERENCES " + lm + "(id) ON DELETE CASCADE" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

        }

        addColumnIfMissing(lm, "icon_custom_model_data", "INT DEFAULT NULL");
        addColumnIfMissing(lm, "icon_plugin_item", "VARCHAR(100) DEFAULT NULL");
        addColumnIfMissing(lm, "name_color", "VARCHAR(32) DEFAULT NULL");
        addColumnIfMissing(lm, "description_color", "VARCHAR(32) DEFAULT NULL");
        addColumnIfMissing(lm, "name_bold", "BOOLEAN DEFAULT FALSE");
        addColumnIfMissing(lm, "description_bold", "BOOLEAN DEFAULT FALSE");
        addColumnIfMissing(lm, "icon_data", "MEDIUMTEXT DEFAULT NULL");
        addColumnIfMissing(lm, "price", "DOUBLE DEFAULT 0.0");
        addColumnIfMissing(lm, "visit_count", "INT DEFAULT 0");
        addColumnIfMissing(lm, "weekly_visits", "INT DEFAULT 0");
        addColumnIfMissing(lm, "is_private", "BOOLEAN DEFAULT FALSE");
        addColumnIfMissing(lm, "is_global", "BOOLEAN DEFAULT TRUE");
        addColumnIfMissing(ct, "icon_custom_model_data", "INT DEFAULT NULL");
        addColumnIfMissing(ct, "icon_plugin_item", "VARCHAR(100) DEFAULT NULL");
        addColumnIfMissing(tx, "tax_amount", "DOUBLE DEFAULT 0.0");
        addColumnIfMissing(pr, "skin_texture_value", "MEDIUMTEXT DEFAULT NULL");
        addColumnIfMissing(pr, "skin_texture_signature", "TEXT DEFAULT NULL");

        createIndexIfMissing("idx_" + tablePrefix + "ic_amount", ic, "amount");
        createIndexIfMissing("idx_" + tablePrefix + "lm_owner", lm, "owner_uuid");
        createIndexIfMissing("idx_" + tablePrefix + "lm_cat", lm, "category_id");
        createIndexIfMissing("idx_" + tablePrefix + "lm_srv", lm, "server_name");
        createIndexIfMissing("idx_" + tablePrefix + "lm_pop", lm, "weekly_visits DESC");
        createIndexIfMissing("idx_" + tablePrefix + "rt_lm", rt, "landmark_id");
        createIndexIfMissing("idx_" + tablePrefix + "rt_plm", rt, "player_uuid, landmark_id");
        createIndexIfMissing("idx_" + tablePrefix + "lv_plm", lv, "player_uuid, landmark_id");
        createIndexIfMissing("idx_" + tablePrefix + "lv_lm", lv, "landmark_id");
        createIndexIfMissing("idx_" + tablePrefix + "rr_rating", rr, "rating_id");
        createIndexIfMissing("idx_" + tablePrefix + "rr_player", rr, "player_uuid");
        createIndexIfMissing("idx_" + tablePrefix + "tx_lm", tx, "landmark_id");
        createIndexIfMissing("idx_" + tablePrefix + "mg_lm", mg, "landmark_id");
        createIndexIfMissing("idx_" + tablePrefix + "mg_lmp", mg, "landmark_id, player_uuid");
        createIndexIfMissing("idx_" + tablePrefix + "bl_lm", bl, "landmark_id");
        createIndexIfMissing("idx_" + tablePrefix + "bl_lmp", bl, "landmark_id, player_uuid");
        createIndexIfMissing("idx_" + tablePrefix + "fv_player", fv, "player_uuid");
        createIndexIfMissing("idx_" + tablePrefix + "fv_lm", fv, "landmark_id");
        createIndexIfMissing("idx_" + tablePrefix + "pn_slot", pn, "slot_index");
        createIndexIfMissing("idx_" + tablePrefix + "pn_lm", pn, "landmark_id");
        createIndexIfMissing("idx_" + tablePrefix + "pn_expires", pn, "expires_at");
        createIndexIfMissing("idx_" + tablePrefix + "pr_name_lower", pr, "name_lower");
        createIndexIfMissing("idx_" + tablePrefix + "pr_updated_at", pr, "updated_at");
    }

    // 閳光偓閳光偓閳光偓 Landmark CRUD 閳光偓閳光偓閳光偓

    @Override
    public Landmark createLandmark(Landmark landmark) {
        int newId = landmark.getId() > 0 ? landmark.getId() : getNextAvailableLandmarkId();
        Landmark created = insertLandmark(landmark, newId);
        if (created != null || landmark.getId() > 0) return created;
        return insertLandmark(landmark, getNextAvailableLandmarkId());
    }

    @Override
    public Landmark importLandmarkSnapshot(Landmark landmark) {
        int newId = landmark.getId() > 0 ? landmark.getId() : getNextAvailableLandmarkId();
        String sql = "INSERT INTO " + tablePrefix + "landmarks " +
                "(id, name, description, name_color, description_color, name_bold, description_bold, icon, icon_custom_model_data, icon_plugin_item, icon_data, " +
                "owner_uuid, owner_name, server_name, world, x, y, z, category_id, price, " +
                "visit_count, weekly_visits, is_private, is_global, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP), COALESCE(?, CURRENT_TIMESTAMP)) " +
                "ON DUPLICATE KEY UPDATE " +
                "name=VALUES(name), description=VALUES(description), " +
                "name_color=VALUES(name_color), description_color=VALUES(description_color), " +
                "name_bold=VALUES(name_bold), description_bold=VALUES(description_bold), icon=VALUES(icon), " +
                "icon_custom_model_data=VALUES(icon_custom_model_data), " +
                "icon_plugin_item=VALUES(icon_plugin_item), icon_data=VALUES(icon_data), " +
                "owner_uuid=VALUES(owner_uuid), owner_name=VALUES(owner_name), " +
                "server_name=VALUES(server_name), world=VALUES(world), x=VALUES(x), y=VALUES(y), z=VALUES(z), " +
                "category_id=VALUES(category_id), price=VALUES(price), visit_count=VALUES(visit_count), " +
                "weekly_visits=VALUES(weekly_visits), is_private=VALUES(is_private), " +
                "is_global=VALUES(is_global), created_at=VALUES(created_at), updated_at=VALUES(updated_at)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, newId);
            ps.setString(2, landmark.getName());
            ps.setString(3, landmark.getDescription());
            ps.setString(4, landmark.getNameColor());
            ps.setString(5, landmark.getDescriptionColor());
            ps.setBoolean(6, landmark.isNameBold());
            ps.setBoolean(7, landmark.isDescriptionBold());
            ps.setString(8, landmark.getIcon());
            setNullableInt(ps, 9, landmark.getIconCustomModelData());
            ps.setString(10, landmark.getIconPluginItem());
            ps.setString(11, serializeIconData(landmark.getIconData()));
            ps.setString(12, landmark.getOwnerUuid().toString());
            ps.setString(13, landmark.getOwnerName());
            ps.setString(14, landmark.getServerName());
            ps.setString(15, landmark.getWorld());
            ps.setInt(16, landmark.getX());
            ps.setInt(17, landmark.getY());
            ps.setInt(18, landmark.getZ());
            ps.setInt(19, landmark.getCategoryId());
            ps.setDouble(20, landmark.getPrice());
            ps.setInt(21, landmark.getVisitCount());
            ps.setInt(22, landmark.getWeeklyVisits());
            ps.setBoolean(23, landmark.isPrivate());
            ps.setBoolean(24, landmark.isGlobal());
            ps.setTimestamp(25, landmark.getCreatedAt());
            ps.setTimestamp(26, landmark.getUpdatedAt());
            ps.executeUpdate();
            landmark.setId(newId);
            Landmark full = getLandmarkById(newId);
            return full != null ? full : landmark;
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL landmark snapshot import failed: " + e.getMessage());
            return null;
        }
    }

    private Landmark insertLandmark(Landmark landmark, int newId) {
        String sql = "INSERT INTO " + tablePrefix + "landmarks " +
                "(id, name, description, name_color, description_color, name_bold, description_bold, icon, icon_custom_model_data, icon_plugin_item, icon_data, " +
                "owner_uuid, owner_name, server_name, world, x, y, z, category_id, price) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, newId);
            ps.setString(2, landmark.getName());
            ps.setString(3, landmark.getDescription());
            ps.setString(4, landmark.getNameColor());
            ps.setString(5, landmark.getDescriptionColor());
            ps.setBoolean(6, landmark.isNameBold());
            ps.setBoolean(7, landmark.isDescriptionBold());
            ps.setString(8, landmark.getIcon());
            setNullableInt(ps, 9, landmark.getIconCustomModelData());
            ps.setString(10, landmark.getIconPluginItem());
            ps.setString(11, serializeIconData(landmark.getIconData()));
            ps.setString(12, landmark.getOwnerUuid().toString());
            ps.setString(13, landmark.getOwnerName());
            ps.setString(14, landmark.getServerName());
            ps.setString(15, landmark.getWorld());
            ps.setInt(16, landmark.getX());
            ps.setInt(17, landmark.getY());
            ps.setInt(18, landmark.getZ());
            ps.setInt(19, landmark.getCategoryId());
            ps.setDouble(20, landmark.getPrice());
            ps.executeUpdate();
            landmark.setId(newId);
            Landmark full = getLandmarkById(newId);
            if (full != null) return full;
            return landmark;
        } catch (SQLException e) {
            if (isDuplicateKey(e) && landmark.getId() <= 0) return null;
            plugin.getLogger().severe("MySQL landmark creation failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public int getNextAvailableLandmarkId() {
        String sql = "SELECT id FROM " + tablePrefix + "landmarks ORDER BY id ASC";
        int expected = 1;
        try (Connection conn = getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                int id = rs.getInt("id");
                if (id == expected) {
                    expected++;
                } else if (id > expected) {
                    break;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL next landmark id query failed: " + e.getMessage());
        }
        return expected;
    }

    @Override
    public boolean updateLandmark(Landmark landmark) {
        String sql = "UPDATE " + tablePrefix + "landmarks SET name=?, description=?, name_color=?, description_color=?, " +
                "name_bold=?, description_bold=?, icon=?, " +
                "icon_custom_model_data=?, icon_plugin_item=?, icon_data=?, server_name=?, world=?, x=?, y=?, z=?, " +
                "category_id=?, price=?, " +
                "is_private=?, updated_at=CURRENT_TIMESTAMP WHERE id=?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, landmark.getName());
            ps.setString(2, landmark.getDescription());
            ps.setString(3, landmark.getNameColor());
            ps.setString(4, landmark.getDescriptionColor());
            ps.setBoolean(5, landmark.isNameBold());
            ps.setBoolean(6, landmark.isDescriptionBold());
            ps.setString(7, landmark.getIcon());
            setNullableInt(ps, 8, landmark.getIconCustomModelData());
            ps.setString(9, landmark.getIconPluginItem());
            ps.setString(10, serializeIconData(landmark.getIconData()));
            ps.setString(11, landmark.getServerName());
            ps.setString(12, landmark.getWorld());
            ps.setInt(13, landmark.getX());
            ps.setInt(14, landmark.getY());
            ps.setInt(15, landmark.getZ());
            ps.setInt(16, landmark.getCategoryId());
            ps.setDouble(17, landmark.getPrice());
            ps.setBoolean(18, landmark.isPrivate());
            ps.setInt(19, landmark.getId());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL landmark update failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean deleteLandmark(int id) {
        return executeUpdate("DELETE FROM " + tablePrefix + "landmarks WHERE id=?", ps -> ps.setInt(1, id));
    }

    @Override
    public int deleteLandmarksByOwner(UUID ownerUuid) {
        return executeUpdateCount(
                "DELETE FROM " + tablePrefix + "landmarks WHERE owner_uuid=?",
                ps -> ps.setString(1, ownerUuid.toString())
        );
    }

    @Override
    public Landmark getLandmarkById(int id) {
        String sql = "SELECT * FROM " + tablePrefix + "landmarks WHERE id=?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapLandmark(rs);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL landmark query failed: " + e.getMessage());
        }
        return null;
    }

    @Override
    public List<Landmark> getLandmarksByOwner(UUID ownerUuid) {
        return queryLandmarks("SELECT * FROM " + tablePrefix + "landmarks WHERE owner_uuid=? ORDER BY created_at DESC",
                ps -> ps.setString(1, ownerUuid.toString()));
    }

    @Override
    public List<Landmark> getLandmarksByCategory(int categoryId) {
        return queryLandmarks("SELECT * FROM " + tablePrefix + "landmarks WHERE category_id=? ORDER BY weekly_visits DESC",
                ps -> ps.setInt(1, categoryId));
    }

    @Override
    public List<Landmark> getAllLandmarks() {
        return queryLandmarks("SELECT * FROM " + tablePrefix + "landmarks ORDER BY weekly_visits DESC", null);
    }

    @Override
    public List<Landmark> getLandmarksSortedByPopularity(int limit, int offset) {
        return queryLandmarks("SELECT * FROM " + tablePrefix + "landmarks ORDER BY weekly_visits DESC LIMIT ? OFFSET ?",
                ps -> {
                    ps.setInt(1, limit);
                    ps.setInt(2, offset);
                });
    }

    @Override
    public List<Landmark> getLandmarksByServer(String serverName) {
        return queryLandmarks("SELECT * FROM " + tablePrefix + "landmarks WHERE server_name=? ORDER BY weekly_visits DESC",
                ps -> ps.setString(1, serverName));
    }

    @Override
    public List<Landmark> searchLandmarks(String query) {
        return queryLandmarks("SELECT * FROM " + tablePrefix + "landmarks WHERE name LIKE ? OR owner_name LIKE ? ORDER BY weekly_visits DESC",
                ps -> {
                    String like = "%" + query + "%";
                    ps.setString(1, like);
                    ps.setString(2, like);
                });
    }

    @Override
    public int getLandmarkCount() {
        return queryCount("SELECT COUNT(*) FROM " + tablePrefix + "landmarks", null);
    }

    @Override
    public void incrementVisitCount(int landmarkId) {
        executeUpdate("UPDATE " + tablePrefix + "landmarks SET visit_count = visit_count + 1 WHERE id=?",
                ps -> ps.setInt(1, landmarkId));
    }

    @Override
    public void recordLandmarkVisit(UUID playerUuid, int landmarkId) {
        if (playerUuid == null || landmarkId <= 0) return;
        executeUpdate(
                "INSERT IGNORE INTO " + tablePrefix + "landmark_visits (player_uuid, landmark_id) VALUES (?,?)",
                ps -> {
                    ps.setString(1, playerUuid.toString());
                    ps.setInt(2, landmarkId);
                });
    }

    @Override
    public boolean hasVisitedLandmark(UUID playerUuid, int landmarkId) {
        if (playerUuid == null || landmarkId <= 0) return false;
        return queryCount(
                "SELECT COUNT(*) FROM " + tablePrefix + "landmark_visits WHERE player_uuid=? AND landmark_id=?",
                ps -> {
                    ps.setString(1, playerUuid.toString());
                    ps.setInt(2, landmarkId);
                }) > 0;
    }

    @Override
    public void adjustLandmarkHeat(int landmarkId, int delta) {
        executeUpdate("UPDATE " + tablePrefix + "landmarks SET weekly_visits = GREATEST(0, weekly_visits + ?) WHERE id=?",
                ps -> {
                    ps.setInt(1, delta);
                    ps.setInt(2, landmarkId);
                });
    }

    // 閳光偓閳光偓閳光偓 Favorite CRUD 閳光偓閳光偓閳光偓

    @Override
    public boolean addFavorite(UUID playerUuid, int landmarkId) {
        return executeUpdate("INSERT IGNORE INTO " + tablePrefix + "favorites (player_uuid, landmark_id) VALUES (?,?)",
                ps -> {
                    ps.setString(1, playerUuid.toString());
                    ps.setInt(2, landmarkId);
                });
    }

    @Override
    public boolean importFavoriteSnapshot(FavoriteSnapshot favorite) {
        return executeUpdate(
                "REPLACE INTO " + tablePrefix + "favorites (player_uuid, landmark_id, created_at) VALUES (?,?,COALESCE(?, CURRENT_TIMESTAMP))",
                ps -> {
                    ps.setString(1, favorite.playerUuid().toString());
                    ps.setInt(2, favorite.landmarkId());
                    ps.setTimestamp(3, favorite.createdAt());
                });
    }

    @Override
    public boolean removeFavorite(UUID playerUuid, int landmarkId) {
        return executeUpdate("DELETE FROM " + tablePrefix + "favorites WHERE player_uuid=? AND landmark_id=?",
                ps -> {
                    ps.setString(1, playerUuid.toString());
                    ps.setInt(2, landmarkId);
                });
    }

    @Override
    public int removeFavoritesByPlayer(UUID playerUuid) {
        return executeUpdateCount(
                "DELETE FROM " + tablePrefix + "favorites WHERE player_uuid=?",
                ps -> ps.setString(1, playerUuid.toString())
        );
    }

    @Override
    public int removeFavoritesByLandmark(int landmarkId) {
        return executeUpdateCount(
                "DELETE FROM " + tablePrefix + "favorites WHERE landmark_id=?",
                ps -> ps.setInt(1, landmarkId)
        );
    }

    @Override
    public List<Integer> getFavorites(UUID playerUuid) {
        List<Integer> ids = new ArrayList<>();
        String sql = "SELECT landmark_id FROM " + tablePrefix + "favorites WHERE player_uuid=? ORDER BY created_at DESC, id DESC";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt("landmark_id"));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL favorite query failed: " + e.getMessage());
        }
        return ids;
    }

    @Override
    public Map<UUID, List<Integer>> getAllFavorites() {
        Map<UUID, List<Integer>> result = new HashMap<>();
        String sql = "SELECT player_uuid, landmark_id FROM " + tablePrefix + "favorites ORDER BY created_at DESC, id DESC";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                result.computeIfAbsent(uuid, k -> new ArrayList<>()).add(rs.getInt("landmark_id"));
            }
        } catch (SQLException | IllegalArgumentException e) {
            plugin.getLogger().severe("MySQL favorites cache load failed: " + e.getMessage());
        }
        return result;
    }

    @Override
    public List<FavoriteSnapshot> getAllFavoriteSnapshots() {
        List<FavoriteSnapshot> result = new ArrayList<>();
        String sql = "SELECT player_uuid, landmark_id, created_at FROM " + tablePrefix + "favorites ORDER BY created_at DESC, id DESC";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(new FavoriteSnapshot(
                        UUID.fromString(rs.getString("player_uuid")),
                        rs.getInt("landmark_id"),
                        rs.getTimestamp("created_at")));
            }
        } catch (SQLException | IllegalArgumentException e) {
            plugin.getLogger().severe("MySQL favorite snapshot export failed: " + e.getMessage());
        }
        return result;
    }

    // 閳光偓閳光偓閳光偓 Category CRUD 閳光偓閳光偓閳光偓

    @Override
    public Category createCategory(Category category) {
        String sql;
        boolean explicitId = category.getId() > 0;
        if (explicitId) {
            sql = "INSERT INTO " + tablePrefix + "categories " +
                    "(id, name, icon, icon_custom_model_data, icon_plugin_item, color, is_default) VALUES (?,?,?,?,?,?,?)";
        } else {
            sql = "INSERT INTO " + tablePrefix + "categories " +
                    "(name, icon, icon_custom_model_data, icon_plugin_item, color, is_default) VALUES (?,?,?,?,?,?)";
        }
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            if (explicitId) ps.setInt(i++, category.getId());
            ps.setString(i++, category.getName());
            ps.setString(i++, category.getIcon());
            setNullableInt(ps, i++, category.getIconCustomModelData());
            ps.setString(i++, category.getIconPluginItem());
            ps.setString(i++, category.getColor());
            ps.setBoolean(i, category.isDefault());
            ps.executeUpdate();
            if (!explicitId) {
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) category.setId(rs.getInt(1));
                }
            }
            return category;
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL category creation failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public boolean updateCategory(Category category) {
        String sql = "UPDATE " + tablePrefix + "categories SET name=?, icon=?, icon_custom_model_data=?, " +
                "icon_plugin_item=?, color=?, is_default=? WHERE id=?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, category.getName());
            ps.setString(2, category.getIcon());
            setNullableInt(ps, 3, category.getIconCustomModelData());
            ps.setString(4, category.getIconPluginItem());
            ps.setString(5, category.getColor());
            ps.setBoolean(6, category.isDefault());
            ps.setInt(7, category.getId());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL category update failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean deleteCategory(int id) {
        return executeUpdate("DELETE FROM " + tablePrefix + "categories WHERE id=?", ps -> ps.setInt(1, id));
    }

    @Override
    public Category getCategoryById(int id) {
        String sql = "SELECT * FROM " + tablePrefix + "categories WHERE id=?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapCategory(rs);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL category query failed: " + e.getMessage());
        }
        return null;
    }

    @Override
    public Category getCategoryByName(String name) {
        String sql = "SELECT * FROM " + tablePrefix + "categories WHERE name=?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapCategory(rs);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL category query failed: " + e.getMessage());
        }
        return null;
    }

    @Override
    public List<Category> getAllCategories() {
        List<Category> list = new ArrayList<>();
        try (Connection conn = getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + tablePrefix + "categories ORDER BY id")) {
            while (rs.next()) list.add(mapCategory(rs));
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL categories query failed: " + e.getMessage());
        }
        return list;
    }

    // 閳光偓閳光偓閳光偓 Rating CRUD 閳光偓閳光偓閳光偓

    @Override
    public Rating addRating(Rating rating) {
        String sql = rating.getCreatedAt() != null
                ? "INSERT INTO " + tablePrefix + "ratings (landmark_id, player_uuid, player_name, score, comment, created_at) VALUES (?,?,?,?,?,?)"
                : "INSERT INTO " + tablePrefix + "ratings (landmark_id, player_uuid, player_name, score, comment) VALUES (?,?,?,?,?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, rating.getLandmarkId());
            ps.setString(2, rating.getPlayerUuid().toString());
            ps.setString(3, rating.getPlayerName());
            ps.setInt(4, rating.getScore());
            ps.setString(5, rating.getComment());
            if (rating.getCreatedAt() != null) ps.setTimestamp(6, rating.getCreatedAt());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) rating.setId(rs.getInt(1));
            }
            return rating;
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL rating creation failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public boolean updateRating(Rating rating) {
        String sql = "UPDATE " + tablePrefix + "ratings SET score=?, comment=?, created_at=CURRENT_TIMESTAMP WHERE id=?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, rating.getScore());
            ps.setString(2, rating.getComment());
            ps.setInt(3, rating.getId());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL rating update failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean deleteRating(int id) {
        return executeUpdate("DELETE FROM " + tablePrefix + "ratings WHERE id=?", ps -> ps.setInt(1, id));
    }

    @Override
    public int deleteRatingsByPlayer(UUID playerUuid) {
        return executeUpdateCount(
                "DELETE FROM " + tablePrefix + "ratings WHERE player_uuid=?",
                ps -> ps.setString(1, playerUuid.toString())
        );
    }

    @Override
    public int deleteRatingsByLandmark(int landmarkId) {
        return executeUpdateCount(
                "DELETE FROM " + tablePrefix + "ratings WHERE landmark_id=?",
                ps -> ps.setInt(1, landmarkId)
        );
    }

    @Override
    public Rating getRatingByPlayer(int landmarkId, UUID playerUuid) {
        String sql = "SELECT * FROM " + tablePrefix + "ratings WHERE landmark_id=? AND player_uuid=?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, landmarkId);
            ps.setString(2, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRating(rs);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL rating query failed: " + e.getMessage());
        }
        return null;
    }

    @Override
    public List<Rating> getRatingsByLandmark(int landmarkId) {
        List<Rating> list = new ArrayList<>();
        String sql = "SELECT * FROM " + tablePrefix + "ratings WHERE landmark_id=? ORDER BY created_at DESC";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, landmarkId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRating(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL landmark ratings query failed: " + e.getMessage());
        }
        return list;
    }

    @Override
    public double getAverageRating(int landmarkId) {
        String sql = "SELECT AVG(score) FROM " + tablePrefix + "ratings WHERE landmark_id=?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, landmarkId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL average rating query failed: " + e.getMessage());
        }
        return 0.0;
    }

    @Override
    public int getRatingCount(int landmarkId) {
        return queryCount("SELECT COUNT(*) FROM " + tablePrefix + "ratings WHERE landmark_id=?",
                ps -> ps.setInt(1, landmarkId));
    }

    @Override
    public Map<Integer, RatingStats> getAllRatingStats() {
        Map<Integer, RatingStats> stats = new HashMap<>();
        String sql = "SELECT landmark_id, COUNT(*) AS rating_count, AVG(score) AS rating_average " +
                "FROM " + tablePrefix + "ratings GROUP BY landmark_id";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                stats.put(rs.getInt("landmark_id"),
                        new RatingStats(rs.getInt("rating_count"), rs.getDouble("rating_average")));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL rating stats query failed: " + e.getMessage());
        }
        return stats;
    }

    // 閳光偓閳光偓閳光偓 Transaction CRUD 閳光偓閳光偓閳光偓

    @Override
    public boolean addRatingReport(int ratingId, UUID playerUuid) {
        if (ratingId <= 0 || playerUuid == null) return false;
        return executeUpdate(
                "INSERT IGNORE INTO " + tablePrefix + "rating_reports (rating_id, player_uuid) VALUES (?,?)",
                ps -> {
                    ps.setInt(1, ratingId);
                    ps.setString(2, playerUuid.toString());
                });
    }

    @Override
    public int getRatingReportCount(int ratingId) {
        if (ratingId <= 0) return 0;
        return queryCount("SELECT COUNT(*) FROM " + tablePrefix + "rating_reports WHERE rating_id=?",
                ps -> ps.setInt(1, ratingId));
    }

    @Override
    public Transaction createTransaction(Transaction transaction) {
        String sql = transaction.getCreatedAt() != null
                ? "INSERT INTO " + tablePrefix + "transactions (landmark_id, player_uuid, player_name, amount, tax_amount, type, created_at) VALUES (?,?,?,?,?,?,?)"
                : "INSERT INTO " + tablePrefix + "transactions (landmark_id, player_uuid, player_name, amount, tax_amount, type) VALUES (?,?,?,?,?,?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, transaction.getLandmarkId());
            ps.setString(2, transaction.getPlayerUuid().toString());
            ps.setString(3, transaction.getPlayerName());
            ps.setDouble(4, transaction.getAmount());
            ps.setDouble(5, transaction.getTaxAmount());
            ps.setString(6, transaction.getType().name());
            if (transaction.getCreatedAt() != null) ps.setTimestamp(7, transaction.getCreatedAt());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) transaction.setId(rs.getInt(1));
            }
            return transaction;
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL transaction creation failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public int deleteTransactionsByLandmark(int landmarkId) {
        return executeUpdateCount(
                "DELETE FROM " + tablePrefix + "transactions WHERE landmark_id=?",
                ps -> ps.setInt(1, landmarkId)
        );
    }

    @Override
    public List<Transaction> getTransactionsByLandmark(int landmarkId) {
        List<Transaction> list = new ArrayList<>();
        String sql = "SELECT * FROM " + tablePrefix + "transactions WHERE landmark_id=? ORDER BY created_at DESC";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, landmarkId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapTransaction(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL landmark transactions query failed: " + e.getMessage());
        }
        return list;
    }

    @Override
    public List<Transaction> getTransactionsByPlayer(UUID playerUuid) {
        List<Transaction> list = new ArrayList<>();
        String sql = "SELECT * FROM " + tablePrefix + "transactions WHERE player_uuid=? ORDER BY created_at DESC";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapTransaction(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL player transactions query failed: " + e.getMessage());
        }
        return list;
    }

    @Override
    public boolean addPendingIncome(UUID ownerUuid, String ownerName, double amount) {
        if (ownerUuid == null || amount <= 0) return false;
        String sql = "INSERT INTO " + tablePrefix + "incomes (owner_uuid, owner_name, amount, updated_at) " +
                "VALUES (?, ?, ?, CURRENT_TIMESTAMP) " +
                "ON DUPLICATE KEY UPDATE owner_name=VALUES(owner_name), amount=amount + VALUES(amount), updated_at=CURRENT_TIMESTAMP";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ownerUuid.toString());
            ps.setString(2, ownerName != null ? ownerName : "Unknown");
            ps.setDouble(3, amount);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL add pending income failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public double getPendingIncome(UUID ownerUuid) {
        if (ownerUuid == null) return 0.0;
        String sql = "SELECT amount FROM " + tablePrefix + "incomes WHERE owner_uuid=?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble("amount");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL pending income query failed: " + e.getMessage());
        }
        return 0.0;
    }

    @Override
    public double claimPendingIncome(UUID ownerUuid) {
        if (ownerUuid == null) return 0.0;
        Connection conn = null;
        try {
            conn = getConnection();
            conn.setAutoCommit(false);
            double amount;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT amount FROM " + tablePrefix + "incomes WHERE owner_uuid=? FOR UPDATE")) {
                ps.setString(1, ownerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    amount = rs.next() ? rs.getDouble("amount") : 0.0;
                }
            }
            if (amount <= 0) {
                conn.commit();
                return 0.0;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + tablePrefix + "incomes SET amount=0, updated_at=CURRENT_TIMESTAMP WHERE owner_uuid=?")) {
                ps.setString(1, ownerUuid.toString());
                ps.executeUpdate();
            }
            conn.commit();
            return amount;
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackError) {
                    plugin.getLogger().severe("MySQL claim pending income rollback failed: " + rollbackError.getMessage());
                }
            }
            plugin.getLogger().severe("MySQL claim pending income failed: " + e.getMessage());
            return 0.0;
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();
                } catch (SQLException closeError) {
                    plugin.getLogger().severe("MySQL claim pending income connection close failed: " + closeError.getMessage());
                }
            }
        }
    }

    @Override
    public List<IncomeSnapshot> getAllIncomeSnapshots() {
        List<IncomeSnapshot> list = new ArrayList<>();
        String sql = "SELECT * FROM " + tablePrefix + "incomes WHERE amount > 0 ORDER BY updated_at DESC";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new IncomeSnapshot(
                        UUID.fromString(rs.getString("owner_uuid")),
                        rs.getString("owner_name"),
                        rs.getDouble("amount"),
                        rs.getTimestamp("updated_at")
                ));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL pending income snapshot query failed: " + e.getMessage());
        }
        return list;
    }

    @Override
    public boolean importIncomeSnapshot(IncomeSnapshot income) {
        if (income == null || income.ownerUuid() == null || income.amount() <= 0) return false;
        String sql = "INSERT INTO " + tablePrefix + "incomes (owner_uuid, owner_name, amount, updated_at) " +
                "VALUES (?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP)) " +
                "ON DUPLICATE KEY UPDATE owner_name=VALUES(owner_name), amount=VALUES(amount), updated_at=VALUES(updated_at)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, income.ownerUuid().toString());
            ps.setString(2, income.ownerName() != null ? income.ownerName() : "Unknown");
            ps.setDouble(3, income.amount());
            ps.setTimestamp(4, income.updatedAt());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL pending income import failed: " + e.getMessage());
            return false;
        }
    }

    // 閳光偓閳光偓閳光偓 Manager operations 閳光偓閳光偓閳光偓

    @Override
    public LandmarkAdmin addManager(LandmarkAdmin admin) {
        String sql = admin.getAddedAt() != null
                ? "INSERT INTO " + tablePrefix + "landmark_managers (landmark_id, player_uuid, player_name, added_at) VALUES (?,?,?,?)"
                : "INSERT INTO " + tablePrefix + "landmark_managers (landmark_id, player_uuid, player_name) VALUES (?,?,?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, admin.getLandmarkId());
            ps.setString(2, admin.getPlayerUuid().toString());
            ps.setString(3, admin.getPlayerName());
            if (admin.getAddedAt() != null) ps.setTimestamp(4, admin.getAddedAt());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) admin.setId(rs.getInt(1));
            }
            return admin;
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL manager creation failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public boolean removeManager(int landmarkId, UUID playerUuid) {
        return executeUpdate("DELETE FROM " + tablePrefix + "landmark_managers WHERE landmark_id=? AND player_uuid=?",
                ps -> {
                    ps.setInt(1, landmarkId);
                    ps.setString(2, playerUuid.toString());
                });
    }

    @Override
    public List<LandmarkAdmin> getManagersByLandmark(int landmarkId) {
        List<LandmarkAdmin> list = new ArrayList<>();
        String sql = "SELECT * FROM " + tablePrefix + "landmark_managers WHERE landmark_id=? ORDER BY added_at";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, landmarkId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapAdmin(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL manager list query failed: " + e.getMessage());
        }
        return list;
    }

    @Override
    public boolean isManager(int landmarkId, UUID playerUuid) {
        return queryCount("SELECT COUNT(*) FROM " + tablePrefix + "landmark_managers WHERE landmark_id=? AND player_uuid=?",
                ps -> {
                    ps.setInt(1, landmarkId);
                    ps.setString(2, playerUuid.toString());
                }) > 0;
    }

    @Override
    public List<Integer> getManagedLandmarkIds(UUID playerUuid) {
        List<Integer> ids = new ArrayList<>();
        String sql = "SELECT landmark_id FROM " + tablePrefix + "landmark_managers WHERE player_uuid=?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt("landmark_id"));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL managed landmark query failed: " + e.getMessage());
        }
        return ids;
    }

    @Override
    public void deleteAllManagers(int landmarkId) {
        executeUpdate("DELETE FROM " + tablePrefix + "landmark_managers WHERE landmark_id=?",
                ps -> ps.setInt(1, landmarkId));
    }

    @Override
    public LandmarkBlacklist addBlacklist(LandmarkBlacklist blacklist) {
        String sql = blacklist.getAddedAt() != null
                ? "INSERT INTO " + tablePrefix + "landmark_blacklists (landmark_id, player_uuid, player_name, added_at) VALUES (?,?,?,?)"
                : "INSERT INTO " + tablePrefix + "landmark_blacklists (landmark_id, player_uuid, player_name) VALUES (?,?,?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, blacklist.getLandmarkId());
            ps.setString(2, blacklist.getPlayerUuid().toString());
            ps.setString(3, blacklist.getPlayerName());
            if (blacklist.getAddedAt() != null) ps.setTimestamp(4, blacklist.getAddedAt());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) blacklist.setId(rs.getInt(1));
            }
            return blacklist;
        } catch (SQLException e) {
            if (!isDuplicateKey(e)) {
                plugin.getLogger().severe("MySQL blacklist creation failed: " + e.getMessage());
            }
            return null;
        }
    }

    @Override
    public boolean removeBlacklist(int landmarkId, UUID playerUuid) {
        return executeUpdate("DELETE FROM " + tablePrefix + "landmark_blacklists WHERE landmark_id=? AND player_uuid=?",
                ps -> {
                    ps.setInt(1, landmarkId);
                    ps.setString(2, playerUuid.toString());
                });
    }

    @Override
    public List<LandmarkBlacklist> getBlacklistByLandmark(int landmarkId) {
        List<LandmarkBlacklist> list = new ArrayList<>();
        String sql = "SELECT * FROM " + tablePrefix + "landmark_blacklists WHERE landmark_id=? ORDER BY added_at";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, landmarkId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapBlacklist(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL blacklist list query failed: " + e.getMessage());
        }
        return list;
    }

    @Override
    public boolean isBlacklisted(int landmarkId, UUID playerUuid) {
        return queryCount("SELECT COUNT(*) FROM " + tablePrefix + "landmark_blacklists WHERE landmark_id=? AND player_uuid=?",
                ps -> {
                    ps.setInt(1, landmarkId);
                    ps.setString(2, playerUuid.toString());
                }) > 0;
    }

    @Override
    public void deleteAllBlacklists(int landmarkId) {
        executeUpdate("DELETE FROM " + tablePrefix + "landmark_blacklists WHERE landmark_id=?",
                ps -> ps.setInt(1, landmarkId));
    }

    // 閳光偓閳光偓閳光偓 Helper methods 閳光偓閳光偓閳光偓

    @Override
    public LandmarkPin addPin(LandmarkPin pin) {
        String sql = pin.getCreatedAt() != null
                ? "INSERT INTO " + tablePrefix + "landmark_pins (slot_index, landmark_id, buyer_uuid, buyer_name, created_at, expires_at) VALUES (?,?,?,?,?,?)"
                : "INSERT INTO " + tablePrefix + "landmark_pins (slot_index, landmark_id, buyer_uuid, buyer_name, expires_at) VALUES (?,?,?,?,?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, pin.getSlotIndex());
            ps.setInt(2, pin.getLandmarkId());
            ps.setString(3, pin.getBuyerUuid().toString());
            ps.setString(4, pin.getBuyerName());
            if (pin.getCreatedAt() != null) {
                ps.setTimestamp(5, pin.getCreatedAt());
                ps.setTimestamp(6, pin.getExpiresAt());
            } else {
                ps.setTimestamp(5, pin.getExpiresAt());
            }
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) pin.setId(rs.getInt(1));
            }
            return pin;
        } catch (SQLException e) {
            if (!isDuplicateKey(e)) {
                plugin.getLogger().severe("MySQL landmark pin creation failed: " + e.getMessage());
            }
            return null;
        }
    }

    @Override
    public LandmarkPin importPinSnapshot(LandmarkPin pin) {
        String sql = "REPLACE INTO " + tablePrefix + "landmark_pins " +
                "(id, slot_index, landmark_id, buyer_uuid, buyer_name, created_at, expires_at) " +
                "VALUES (NULLIF(?, 0), ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP), ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, pin.getId());
            ps.setInt(2, pin.getSlotIndex());
            ps.setInt(3, pin.getLandmarkId());
            ps.setString(4, pin.getBuyerUuid().toString());
            ps.setString(5, pin.getBuyerName() != null ? pin.getBuyerName() : "Unknown");
            ps.setTimestamp(6, pin.getCreatedAt());
            ps.setTimestamp(7, pin.getExpiresAt());
            return ps.executeUpdate() > 0 ? getPinBySlot(pin.getSlotIndex()) : null;
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL landmark pin import failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public boolean removePinBySlot(int slotIndex) {
        return executeUpdate("DELETE FROM " + tablePrefix + "landmark_pins WHERE slot_index=?",
                ps -> ps.setInt(1, slotIndex));
    }

    @Override
    public int deleteExpiredPins(Timestamp now) {
        return executeUpdateCount("DELETE FROM " + tablePrefix + "landmark_pins WHERE expires_at<=?",
                ps -> ps.setTimestamp(1, now));
    }

    @Override
    public int deletePinsByLandmark(int landmarkId) {
        return executeUpdateCount("DELETE FROM " + tablePrefix + "landmark_pins WHERE landmark_id=?",
                ps -> ps.setInt(1, landmarkId));
    }

    @Override
    public void deleteAllPins() {
        executeUpdate("DELETE FROM " + tablePrefix + "landmark_pins", null);
    }

    @Override
    public LandmarkPin getActivePinBySlot(int slotIndex, Timestamp now) {
        String sql = "SELECT * FROM " + tablePrefix + "landmark_pins WHERE slot_index=? AND expires_at>?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, slotIndex);
            ps.setTimestamp(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapPin(rs);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL landmark pin query failed: " + e.getMessage());
        }
        return null;
    }

    @Override
    public List<LandmarkPin> getActivePins(Timestamp now) {
        List<LandmarkPin> list = new ArrayList<>();
        String sql = "SELECT * FROM " + tablePrefix + "landmark_pins WHERE expires_at>? ORDER BY slot_index";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapPin(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL active landmark pin query failed: " + e.getMessage());
        }
        return list;
    }

    @Override
    public List<LandmarkPin> getAllPinSnapshots() {
        List<LandmarkPin> list = new ArrayList<>();
        String sql = "SELECT * FROM " + tablePrefix + "landmark_pins ORDER BY slot_index";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapPin(rs));
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL landmark pin snapshot query failed: " + e.getMessage());
        }
        return list;
    }

    private LandmarkPin getPinBySlot(int slotIndex) {
        String sql = "SELECT * FROM " + tablePrefix + "landmark_pins WHERE slot_index=?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, slotIndex);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapPin(rs);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL landmark pin by slot query failed: " + e.getMessage());
        }
        return null;
    }

    @Override
    public void upsertPlayerRecord(UUID playerUuid, String playerName) {
        upsertPlayerRecord(playerUuid, playerName, null, null);
    }

    @Override
    public void upsertPlayerRecord(UUID playerUuid, String playerName, String textureValue, String textureSignature) {
        if (playerUuid == null || playerName == null || playerName.isBlank()) return;
        String nameLower = playerName.toLowerCase(java.util.Locale.ROOT);
        PlayerSkin existingSkin = getPlayerSkinByUuidOrName(playerUuid, nameLower);
        String storedTextureValue = hasText(textureValue)
                ? textureValue
                : existingSkin != null ? existingSkin.textureValue() : null;
        String storedTextureSignature = hasText(textureValue)
                ? textureSignature
                : existingSkin != null ? existingSkin.textureSignature() : null;
        try (Connection conn = getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM " + tablePrefix + "players WHERE player_uuid=? OR name_lower=?")) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, nameLower);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + tablePrefix + "players " +
                            "(player_uuid, player_name, name_lower, skin_texture_value, skin_texture_signature, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, playerName);
                ps.setString(3, nameLower);
                ps.setString(4, storedTextureValue);
                ps.setString(5, storedTextureSignature);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL player record update failed: " + e.getMessage());
        }
    }

    private PlayerSkin getPlayerSkinByUuidOrName(UUID playerUuid, String nameLower) {
        if (playerUuid == null || nameLower == null || nameLower.isBlank()) return null;
        String sql = "SELECT player_uuid, player_name, skin_texture_value, skin_texture_signature " +
                "FROM " + tablePrefix + "players WHERE player_uuid=? OR name_lower=? " +
                "ORDER BY CASE WHEN player_uuid=? THEN 0 ELSE 1 END LIMIT 1";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, nameLower);
            ps.setString(3, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapPlayerSkin(rs);
            }
        } catch (SQLException | IllegalArgumentException ignored) {
        }
        return null;
    }

    @Override
    public PlayerRecord getPlayerRecordByName(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        String nameLower = playerName.toLowerCase(java.util.Locale.ROOT);
        String sql = "SELECT player_uuid, player_name FROM " + tablePrefix + "players WHERE name_lower=?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nameLower);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PlayerRecord(UUID.fromString(rs.getString("player_uuid")), rs.getString("player_name"));
                }
            }
        } catch (SQLException | IllegalArgumentException e) {
            plugin.getLogger().severe("MySQL player record query failed: " + e.getMessage());
        }
        PlayerRecord legacy = findPlayerRecordByNameInLegacyTables(nameLower);
        if (legacy != null) upsertPlayerRecord(legacy.playerUuid(), legacy.playerName());
        return legacy;
    }

    @Override
    public List<PlayerSkin> getRecentPlayerSkins(int limit) {
        List<PlayerSkin> skins = new ArrayList<>();
        int cappedLimit = Math.max(1, limit);
        String sql = "SELECT player_uuid, player_name, skin_texture_value, skin_texture_signature " +
                "FROM " + tablePrefix + "players " +
                "WHERE skin_texture_value IS NOT NULL AND skin_texture_value <> '' " +
                "ORDER BY updated_at DESC LIMIT ?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, cappedLimit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    skins.add(mapPlayerSkin(rs));
                }
            }
        } catch (SQLException | IllegalArgumentException e) {
            plugin.getLogger().severe("MySQL player skins query failed: " + e.getMessage());
        }
        return skins;
    }

    private PlayerRecord findPlayerRecordByNameInLegacyTables(String nameLower) {
        String[] queries = {
                "SELECT owner_uuid AS player_uuid, owner_name AS player_name FROM " + tablePrefix + "landmarks WHERE LOWER(owner_name)=? LIMIT 1",
                "SELECT player_uuid, player_name FROM " + tablePrefix + "landmark_managers WHERE LOWER(player_name)=? LIMIT 1",
                "SELECT player_uuid, player_name FROM " + tablePrefix + "landmark_blacklists WHERE LOWER(player_name)=? LIMIT 1",
                "SELECT player_uuid, player_name FROM " + tablePrefix + "ratings WHERE LOWER(player_name)=? LIMIT 1",
                "SELECT player_uuid, player_name FROM " + tablePrefix + "transactions WHERE LOWER(player_name)=? LIMIT 1",
                "SELECT owner_uuid AS player_uuid, owner_name AS player_name FROM " + tablePrefix + "incomes WHERE LOWER(owner_name)=? LIMIT 1"
        };
        for (String query : queries) {
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setString(1, nameLower);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new PlayerRecord(UUID.fromString(rs.getString("player_uuid")), rs.getString("player_name"));
                    }
                }
            } catch (SQLException | IllegalArgumentException e) {
                plugin.getLogger().severe("MySQL legacy player record query failed: " + e.getMessage());
            }
        }
        return null;
    }

    private PlayerSkin mapPlayerSkin(ResultSet rs) throws SQLException {
        return new PlayerSkin(
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("player_name"),
                rs.getString("skin_texture_value"),
                rs.getString("skin_texture_signature"));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @FunctionalInterface
    private interface SqlPreparer {
        void prepare(PreparedStatement ps) throws SQLException;
    }

    private List<Landmark> queryLandmarks(String sql, SqlPreparer preparer) {
        List<Landmark> list = new ArrayList<>();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            if (preparer != null) preparer.prepare(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapLandmark(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL landmark list query failed: " + e.getMessage());
        }
        return list;
    }

    private boolean executeUpdate(String sql, SqlPreparer preparer) {
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            if (preparer != null) preparer.prepare(ps);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL database operation failed: " + e.getMessage());
            return false;
        }
    }

    private int executeUpdateCount(String sql, SqlPreparer preparer) {
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            if (preparer != null) preparer.prepare(ps);
            return ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL batch database operation failed: " + e.getMessage());
            return 0;
        }
    }

    private int queryCount(String sql, SqlPreparer preparer) {
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            if (preparer != null) preparer.prepare(ps);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL count query failed: " + e.getMessage());
        }
        return 0;
    }

    private void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private boolean isDuplicateKey(SQLException e) {
        return "23000".equals(e.getSQLState()) || e.getErrorCode() == 1062;
    }

    private void addColumnIfMissing(String table, String column, String definition) throws SQLException {
        String sql = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) return;
            }
        }
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private void createIndexIfMissing(String indexName, String table, String columns) throws SQLException {
        String sql = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME=? AND INDEX_NAME=?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) return;
            }
        }
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE INDEX " + indexName + " ON " + table + "(" + columns + ")");
        }
    }

    private String serializeIconData(Map<String, Object> iconData) {
        if (iconData == null || iconData.isEmpty()) return null;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("item", iconData);
        return yaml.saveToString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deserializeIconData(String data) {
        if (data == null || data.isBlank()) return null;
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(data);
            Object value = yaml.get("item");
            if (value instanceof ItemStack stack) {
                return stack.serialize();
            }
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null) {
                        result.put(entry.getKey().toString(), entry.getValue());
                    }
                }
                return result;
            }
            if (value instanceof org.bukkit.configuration.ConfigurationSection section) {
                return (Map<String, Object>) section.getValues(false);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("MySQL icon data parse failed: " + e.getMessage());
        }
        return null;
    }

    private Landmark mapLandmark(ResultSet rs) throws SQLException {
        Landmark lm = new Landmark();
        lm.setId(rs.getInt("id"));
        lm.setName(rs.getString("name"));
        lm.setDescription(rs.getString("description"));
        lm.setNameColor(getOptionalString(rs, "name_color"));
        lm.setDescriptionColor(getOptionalString(rs, "description_color"));
        lm.setNameBold(getOptionalBoolean(rs, "name_bold"));
        lm.setDescriptionBold(getOptionalBoolean(rs, "description_bold"));
        lm.setIcon(rs.getString("icon"));
        lm.setIconCustomModelData(rs.getObject("icon_custom_model_data") != null ? rs.getInt("icon_custom_model_data") : null);
        lm.setIconPluginItem(rs.getString("icon_plugin_item"));
        lm.setIconData(deserializeIconData(getOptionalString(rs, "icon_data")));
        lm.setOwnerUuid(UUID.fromString(rs.getString("owner_uuid")));
        lm.setOwnerName(rs.getString("owner_name"));
        lm.setServerName(rs.getString("server_name"));
        lm.setWorld(rs.getString("world"));
        lm.setX(rs.getInt("x"));
        lm.setY(rs.getInt("y"));
        lm.setZ(rs.getInt("z"));
        lm.setCategoryId(rs.getInt("category_id"));
        lm.setPrice(rs.getDouble("price"));
        lm.setVisitCount(rs.getInt("visit_count"));
        lm.setWeeklyVisits(rs.getInt("weekly_visits"));
        lm.setPrivate(rs.getBoolean("is_private"));
        lm.setGlobal(rs.getBoolean("is_global"));
        lm.setCreatedAt(rs.getTimestamp("created_at"));
        lm.setUpdatedAt(rs.getTimestamp("updated_at"));
        return lm;
    }

    private String getOptionalString(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private boolean getOptionalBoolean(ResultSet rs, String column) {
        try {
            return rs.getBoolean(column);
        } catch (SQLException ignored) {
            return false;
        }
    }

    private Category mapCategory(ResultSet rs) throws SQLException {
        Category c = new Category();
        c.setId(rs.getInt("id"));
        c.setName(rs.getString("name"));
        c.setIcon(rs.getString("icon"));
        c.setIconCustomModelData(rs.getObject("icon_custom_model_data") != null ? rs.getInt("icon_custom_model_data") : null);
        c.setIconPluginItem(rs.getString("icon_plugin_item"));
        c.setColor(rs.getString("color"));
        c.setDefault(rs.getBoolean("is_default"));
        return c;
    }

    private Rating mapRating(ResultSet rs) throws SQLException {
        Rating r = new Rating();
        r.setId(rs.getInt("id"));
        r.setLandmarkId(rs.getInt("landmark_id"));
        r.setPlayerUuid(UUID.fromString(rs.getString("player_uuid")));
        r.setPlayerName(rs.getString("player_name"));
        r.setScore(rs.getInt("score"));
        r.setComment(rs.getString("comment"));
        r.setCreatedAt(rs.getTimestamp("created_at"));
        return r;
    }

    private Transaction mapTransaction(ResultSet rs) throws SQLException {
        Transaction t = new Transaction();
        t.setId(rs.getInt("id"));
        t.setLandmarkId(rs.getInt("landmark_id"));
        t.setPlayerUuid(UUID.fromString(rs.getString("player_uuid")));
        t.setPlayerName(rs.getString("player_name"));
        t.setAmount(rs.getDouble("amount"));
        t.setTaxAmount(rs.getDouble("tax_amount"));
        t.setType(Transaction.Type.valueOf(rs.getString("type")));
        t.setCreatedAt(rs.getTimestamp("created_at"));
        return t;
    }

    private LandmarkAdmin mapAdmin(ResultSet rs) throws SQLException {
        LandmarkAdmin a = new LandmarkAdmin();
        a.setId(rs.getInt("id"));
        a.setLandmarkId(rs.getInt("landmark_id"));
        a.setPlayerUuid(UUID.fromString(rs.getString("player_uuid")));
        a.setPlayerName(rs.getString("player_name"));
        a.setAddedAt(rs.getTimestamp("added_at"));
        return a;
    }

    private LandmarkBlacklist mapBlacklist(ResultSet rs) throws SQLException {
        LandmarkBlacklist b = new LandmarkBlacklist();
        b.setId(rs.getInt("id"));
        b.setLandmarkId(rs.getInt("landmark_id"));
        b.setPlayerUuid(UUID.fromString(rs.getString("player_uuid")));
        b.setPlayerName(rs.getString("player_name"));
        b.setAddedAt(rs.getTimestamp("added_at"));
        return b;
    }

    private LandmarkPin mapPin(ResultSet rs) throws SQLException {
        LandmarkPin pin = new LandmarkPin();
        pin.setId(rs.getInt("id"));
        pin.setSlotIndex(rs.getInt("slot_index"));
        pin.setLandmarkId(rs.getInt("landmark_id"));
        pin.setBuyerUuid(UUID.fromString(rs.getString("buyer_uuid")));
        pin.setBuyerName(rs.getString("buyer_name"));
        pin.setCreatedAt(rs.getTimestamp("created_at"));
        pin.setExpiresAt(rs.getTimestamp("expires_at"));
        return pin;
    }
}
