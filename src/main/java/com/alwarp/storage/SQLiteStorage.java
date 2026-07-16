package com.alwarp.storage;

import com.alwarp.model.*;
import com.alwarp.scheduler.FoliaScheduler;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SQLite数据存储实现。
 * 使用单连接模式，所有数据库操作在异步线程执行。
 */
public class SQLiteStorage implements StorageManager {

    private final Plugin plugin;
    private final FoliaScheduler scheduler;
    private final String tablePrefix;
    private Connection connection;

    public SQLiteStorage(Plugin plugin, FoliaScheduler scheduler, String tablePrefix) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.tablePrefix = tablePrefix;
    }

    @Override
    public void init() {
        File dbFile = new File(plugin.getDataFolder(), "alwarp.db");
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            createTables();
            if (plugin instanceof com.alwarp.ALwarp alwarp) {
                alwarp.logInfo("SQLite 数据库初始化完成", "SQLite database initialized");
            } else {
                plugin.getLogger().info("SQLite database initialized");
            }
        } catch (Exception e) {
            String prefix = plugin instanceof com.alwarp.ALwarp alwarp
                    ? alwarp.localize("SQLite 数据库初始化失败: ", "SQLite database initialization failed: ")
                    : "SQLite database initialization failed: ";
            plugin.getLogger().severe(prefix + e.getMessage());
        }
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("关闭SQLite连接失败: " + e.getMessage());
        }
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

        Statement st = connection.createStatement();

        st.executeUpdate("CREATE TABLE IF NOT EXISTS " + lm + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name VARCHAR(100) NOT NULL," +
                "description TEXT," +
                "name_color VARCHAR(32) DEFAULT NULL," +
                "description_color VARCHAR(32) DEFAULT NULL," +
                "name_bold BOOLEAN DEFAULT FALSE," +
                "description_bold BOOLEAN DEFAULT FALSE," +
                "icon VARCHAR(50) DEFAULT 'COMPASS'," +
                "icon_custom_model_data INTEGER DEFAULT NULL," +
                "icon_plugin_item VARCHAR(100) DEFAULT NULL," +
                "icon_data TEXT DEFAULT NULL," +
                "owner_uuid VARCHAR(36) NOT NULL," +
                "owner_name VARCHAR(16) NOT NULL," +
                "server_name VARCHAR(50) NOT NULL," +
                "world VARCHAR(100) NOT NULL," +
                "x INTEGER NOT NULL," +
                "y INTEGER NOT NULL," +
                "z INTEGER NOT NULL," +
                "category_id INTEGER DEFAULT 1," +
                "price DOUBLE DEFAULT 0.0," +
                "visit_count INTEGER DEFAULT 0," +
                "weekly_visits INTEGER DEFAULT 0," +
                "is_private BOOLEAN DEFAULT FALSE," +
                "is_global BOOLEAN DEFAULT TRUE," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")");

        addColumnIfMissing(lm, "name_color", "VARCHAR(32) DEFAULT NULL");
        addColumnIfMissing(lm, "description_color", "VARCHAR(32) DEFAULT NULL");
        addColumnIfMissing(lm, "name_bold", "BOOLEAN DEFAULT FALSE");
        addColumnIfMissing(lm, "description_bold", "BOOLEAN DEFAULT FALSE");
        addColumnIfMissing(lm, "icon_data", "TEXT DEFAULT NULL");

        st.executeUpdate("CREATE TABLE IF NOT EXISTS " + ct + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name VARCHAR(50) NOT NULL," +
                "icon VARCHAR(50) DEFAULT 'MAP'," +
                "icon_custom_model_data INTEGER DEFAULT NULL," +
                "icon_plugin_item VARCHAR(100) DEFAULT NULL," +
                "color VARCHAR(10) DEFAULT '&7'," +
                "is_default BOOLEAN DEFAULT FALSE" +
                ")");

        st.executeUpdate("CREATE TABLE IF NOT EXISTS " + mg + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "landmark_id INTEGER NOT NULL," +
                "player_uuid VARCHAR(36) NOT NULL," +
                "player_name VARCHAR(16) NOT NULL," +
                "added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "FOREIGN KEY(landmark_id) REFERENCES " + lm + "(id) ON DELETE CASCADE" +
                ")");

        st.executeUpdate("CREATE TABLE IF NOT EXISTS " + bl + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "landmark_id INTEGER NOT NULL," +
                "player_uuid VARCHAR(36) NOT NULL," +
                "player_name VARCHAR(16) NOT NULL," +
                "added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "UNIQUE(landmark_id, player_uuid)," +
                "FOREIGN KEY(landmark_id) REFERENCES " + lm + "(id) ON DELETE CASCADE" +
                ")");

        st.executeUpdate("CREATE TABLE IF NOT EXISTS " + rt + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "landmark_id INTEGER NOT NULL," +
                "player_uuid VARCHAR(36) NOT NULL," +
                "player_name VARCHAR(16) NOT NULL," +
                "score INTEGER NOT NULL CHECK(score BETWEEN 1 AND 5)," +
                "comment TEXT," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "FOREIGN KEY(landmark_id) REFERENCES " + lm + "(id) ON DELETE CASCADE" +
                ")");

        st.executeUpdate("CREATE TABLE IF NOT EXISTS " + lv + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "player_uuid VARCHAR(36) NOT NULL," +
                "landmark_id INTEGER NOT NULL," +
                "visited_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "UNIQUE(player_uuid, landmark_id)," +
                "FOREIGN KEY(landmark_id) REFERENCES " + lm + "(id) ON DELETE CASCADE" +
                ")");

        st.executeUpdate("CREATE TABLE IF NOT EXISTS " + rr + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "rating_id INTEGER NOT NULL," +
                "player_uuid VARCHAR(36) NOT NULL," +
                "reported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "UNIQUE(rating_id, player_uuid)," +
                "FOREIGN KEY(rating_id) REFERENCES " + rt + "(id) ON DELETE CASCADE" +
                ")");

        st.executeUpdate("CREATE TABLE IF NOT EXISTS " + tx + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "landmark_id INTEGER NOT NULL," +
                "player_uuid VARCHAR(36) NOT NULL," +
                "player_name VARCHAR(16) NOT NULL," +
                "amount DOUBLE NOT NULL," +
                "tax_amount DOUBLE DEFAULT 0.0," +
                "type VARCHAR(20) NOT NULL," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "FOREIGN KEY(landmark_id) REFERENCES " + lm + "(id) ON DELETE CASCADE" +
                ")");

        st.executeUpdate("CREATE TABLE IF NOT EXISTS " + ic + " (" +
                "owner_uuid VARCHAR(36) PRIMARY KEY," +
                "owner_name VARCHAR(16) NOT NULL," +
                "amount DOUBLE NOT NULL DEFAULT 0.0," +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")");

        st.executeUpdate("CREATE TABLE IF NOT EXISTS " + pr + " (" +
                "player_uuid VARCHAR(36) PRIMARY KEY," +
                "player_name VARCHAR(16) NOT NULL," +
                "name_lower VARCHAR(16) NOT NULL UNIQUE," +
                "skin_texture_value TEXT DEFAULT NULL," +
                "skin_texture_signature TEXT DEFAULT NULL," +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")");
        addColumnIfMissing(pr, "skin_texture_value", "TEXT DEFAULT NULL");
        addColumnIfMissing(pr, "skin_texture_signature", "TEXT DEFAULT NULL");

        st.executeUpdate("CREATE TABLE IF NOT EXISTS " + fv + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "player_uuid VARCHAR(36) NOT NULL," +
                "landmark_id INTEGER NOT NULL," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "UNIQUE(player_uuid, landmark_id)," +
                "FOREIGN KEY(landmark_id) REFERENCES " + lm + "(id) ON DELETE CASCADE" +
                ")");

        st.executeUpdate("CREATE TABLE IF NOT EXISTS " + pn + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "slot_index INTEGER NOT NULL UNIQUE," +
                "landmark_id INTEGER NOT NULL," +
                "buyer_uuid VARCHAR(36) NOT NULL," +
                "buyer_name VARCHAR(16) NOT NULL," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "expires_at TIMESTAMP NOT NULL," +
                "FOREIGN KEY(landmark_id) REFERENCES " + lm + "(id) ON DELETE CASCADE" +
                ")");

        // 创建索引
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "landmarks_owner ON " + lm + "(owner_uuid)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "landmarks_category ON " + lm + "(category_id)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "landmarks_server ON " + lm + "(server_name)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "landmarks_popularity ON " + lm + "(weekly_visits DESC)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "ratings_landmark ON " + rt + "(landmark_id)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "ratings_player_landmark ON " + rt + "(player_uuid, landmark_id)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "visits_player_landmark ON " + lv + "(player_uuid, landmark_id)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "visits_landmark ON " + lv + "(landmark_id)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "reports_rating ON " + rr + "(rating_id)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "reports_player ON " + rr + "(player_uuid)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "transactions_landmark ON " + tx + "(landmark_id)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "incomes_amount ON " + ic + "(amount)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "managers_landmark ON " + mg + "(landmark_id)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "managers_landmark_player ON " + mg + "(landmark_id, player_uuid)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "blacklists_landmark ON " + bl + "(landmark_id)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "blacklists_landmark_player ON " + bl + "(landmark_id, player_uuid)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "favorites_player ON " + fv + "(player_uuid)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "favorites_landmark ON " + fv + "(landmark_id)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "pins_slot ON " + pn + "(slot_index)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "pins_landmark ON " + pn + "(landmark_id)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "pins_expires ON " + pn + "(expires_at)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "players_name_lower ON " + pr + "(name_lower)");
        st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + tablePrefix + "players_updated_at ON " + pr + "(updated_at DESC)");

        st.close();
        // Enable foreign keys
        connection.createStatement().execute("PRAGMA foreign_keys = ON");
    }

    // ─── Landmark CRUD ───

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
                "ON CONFLICT(id) DO UPDATE SET " +
                "name=excluded.name, description=excluded.description, " +
                "name_color=excluded.name_color, description_color=excluded.description_color, " +
                "name_bold=excluded.name_bold, description_bold=excluded.description_bold, icon=excluded.icon, " +
                "icon_custom_model_data=excluded.icon_custom_model_data, " +
                "icon_plugin_item=excluded.icon_plugin_item, icon_data=excluded.icon_data, " +
                "owner_uuid=excluded.owner_uuid, owner_name=excluded.owner_name, " +
                "server_name=excluded.server_name, world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z, " +
                "category_id=excluded.category_id, price=excluded.price, visit_count=excluded.visit_count, " +
                "weekly_visits=excluded.weekly_visits, is_private=excluded.is_private, " +
                "is_global=excluded.is_global, created_at=excluded.created_at, updated_at=excluded.updated_at";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
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
            plugin.getLogger().severe("导入地标快照失败: " + e.getMessage());
            return null;
        }
    }

    private Landmark insertLandmark(Landmark landmark, int newId) {
        String sql = "INSERT INTO " + tablePrefix + "landmarks " +
                "(id, name, description, name_color, description_color, name_bold, description_bold, icon, icon_custom_model_data, icon_plugin_item, icon_data, " +
                "owner_uuid, owner_name, server_name, world, x, y, z, category_id, price) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
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
            plugin.getLogger().severe("创建地标失败: " + e.getMessage());
            return null;
        }
    }

    @Override
    public int getNextAvailableLandmarkId() {
        String sql = "SELECT id FROM " + tablePrefix + "landmarks ORDER BY id ASC";
        int expected = 1;
        try (Statement st = connection.createStatement();
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
            plugin.getLogger().severe("查询下一个地标ID失败: " + e.getMessage());
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
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
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
            plugin.getLogger().severe("更新地标失败: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean deleteLandmark(int id) {
        String sql = "DELETE FROM " + tablePrefix + "landmarks WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().severe("删除地标失败: " + e.getMessage());
            return false;
        }
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
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return mapLandmark(rs);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("查询地标失败: " + e.getMessage());
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
        try (Statement st = connection.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + tablePrefix + "landmarks");
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            plugin.getLogger().severe("查询地标数量失败: " + e.getMessage());
        }
        return 0;
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
                "INSERT OR IGNORE INTO " + tablePrefix + "landmark_visits (player_uuid, landmark_id) VALUES (?,?)",
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
        executeUpdate("UPDATE " + tablePrefix + "landmarks SET weekly_visits = MAX(0, weekly_visits + ?) WHERE id=?",
                ps -> {
                    ps.setInt(1, delta);
                    ps.setInt(2, landmarkId);
                });
    }

    // ─── Favorite CRUD ───

    @Override
    public boolean addFavorite(UUID playerUuid, int landmarkId) {
        return executeUpdate("INSERT OR IGNORE INTO " + tablePrefix + "favorites (player_uuid, landmark_id) VALUES (?,?)",
                ps -> {
                    ps.setString(1, playerUuid.toString());
                    ps.setInt(2, landmarkId);
                });
    }

    @Override
    public boolean importFavoriteSnapshot(FavoriteSnapshot favorite) {
        return executeUpdate(
                "INSERT OR REPLACE INTO " + tablePrefix + "favorites (player_uuid, landmark_id, created_at) VALUES (?,?,COALESCE(?, CURRENT_TIMESTAMP))",
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
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt("landmark_id"));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("查询收藏失败: " + e.getMessage());
        }
        return ids;
    }

    @Override
    public Map<UUID, List<Integer>> getAllFavorites() {
        Map<UUID, List<Integer>> result = new HashMap<>();
        String sql = "SELECT player_uuid, landmark_id FROM " + tablePrefix + "favorites ORDER BY created_at DESC, id DESC";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                result.computeIfAbsent(uuid, k -> new ArrayList<>()).add(rs.getInt("landmark_id"));
            }
        } catch (SQLException | IllegalArgumentException e) {
            plugin.getLogger().severe("加载收藏缓存失败: " + e.getMessage());
        }
        return result;
    }

    @Override
    public List<FavoriteSnapshot> getAllFavoriteSnapshots() {
        List<FavoriteSnapshot> result = new ArrayList<>();
        String sql = "SELECT player_uuid, landmark_id, created_at FROM " + tablePrefix + "favorites ORDER BY created_at DESC, id DESC";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(new FavoriteSnapshot(
                        UUID.fromString(rs.getString("player_uuid")),
                        rs.getInt("landmark_id"),
                        rs.getTimestamp("created_at")));
            }
        } catch (SQLException | IllegalArgumentException e) {
            plugin.getLogger().severe("导出收藏快照失败: " + e.getMessage());
        }
        return result;
    }

    // ─── Category CRUD ───

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
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
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
                ResultSet rs = ps.getGeneratedKeys();
                if (rs.next()) category.setId(rs.getInt(1));
            }
            return category;
        } catch (SQLException e) {
            plugin.getLogger().severe("创建分类失败: " + e.getMessage());
            return null;
        }
    }

    @Override
    public boolean updateCategory(Category category) {
        String sql = "UPDATE " + tablePrefix + "categories SET name=?, icon=?, icon_custom_model_data=?, " +
                "icon_plugin_item=?, color=?, is_default=? WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, category.getName());
            ps.setString(2, category.getIcon());
            setNullableInt(ps, 3, category.getIconCustomModelData());
            ps.setString(4, category.getIconPluginItem());
            ps.setString(5, category.getColor());
            ps.setBoolean(6, category.isDefault());
            ps.setInt(7, category.getId());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().severe("更新分类失败: " + e.getMessage());
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
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return mapCategory(rs);
        } catch (SQLException e) {
            plugin.getLogger().severe("查询分类失败: " + e.getMessage());
        }
        return null;
    }

    @Override
    public Category getCategoryByName(String name) {
        String sql = "SELECT * FROM " + tablePrefix + "categories WHERE name=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return mapCategory(rs);
        } catch (SQLException e) {
            plugin.getLogger().severe("查询分类失败: " + e.getMessage());
        }
        return null;
    }

    @Override
    public List<Category> getAllCategories() {
        List<Category> list = new ArrayList<>();
        try (Statement st = connection.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT * FROM " + tablePrefix + "categories ORDER BY id");
            while (rs.next()) list.add(mapCategory(rs));
        } catch (SQLException e) {
            plugin.getLogger().severe("查询全部分类失败: " + e.getMessage());
        }
        return list;
    }

    // ─── Rating CRUD ───

    @Override
    public Rating addRating(Rating rating) {
        String sql = rating.getCreatedAt() != null
                ? "INSERT INTO " + tablePrefix + "ratings (landmark_id, player_uuid, player_name, score, comment, created_at) VALUES (?,?,?,?,?,?)"
                : "INSERT INTO " + tablePrefix + "ratings (landmark_id, player_uuid, player_name, score, comment) VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, rating.getLandmarkId());
            ps.setString(2, rating.getPlayerUuid().toString());
            ps.setString(3, rating.getPlayerName());
            ps.setInt(4, rating.getScore());
            ps.setString(5, rating.getComment());
            if (rating.getCreatedAt() != null) ps.setTimestamp(6, rating.getCreatedAt());
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) rating.setId(rs.getInt(1));
            return rating;
        } catch (SQLException e) {
            plugin.getLogger().severe("添加评分失败: " + e.getMessage());
            return null;
        }
    }

    @Override
    public boolean updateRating(Rating rating) {
        String sql = "UPDATE " + tablePrefix + "ratings SET score=?, comment=?, created_at=CURRENT_TIMESTAMP WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, rating.getScore());
            ps.setString(2, rating.getComment());
            ps.setInt(3, rating.getId());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().severe("更新评分失败: " + e.getMessage());
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
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, landmarkId);
            ps.setString(2, playerUuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return mapRating(rs);
        } catch (SQLException e) {
            plugin.getLogger().severe("查询评分失败: " + e.getMessage());
        }
        return null;
    }

    @Override
    public List<Rating> getRatingsByLandmark(int landmarkId) {
        List<Rating> list = new ArrayList<>();
        String sql = "SELECT * FROM " + tablePrefix + "ratings WHERE landmark_id=? ORDER BY created_at DESC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, landmarkId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapRating(rs));
        } catch (SQLException e) {
            plugin.getLogger().severe("查询地标评分失败: " + e.getMessage());
        }
        return list;
    }

    @Override
    public double getAverageRating(int landmarkId) {
        String sql = "SELECT AVG(score) FROM " + tablePrefix + "ratings WHERE landmark_id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, landmarkId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getDouble(1);
        } catch (SQLException e) {
            plugin.getLogger().severe("查询平均评分失败: " + e.getMessage());
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
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                stats.put(rs.getInt("landmark_id"),
                        new RatingStats(rs.getInt("rating_count"), rs.getDouble("rating_average")));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("查询评分统计失败: " + e.getMessage());
        }
        return stats;
    }

    // ─── Transaction CRUD ───

    @Override
    public boolean addRatingReport(int ratingId, UUID playerUuid) {
        if (ratingId <= 0 || playerUuid == null) return false;
        return executeUpdate(
                "INSERT OR IGNORE INTO " + tablePrefix + "rating_reports (rating_id, player_uuid) VALUES (?,?)",
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
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, transaction.getLandmarkId());
            ps.setString(2, transaction.getPlayerUuid().toString());
            ps.setString(3, transaction.getPlayerName());
            ps.setDouble(4, transaction.getAmount());
            ps.setDouble(5, transaction.getTaxAmount());
            ps.setString(6, transaction.getType().name());
            if (transaction.getCreatedAt() != null) ps.setTimestamp(7, transaction.getCreatedAt());
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) transaction.setId(rs.getInt(1));
            return transaction;
        } catch (SQLException e) {
            plugin.getLogger().severe("创建交易记录失败: " + e.getMessage());
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
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM " + tablePrefix + "transactions WHERE landmark_id=? ORDER BY created_at DESC")) {
            ps.setInt(1, landmarkId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapTransaction(rs));
        } catch (SQLException e) {
            plugin.getLogger().severe("查询交易记录失败: " + e.getMessage());
        }
        return list;
    }

    @Override
    public List<Transaction> getTransactionsByPlayer(UUID playerUuid) {
        List<Transaction> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM " + tablePrefix + "transactions WHERE player_uuid=? ORDER BY created_at DESC")) {
            ps.setString(1, playerUuid.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapTransaction(rs));
        } catch (SQLException e) {
            plugin.getLogger().severe("查询玩家交易失败: " + e.getMessage());
        }
        return list;
    }

    // ─── Manager operations ───

    @Override
    public boolean addPendingIncome(UUID ownerUuid, String ownerName, double amount) {
        if (ownerUuid == null || amount <= 0) return false;
        String sql = "INSERT INTO " + tablePrefix + "incomes (owner_uuid, owner_name, amount, updated_at) " +
                "VALUES (?, ?, ?, CURRENT_TIMESTAMP) " +
                "ON CONFLICT(owner_uuid) DO UPDATE SET " +
                "owner_name=excluded.owner_name, amount=amount + excluded.amount, updated_at=CURRENT_TIMESTAMP";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ownerUuid.toString());
            ps.setString(2, ownerName != null ? ownerName : "Unknown");
            ps.setDouble(3, amount);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().severe("Add pending income failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public double getPendingIncome(UUID ownerUuid) {
        if (ownerUuid == null) return 0.0;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT amount FROM " + tablePrefix + "incomes WHERE owner_uuid=?")) {
            ps.setString(1, ownerUuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getDouble("amount");
        } catch (SQLException e) {
            plugin.getLogger().severe("Query pending income failed: " + e.getMessage());
        }
        return 0.0;
    }

    @Override
    public double claimPendingIncome(UUID ownerUuid) {
        if (ownerUuid == null) return 0.0;
        boolean oldAutoCommit = true;
        try {
            oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            double amount = getPendingIncome(ownerUuid);
            if (amount <= 0) {
                connection.commit();
                return 0.0;
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE " + tablePrefix + "incomes SET amount=0, updated_at=CURRENT_TIMESTAMP WHERE owner_uuid=?")) {
                ps.setString(1, ownerUuid.toString());
                ps.executeUpdate();
            }
            connection.commit();
            return amount;
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ignored) {}
            plugin.getLogger().severe("Claim pending income failed: " + e.getMessage());
            return 0.0;
        } finally {
            try { connection.setAutoCommit(oldAutoCommit); } catch (SQLException ignored) {}
        }
    }

    @Override
    public List<IncomeSnapshot> getAllIncomeSnapshots() {
        List<IncomeSnapshot> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM " + tablePrefix + "incomes WHERE amount > 0 ORDER BY updated_at DESC")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new IncomeSnapshot(
                        UUID.fromString(rs.getString("owner_uuid")),
                        rs.getString("owner_name"),
                        rs.getDouble("amount"),
                        rs.getTimestamp("updated_at")
                ));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Query pending income snapshots failed: " + e.getMessage());
        }
        return list;
    }

    @Override
    public boolean importIncomeSnapshot(IncomeSnapshot income) {
        if (income == null || income.ownerUuid() == null || income.amount() <= 0) return false;
        String sql = "INSERT INTO " + tablePrefix + "incomes (owner_uuid, owner_name, amount, updated_at) " +
                "VALUES (?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP)) " +
                "ON CONFLICT(owner_uuid) DO UPDATE SET " +
                "owner_name=excluded.owner_name, amount=excluded.amount, updated_at=excluded.updated_at";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, income.ownerUuid().toString());
            ps.setString(2, income.ownerName() != null ? income.ownerName() : "Unknown");
            ps.setDouble(3, income.amount());
            ps.setTimestamp(4, income.updatedAt());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().severe("Import pending income failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public LandmarkAdmin addManager(LandmarkAdmin admin) {
        String sql = admin.getAddedAt() != null
                ? "INSERT INTO " + tablePrefix + "landmark_managers (landmark_id, player_uuid, player_name, added_at) VALUES (?,?,?,?)"
                : "INSERT INTO " + tablePrefix + "landmark_managers (landmark_id, player_uuid, player_name) VALUES (?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, admin.getLandmarkId());
            ps.setString(2, admin.getPlayerUuid().toString());
            ps.setString(3, admin.getPlayerName());
            if (admin.getAddedAt() != null) ps.setTimestamp(4, admin.getAddedAt());
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) admin.setId(rs.getInt(1));
            return admin;
        } catch (SQLException e) {
            plugin.getLogger().severe("添加管理员失败: " + e.getMessage());
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
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM " + tablePrefix + "landmark_managers WHERE landmark_id=? ORDER BY added_at")) {
            ps.setInt(1, landmarkId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapAdmin(rs));
        } catch (SQLException e) {
            plugin.getLogger().severe("查询管理员列表失败: " + e.getMessage());
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
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT landmark_id FROM " + tablePrefix + "landmark_managers WHERE player_uuid=?")) {
            ps.setString(1, playerUuid.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) ids.add(rs.getInt("landmark_id"));
        } catch (SQLException e) {
            plugin.getLogger().severe("查询管理地标失败: " + e.getMessage());
        }
        return ids;
    }

    @Override
    public void deleteAllManagers(int landmarkId) {
        executeUpdate("DELETE FROM " + tablePrefix + "landmark_managers WHERE landmark_id=?", ps -> ps.setInt(1, landmarkId));
    }

    @Override
    public LandmarkBlacklist addBlacklist(LandmarkBlacklist blacklist) {
        String sql = blacklist.getAddedAt() != null
                ? "INSERT INTO " + tablePrefix + "landmark_blacklists (landmark_id, player_uuid, player_name, added_at) VALUES (?,?,?,?)"
                : "INSERT INTO " + tablePrefix + "landmark_blacklists (landmark_id, player_uuid, player_name) VALUES (?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, blacklist.getLandmarkId());
            ps.setString(2, blacklist.getPlayerUuid().toString());
            ps.setString(3, blacklist.getPlayerName());
            if (blacklist.getAddedAt() != null) ps.setTimestamp(4, blacklist.getAddedAt());
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) blacklist.setId(rs.getInt(1));
            return blacklist;
        } catch (SQLException e) {
            if (!isDuplicateKey(e)) {
                plugin.getLogger().severe("添加黑名单失败: " + e.getMessage());
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
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM " + tablePrefix + "landmark_blacklists WHERE landmark_id=? ORDER BY added_at")) {
            ps.setInt(1, landmarkId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapBlacklist(rs));
        } catch (SQLException e) {
            plugin.getLogger().severe("查询黑名单失败: " + e.getMessage());
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
        executeUpdate("DELETE FROM " + tablePrefix + "landmark_blacklists WHERE landmark_id=?", ps -> ps.setInt(1, landmarkId));
    }

    // ─── Helper methods ───

    @Override
    public LandmarkPin addPin(LandmarkPin pin) {
        String sql = pin.getCreatedAt() != null
                ? "INSERT INTO " + tablePrefix + "landmark_pins (slot_index, landmark_id, buyer_uuid, buyer_name, created_at, expires_at) VALUES (?,?,?,?,?,?)"
                : "INSERT INTO " + tablePrefix + "landmark_pins (slot_index, landmark_id, buyer_uuid, buyer_name, expires_at) VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
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
            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) pin.setId(rs.getInt(1));
            return pin;
        } catch (SQLException e) {
            if (!isDuplicateKey(e)) {
                plugin.getLogger().severe("Add landmark pin failed: " + e.getMessage());
            }
            return null;
        }
    }

    @Override
    public LandmarkPin importPinSnapshot(LandmarkPin pin) {
        String sql = "INSERT OR REPLACE INTO " + tablePrefix + "landmark_pins " +
                "(id, slot_index, landmark_id, buyer_uuid, buyer_name, created_at, expires_at) " +
                "VALUES (NULLIF(?, 0), ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP), ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, pin.getId());
            ps.setInt(2, pin.getSlotIndex());
            ps.setInt(3, pin.getLandmarkId());
            ps.setString(4, pin.getBuyerUuid().toString());
            ps.setString(5, pin.getBuyerName() != null ? pin.getBuyerName() : "Unknown");
            ps.setTimestamp(6, pin.getCreatedAt());
            ps.setTimestamp(7, pin.getExpiresAt());
            return ps.executeUpdate() > 0 ? getPinBySlot(pin.getSlotIndex()) : null;
        } catch (SQLException e) {
            plugin.getLogger().severe("Import landmark pin failed: " + e.getMessage());
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
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM " + tablePrefix + "landmark_pins WHERE slot_index=? AND expires_at>?")) {
            ps.setInt(1, slotIndex);
            ps.setTimestamp(2, now);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return mapPin(rs);
        } catch (SQLException e) {
            plugin.getLogger().severe("Query landmark pin failed: " + e.getMessage());
        }
        return null;
    }

    @Override
    public List<LandmarkPin> getActivePins(Timestamp now) {
        List<LandmarkPin> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM " + tablePrefix + "landmark_pins WHERE expires_at>? ORDER BY slot_index")) {
            ps.setTimestamp(1, now);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapPin(rs));
        } catch (SQLException e) {
            plugin.getLogger().severe("Query active landmark pins failed: " + e.getMessage());
        }
        return list;
    }

    @Override
    public List<LandmarkPin> getAllPinSnapshots() {
        List<LandmarkPin> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM " + tablePrefix + "landmark_pins ORDER BY slot_index");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapPin(rs));
        } catch (SQLException e) {
            plugin.getLogger().severe("Query landmark pin snapshots failed: " + e.getMessage());
        }
        return list;
    }

    private LandmarkPin getPinBySlot(int slotIndex) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM " + tablePrefix + "landmark_pins WHERE slot_index=?")) {
            ps.setInt(1, slotIndex);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return mapPin(rs);
        } catch (SQLException e) {
            plugin.getLogger().severe("Query landmark pin by slot failed: " + e.getMessage());
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
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM " + tablePrefix + "players WHERE player_uuid=? OR name_lower=?")) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, nameLower);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
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
            plugin.getLogger().severe("Update player record failed: " + e.getMessage());
        }
    }

    private PlayerSkin getPlayerSkinByUuidOrName(UUID playerUuid, String nameLower) {
        if (playerUuid == null || nameLower == null || nameLower.isBlank()) return null;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT player_uuid, player_name, skin_texture_value, skin_texture_signature " +
                        "FROM " + tablePrefix + "players WHERE player_uuid=? OR name_lower=? " +
                        "ORDER BY CASE WHEN player_uuid=? THEN 0 ELSE 1 END LIMIT 1")) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, nameLower);
            ps.setString(3, playerUuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return mapPlayerSkin(rs);
        } catch (SQLException | IllegalArgumentException ignored) {
        }
        return null;
    }

    @Override
    public PlayerRecord getPlayerRecordByName(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        String nameLower = playerName.toLowerCase(java.util.Locale.ROOT);
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT player_uuid, player_name FROM " + tablePrefix + "players WHERE name_lower=?")) {
            ps.setString(1, nameLower);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new PlayerRecord(UUID.fromString(rs.getString("player_uuid")), rs.getString("player_name"));
            }
        } catch (SQLException | IllegalArgumentException e) {
            plugin.getLogger().severe("Query player record failed: " + e.getMessage());
        }
        PlayerRecord legacy = findPlayerRecordByNameInLegacyTables(nameLower);
        if (legacy != null) upsertPlayerRecord(legacy.playerUuid(), legacy.playerName());
        return legacy;
    }

    @Override
    public List<PlayerSkin> getRecentPlayerSkins(int limit) {
        List<PlayerSkin> skins = new ArrayList<>();
        int cappedLimit = Math.max(1, limit);
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT player_uuid, player_name, skin_texture_value, skin_texture_signature " +
                        "FROM " + tablePrefix + "players " +
                        "WHERE skin_texture_value IS NOT NULL AND skin_texture_value <> '' " +
                        "ORDER BY updated_at DESC LIMIT ?")) {
            ps.setInt(1, cappedLimit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                skins.add(mapPlayerSkin(rs));
            }
        } catch (SQLException | IllegalArgumentException e) {
            plugin.getLogger().severe("Query player skins failed: " + e.getMessage());
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
        for (String sql : queries) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, nameLower);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    return new PlayerRecord(UUID.fromString(rs.getString("player_uuid")), rs.getString("player_name"));
                }
            } catch (SQLException | IllegalArgumentException e) {
                plugin.getLogger().severe("Query legacy player record failed: " + e.getMessage());
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
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (preparer != null) preparer.prepare(ps);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapLandmark(rs));
        } catch (SQLException e) {
            plugin.getLogger().severe("查询地标列表失败: " + e.getMessage());
        }
        return list;
    }

    private boolean executeUpdate(String sql, SqlPreparer preparer) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (preparer != null) preparer.prepare(ps);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().severe("数据库操作失败: " + e.getMessage());
            return false;
        }
    }

    private int executeUpdateCount(String sql, SqlPreparer preparer) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (preparer != null) preparer.prepare(ps);
            return ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("数据库批量操作失败: " + e.getMessage());
            return 0;
        }
    }

    private int queryCount(String sql, SqlPreparer preparer) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (preparer != null) preparer.prepare(ps);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            plugin.getLogger().severe("查询计数失败: " + e.getMessage());
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
        return "23000".equals(e.getSQLState())
                || e.getErrorCode() == 19
                || (e.getMessage() != null && e.getMessage().toLowerCase().contains("unique"));
    }

    private void addColumnIfMissing(String table, String column, String definition) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
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
            plugin.getLogger().warning("解析地标图标数据失败: " + e.getMessage());
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
