package io.github.mcclauneck.mceconomy.common.database.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.mcclauneck.mceconomy.api.database.IMCEconomyDB;
import io.github.mcclauneck.mceconomy.api.enums.CurrencyType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * MySQL implementation for MCEconomy using explicit connection parameters.
 */
public class MCEconomyMySQL implements IMCEconomyDB {

    /**
     * The connection pool data source.
     */
    private final HikariDataSource dataSource;

    /**
     * Constructs a new MySQL database handler using provided credentials.
     *
     * @param dbUser username for the database
     * @param dbPass password for the database
     * @param dbHost host of the database
     * @param dbName schema/database name
     * @param dbPort port number as string (defaults to 3306 if null/blank)
     * @param dbSsl  whether to use SSL
     */
    public MCEconomyMySQL(String dbUser, String dbPass, String dbHost, String dbName, String dbPort, boolean dbSsl) {
        String port = (dbPort == null || dbPort.isBlank()) ? "3306" : dbPort.trim();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + dbHost + ":" + port + "/" + dbName + "?useSSL=" + dbSsl);
        config.setUsername(dbUser);
        config.setPassword(dbPass);

        // Pool settings tuned for lightweight async usage
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setLeakDetectionThreshold(10000);

        // Performance properties
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        this.dataSource = new HikariDataSource(config);

        try {
            createTable();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates the economy_accounts table if it does not already exist.
     */
    private void createTable() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS economy_accounts (" +
                     "account_uuid VARCHAR(36) NOT NULL, " +
                     "account_type VARCHAR(32) NOT NULL, " +
                     "coin BIGINT NOT NULL DEFAULT 0, " +
                     "copper BIGINT NOT NULL DEFAULT 0, " +
                     "silver BIGINT NOT NULL DEFAULT 0, " +
                     "gold BIGINT NOT NULL DEFAULT 0, " +
                     "PRIMARY KEY (account_uuid, account_type))";

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    @Override
    public boolean ensureAccountExist(String accountUuid, String accountType) {
        try (Connection conn = dataSource.getConnection()) {
            return ensureAccountExist(conn, accountUuid, accountType);
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public long getCoin(String accountUuid, String accountType, CurrencyType coinType) {
        String col = columnName(coinType);
        ensureAccountExist(accountUuid, accountType);
        String sql = "SELECT " + col + " FROM economy_accounts WHERE account_uuid = ? AND account_type = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, accountUuid);
            pstmt.setString(2, accountType);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return Math.max(0L, rs.getLong(1));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    @Override
    public boolean setCoin(String accountUuid, String accountType, CurrencyType coinType, long amount) {
        if (amount < 0) return false;
        String col = columnName(coinType);
        ensureAccountExist(accountUuid, accountType);
        String sql = "UPDATE economy_accounts SET " + col + " = ? WHERE account_uuid = ? AND account_type = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, amount);
            pstmt.setString(2, accountUuid);
            pstmt.setString(3, accountType);
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean addCoin(String accountUuid, String accountType, CurrencyType coinType, long amount) {
        if (amount <= 0) return false;
        ensureAccountExist(accountUuid, accountType);
        String col = columnName(coinType);
        String sql = "UPDATE economy_accounts SET " + col + " = " + col + " + ? WHERE account_uuid = ? AND account_type = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, amount);
            pstmt.setString(2, accountUuid);
            pstmt.setString(3, accountType);
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean minusCoin(String accountUuid, String accountType, CurrencyType coinType, long amount) {
        if (amount <= 0) return false;
        String col = columnName(coinType);
        String sql = "UPDATE economy_accounts SET " + col + " = " + col + " - ? " +
                     "WHERE account_uuid = ? AND account_type = ? AND " + col + " >= ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, amount);
            pstmt.setString(2, accountUuid);
            pstmt.setString(3, accountType);
            pstmt.setLong(4, amount);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean sendCoin(String senderUuid, String senderType, String receiverUuid, String receiverType, CurrencyType coinType, long amount) {
        if (amount <= 0) return false;
        String col = columnName(coinType);
        try (Connection conn = dataSource.getConnection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                if (!ensureAccountExist(conn, senderUuid, senderType)) {
                    conn.rollback();
                    conn.setAutoCommit(prevAutoCommit);
                    return false;
                }
                if (!ensureAccountExist(conn, receiverUuid, receiverType)) {
                    conn.rollback();
                    conn.setAutoCommit(prevAutoCommit);
                    return false;
                }

                String withdrawSql = "UPDATE economy_accounts SET " + col + " = " + col + " - ? " +
                                     "WHERE account_uuid = ? AND account_type = ? AND " + col + " >= ?";
                try (PreparedStatement withdraw = conn.prepareStatement(withdrawSql)) {
                    withdraw.setLong(1, amount);
                    withdraw.setString(2, senderUuid);
                    withdraw.setString(3, senderType);
                    withdraw.setLong(4, amount);
                    if (withdraw.executeUpdate() == 0) {
                        conn.rollback();
                        conn.setAutoCommit(prevAutoCommit);
                        return false;
                    }
                }

                String depositSql = "UPDATE economy_accounts SET " + col + " = " + col + " + ? WHERE account_uuid = ? AND account_type = ?";
                try (PreparedStatement deposit = conn.prepareStatement(depositSql)) {
                    deposit.setLong(1, amount);
                    deposit.setString(2, receiverUuid);
                    deposit.setString(3, receiverType);
                    deposit.executeUpdate();
                }

                conn.commit();
                conn.setAutoCommit(prevAutoCommit);
                return true;
            } catch (SQLException e) {
                conn.rollback();
                conn.setAutoCommit(prevAutoCommit);
                throw e;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
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

    /**
     * Insert-or-ignore using an existing connection to participate in transactions.
     */
    private boolean ensureAccountExist(Connection conn, String accountUuid, String accountType) throws SQLException {
        String sql = "INSERT IGNORE INTO economy_accounts (account_uuid, account_type) VALUES (?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, accountUuid);
            pstmt.setString(2, accountType);
            pstmt.executeUpdate();
            return true;
        }
    }

    /**
     * Closes the MySQL connection pool and releases resources.
     */
    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
