package com.italixcs.advancedreports;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ReportRepository implements AutoCloseable {
    enum Sort { MOST, LEAST, RECENT, OLDEST, NEWEST, PLAYER_OLDEST }
    enum SubmitStatus { CREATED, DUPLICATE, COOLDOWN }
    record Target(UUID id, String name, long count) {}
    record PlayerIdentity(UUID id, String name) {}
    record Report(long id, UUID targetId, String targetName, long number, UUID reporterId,
                  String reporterName, String reason, long createdAt) {}
    record Page<T>(List<T> items, long total) {}
    record Submission(SubmitStatus status, long total, long waitSeconds, long number) {}
    record DeleteResult(boolean deleted, long remaining) {}

    private final AdvancedReportsPlugin plugin;
    // ponytail: one database worker serializes report writes for a single server; split by player if volume grows.
    private final ExecutorService databaseThread = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "AdvancedReports-Database");
        thread.setDaemon(true);
        return thread;
    });
    private volatile HikariDataSource dataSource;

    ReportRepository(AdvancedReportsPlugin plugin) {
        this.plugin = plugin;
    }

    CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            FileConfiguration config = plugin.getConfig();
            String host = config.getString("mysql.host", "localhost");
            int port = config.getInt("mysql.port", 3306);
            String database = config.getString("mysql.database", "minecraft");
            boolean ssl = config.getBoolean("mysql.ssl", false);
            HikariConfig pool = new HikariConfig();
            pool.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=" + ssl + "&serverTimezone=UTC");
            pool.setUsername(config.getString("mysql.username", "root"));
            pool.setPassword(config.getString("mysql.password", ""));
            pool.setPoolName("AdvancedReports");
            pool.setMaximumPoolSize(1);
            pool.setMinimumIdle(1);
            pool.setConnectionTimeout(10_000);
            pool.setInitializationFailTimeout(10_000);
            dataSource = new HikariDataSource(pool);
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS advancedreports_known_players ("
                        + "uuid CHAR(36) PRIMARY KEY, name VARCHAR(16) NOT NULL, KEY name_idx(name)) "
                        + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS advancedreports_sequences ("
                        + "target_uuid CHAR(36) PRIMARY KEY, last_number BIGINT NOT NULL DEFAULT 0) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS advancedreports_cooldowns ("
                        + "reporter_uuid CHAR(36) PRIMARY KEY, submitted_at BIGINT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS advancedreports_reports ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, target_uuid CHAR(36) NOT NULL, "
                        + "target_name VARCHAR(16) NOT NULL, report_number BIGINT NOT NULL, "
                        + "reporter_uuid CHAR(36) NOT NULL, reporter_name VARCHAR(16) NOT NULL, "
                        + "reason VARCHAR(255) NOT NULL DEFAULT '', created_at BIGINT NOT NULL, "
                        + "UNIQUE KEY target_report_number (target_uuid, report_number), "
                        + "KEY target_created (target_uuid, created_at), KEY reporter_target (reporter_uuid, target_uuid)) "
                        + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, databaseThread);
    }

    CompletableFuture<Void> rememberPlayer(UUID id, String name) {
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO advancedreports_known_players(uuid,name) VALUES(?,?) "
                            + "ON DUPLICATE KEY UPDATE name=VALUES(name)")) {
                statement.setString(1, id.toString());
                statement.setString(2, name);
                statement.executeUpdate();
            }
            return null;
        });
    }

    CompletableFuture<PlayerIdentity> knownPlayer(String name) {
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT uuid,name FROM advancedreports_known_players WHERE name=? LIMIT 1")) {
                statement.setString(1, name);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? new PlayerIdentity(UUID.fromString(result.getString("uuid")), result.getString("name")) : null;
                }
            }
        });
    }

    CompletableFuture<Submission> submit(UUID targetId, String targetName, UUID reporterId,
                                         String reporterName, String reason, long cooldownMillis) {
        return query(connection -> {
            connection.setAutoCommit(false);
            long now = System.currentTimeMillis();
            String storedReason = reason == null ? "" : reason;
            try {
                try (PreparedStatement duplicate = connection.prepareStatement(
                        "SELECT 1 FROM advancedreports_reports WHERE target_uuid=? AND reporter_uuid=? AND reason=? LIMIT 1")) {
                    duplicate.setString(1, targetId.toString());
                    duplicate.setString(2, reporterId.toString());
                    duplicate.setString(3, storedReason);
                    try (ResultSet result = duplicate.executeQuery()) {
                        if (result.next()) {
                            connection.rollback();
                            return new Submission(SubmitStatus.DUPLICATE, 0, 0, 0);
                        }
                    }
                }

                long lastSubmitted = 0;
                try (PreparedStatement cooldown = connection.prepareStatement(
                        "SELECT submitted_at FROM advancedreports_cooldowns WHERE reporter_uuid=? FOR UPDATE")) {
                    cooldown.setString(1, reporterId.toString());
                    try (ResultSet result = cooldown.executeQuery()) {
                        if (result.next()) lastSubmitted = result.getLong(1);
                    }
                }
                long remaining = cooldownMillis - (now - lastSubmitted);
                if (lastSubmitted > 0 && remaining > 0) {
                    connection.rollback();
                    return new Submission(SubmitStatus.COOLDOWN, 0, (remaining + 999) / 1000, 0);
                }

                try (PreparedStatement create = connection.prepareStatement(
                        "INSERT INTO advancedreports_sequences(target_uuid,last_number) VALUES(?,0) "
                                + "ON DUPLICATE KEY UPDATE target_uuid=VALUES(target_uuid)")) {
                    create.setString(1, targetId.toString());
                    create.executeUpdate();
                }
                long nextNumber;
                try (PreparedStatement sequence = connection.prepareStatement(
                        "SELECT last_number FROM advancedreports_sequences WHERE target_uuid=? FOR UPDATE")) {
                    sequence.setString(1, targetId.toString());
                    try (ResultSet result = sequence.executeQuery()) {
                        if (!result.next()) throw new SQLException("Could not read report number sequence");
                        nextNumber = result.getLong(1) + 1;
                    }
                }
                try (PreparedStatement sequence = connection.prepareStatement(
                        "UPDATE advancedreports_sequences SET last_number=? WHERE target_uuid=?")) {
                    sequence.setLong(1, nextNumber);
                    sequence.setString(2, targetId.toString());
                    sequence.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO advancedreports_reports(target_uuid,target_name,report_number,reporter_uuid,reporter_name,reason,created_at) "
                                + "VALUES(?,?,?,?,?,?,?)")) {
                    insert.setString(1, targetId.toString());
                    insert.setString(2, targetName);
                    insert.setLong(3, nextNumber);
                    insert.setString(4, reporterId.toString());
                    insert.setString(5, reporterName);
                    insert.setString(6, storedReason);
                    insert.setLong(7, now);
                    insert.executeUpdate();
                }
                try (PreparedStatement cooldown = connection.prepareStatement(
                        "INSERT INTO advancedreports_cooldowns(reporter_uuid,submitted_at) VALUES(?,?) "
                                + "ON DUPLICATE KEY UPDATE submitted_at=VALUES(submitted_at)")) {
                    cooldown.setString(1, reporterId.toString());
                    cooldown.setLong(2, now);
                    cooldown.executeUpdate();
                }
                long total = countTarget(connection, targetId);
                connection.commit();
                return new Submission(SubmitStatus.CREATED, total, 0, nextNumber);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        });
    }

    CompletableFuture<Page<Target>> targets(Sort sort, int page, int pageSize) {
        return query(connection -> {
            String order = switch (sort) {
                case MOST -> "report_count DESC, player_name ASC";
                case LEAST -> "report_count ASC, player_name ASC";
                case RECENT -> "latest_report DESC, player_name ASC";
                case OLDEST -> "first_report ASC, player_name ASC";
                default -> "report_count DESC, player_name ASC";
            };
            String sql = "SELECT r.target_uuid, COALESCE(MAX(k.name), MAX(r.target_name)) AS player_name, "
                    + "COUNT(*) AS report_count, MAX(r.created_at) AS latest_report, MIN(r.created_at) AS first_report "
                    + "FROM advancedreports_reports r LEFT JOIN advancedreports_known_players k ON k.uuid=r.target_uuid "
                    + "GROUP BY r.target_uuid ORDER BY " + order + ", r.target_uuid LIMIT ? OFFSET ?";
            List<Target> items = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, pageSize);
                statement.setInt(2, Math.max(0, page) * pageSize);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) items.add(new Target(UUID.fromString(result.getString("target_uuid")),
                            result.getString("player_name"), result.getLong("report_count")));
                }
            }
            long total;
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(DISTINCT target_uuid) FROM advancedreports_reports")) {
                result.next();
                total = result.getLong(1);
            }
            return new Page<>(items, total);
        });
    }

    CompletableFuture<Page<Report>> reports(UUID targetId, Sort sort, int page, int pageSize) {
        return query(connection -> {
            String order = sort == Sort.PLAYER_OLDEST ? "created_at ASC, report_number ASC" : "created_at DESC, report_number DESC";
            List<Report> items = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id,target_uuid,target_name,report_number,reporter_uuid,reporter_name,reason,created_at "
                            + "FROM advancedreports_reports WHERE target_uuid=? ORDER BY " + order + " LIMIT ? OFFSET ?")) {
                statement.setString(1, targetId.toString());
                statement.setInt(2, pageSize);
                statement.setInt(3, Math.max(0, page) * pageSize);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) items.add(readReport(result));
                }
            }
            return new Page<>(items, countTarget(connection, targetId));
        });
    }

    CompletableFuture<Report> report(long id) {
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id,target_uuid,target_name,report_number,reporter_uuid,reporter_name,reason,created_at "
                            + "FROM advancedreports_reports WHERE id=?")) {
                statement.setLong(1, id);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? readReport(result) : null;
                }
            }
        });
    }

    CompletableFuture<DeleteResult> delete(long id, UUID targetId) {
        return query(connection -> {
            int removed;
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM advancedreports_reports WHERE id=? AND target_uuid=?")) {
                statement.setLong(1, id);
                statement.setString(2, targetId.toString());
                removed = statement.executeUpdate();
            }
            return new DeleteResult(removed > 0, countTarget(connection, targetId));
        });
    }

    private static Report readReport(ResultSet result) throws SQLException {
        return new Report(result.getLong("id"), UUID.fromString(result.getString("target_uuid")),
                result.getString("target_name"), result.getLong("report_number"),
                UUID.fromString(result.getString("reporter_uuid")), result.getString("reporter_name"),
                result.getString("reason"), result.getLong("created_at"));
    }

    private static long countTarget(Connection connection, UUID targetId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM advancedreports_reports WHERE target_uuid=?")) {
            statement.setString(1, targetId.toString());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private <T> CompletableFuture<T> query(SqlWork<T> action) {
        return CompletableFuture.supplyAsync(() -> {
            HikariDataSource source = dataSource;
            if (source == null || source.isClosed()) throw new CompletionException(new SQLException("Database is not ready"));
            try (Connection connection = source.getConnection()) {
                return action.run(connection);
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, databaseThread);
    }

    @Override
    public void close() {
        databaseThread.shutdownNow();
        HikariDataSource source = dataSource;
        if (source != null) source.close();
    }

    @FunctionalInterface
    private interface SqlWork<T> { T run(Connection connection) throws SQLException; }
}
