package io.github.mcclauneck.mceconomy.common.database.sqlite;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.mcclauneck.mceconomy.api.database.IMCEconomyDB;
import io.github.mcclauneck.mceconomy.api.enums.CurrencyType;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * SQLite implementation for MCEconomy using an explicit database file path.
 */
public class MCEconomySQLite implements IMCEconomyDB {

    /**
     * Hikari connection pool for SQLite.
     */
    private final HikariDataSource dataSource;

    /**
     * Lock object for thread synchronization (SQLite allows only one writer).
     */
    private final Object lock = new Object();

    /**
     * Constructs a new SQLite database handler.
     * Creates parent directories and the database file if they do not exist.
     *
     * @param dbPath path to the SQLite database file (absolute or relative)
     */
    public MCEconomySQLite(String dbPath) {
        File target = resolvePath(dbPath);
        File parentDir = target.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + target.getAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(30000);
        config.setPoolName("MCEconomy-SQLite-Pool");

        this.dataSource = new HikariDataSource(config);

        try {
            createTable();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Resolve a potentially relative path to a file.
     */
    private File resolvePath(String path) {
        File candidate = new File(path);
        if (candidate.isAbsolute()) {
            return candidate;
        }
        return candidate.getAbsoluteFile();
    }

    /**
     * Creates the economy_accounts table if it does not already exist in the SQLite file.
     */
    private void createTable() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS economy_accounts (" +
                     "account_uuid TEXT NOT NULL, " +
                     "account_type TEXT NOT NULL, " +
                     "coin INTEGER NOT NULL DEFAULT 0, " +
                     "copper INTEGER NOT NULL DEFAULT 0, " +
                     "silver INTEGER NOT NULL DEFAULT 0, " +
                     "gold INTEGER NOT NULL DEFAULT 0, " +
                     "PRIMARY KEY (account_uuid, account_type))";
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    @Override
    public boolean ensureAccountExist(String accountUuid, String accountType) {
        String sql = "INSERT OR IGNORE INTO economy_accounts (account_uuid, account_type) VALUES (?, ?)";
        synchronized (lock) {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, accountUuid);
                pstmt.setString(2, accountType);
                pstmt.executeUpdate();
                return true;
            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
        }
    }

    @Override
    public int getCoin(String accountUuid, String accountType, CurrencyType coinType) {
        synchronized (lock) {
            String col = columnName(coinType);
            ensureAccountExist(accountUuid, accountType);
            String sql = "SELECT " + col + " FROM economy_accounts WHERE account_uuid = ? AND account_type = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, accountUuid);
                pstmt.setString(2, accountType);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) return rs.getInt(1);
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return 0;
    }

    @Override
    public boolean setCoin(String accountUuid, String accountType, CurrencyType coinType, int amount) {
        synchronized (lock) {
            if (amount < 0) return false;
            String col = columnName(coinType);
            ensureAccountExist(accountUuid, accountType);
            String sql = "UPDATE economy_accounts SET " + col + " = ? WHERE account_uuid = ? AND account_type = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, amount);
                pstmt.setString(2, accountUuid);
                pstmt.setString(3, accountType);
                pstmt.executeUpdate();
                return true;
            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
        }
    }

    @Override
    public boolean addCoin(String accountUuid, String accountType, CurrencyType coinType, int amount) {
        synchronized (lock) {
            if (amount <= 0) return false;
            int newAmount = getCoin(accountUuid, accountType, coinType) + amount;
            return setCoin(accountUuid, accountType, coinType, newAmount);
        }
    }

    @Override
    public boolean minusCoin(String accountUuid, String accountType, CurrencyType coinType, int amount) {
        synchronized (lock) {
            if (amount <= 0) return false;
            String col = columnName(coinType);
            String sql = "UPDATE economy_accounts SET " + col + " = " + col + " - ? " +
                         "WHERE account_uuid = ? AND account_type = ? AND " + col + " >= ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, amount);
                pstmt.setString(2, accountUuid);
                pstmt.setString(3, accountType);
                pstmt.setInt(4, amount);
                return pstmt.executeUpdate() > 0;
            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
        }
    }

    @Override
    public boolean sendCoin(String senderUuid, String senderType, String receiverUuid, String receiverType, CurrencyType coinType, int amount) {
        synchronized (lock) {
            if (amount <= 0) return false;
            String col = columnName(coinType);
            try (Connection conn = dataSource.getConnection()) {
                boolean prevAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    ensureAccountExist(senderUuid, senderType);
                    ensureAccountExist(receiverUuid, receiverType);

                    String withdrawSql = "UPDATE economy_accounts SET " + col + " = " + col + " - ? " +
                                         "WHERE account_uuid = ? AND account_type = ? AND " + col + " >= ?";
                    try (PreparedStatement withdraw = conn.prepareStatement(withdrawSql)) {
                        withdraw.setInt(1, amount);
                        withdraw.setString(2, senderUuid);
                        withdraw.setString(3, senderType);
                        withdraw.setInt(4, amount);
                        if (withdraw.executeUpdate() == 0) {
                            conn.rollback();
                            conn.setAutoCommit(prevAutoCommit);
                            return false;
                        }
                    }

                    String depositSql = "UPDATE economy_accounts SET " + col + " = " + col + " + ? WHERE account_uuid = ? AND account_type = ?";
                    try (PreparedStatement deposit = conn.prepareStatement(depositSql)) {
                        deposit.setInt(1, amount);
                        deposit.setString(2, receiverUuid);
                        deposit.setString(3, receiverType);
                        deposit.executeUpdate();
                    }

                    conn.commit();
                    conn.setAutoCommit(prevAutoCommit);
                    return true;
                } catch (SQLException e) {
                    try { conn.rollback(); } catch (SQLException ignored) {}
                    try { conn.setAutoCommit(prevAutoCommit); } catch (SQLException ignored) {}
                    throw e;
                }
            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
            }
        }
    }

    /**
     * Resolve the trusted column name for a currency type.
     */
    private String columnName(CurrencyType type) {
        Objects.requireNonNull(type, "currency type");
        return switch (type) {
            case COIN -> "coin";
            case COPPER -> "copper";
            case SILVER -> "silver";
            case GOLD -> "gold";
        };
    }
}
