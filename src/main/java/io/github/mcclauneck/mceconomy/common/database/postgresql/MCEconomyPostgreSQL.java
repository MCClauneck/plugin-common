package io.github.mcclauneck.mceconomy.common.database.postgresql;

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
 * PostgreSQL implementation for MCEconomy.
 */
public class MCEconomyPostgreSQL implements IMCEconomyDB {

    /**
     * The PostgreSQL connection pool data source.
     */
    private final HikariDataSource dataSource;

    /**
     * Constructs a new PostgreSQL database handler.
     *
     * @param dbUser database username
     * @param dbPass database password
     * @param dbHost database host
     * @param dbName database/schema name
     * @param dbPort database port (defaults to 5432 when blank)
     * @param dbSsl  whether SSL should be enabled
     */
    public MCEconomyPostgreSQL(String dbUser, String dbPass, String dbHost, String dbName, String dbPort, boolean dbSsl) {
        String port = (dbPort == null || dbPort.isBlank()) ? "5432" : dbPort.trim();

        HikariConfig config = new HikariConfig();
        // PostgreSQL JDBC URL format
        String sslParam = dbSsl ? "?sslmode=verify-full" : "?sslmode=disable";
        config.setJdbcUrl("jdbc:postgresql://" + dbHost + ":" + port + "/" + dbName + sslParam);
        config.setUsername(dbUser);
        config.setPassword(dbPass);

        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setLeakDetectionThreshold(10000);

        this.dataSource = new HikariDataSource(config);

        try {
            createTable();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates the economy_accounts table if it does not already exist.
     *
     * @throws SQLException if table creation fails
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

    /**
     * Ensures an account row exists.
     *
     * @param accountUuid unique account identifier
     * @param accountType logical account type
     * @return true when present/created; false on error
     */
    @Override
    public boolean ensureAccountExist(String accountUuid, String accountType) {
        try (Connection conn = dataSource.getConnection()) {
            return ensureAccountExist(conn, accountUuid, accountType);
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Ensures an account row exists using an existing SQL connection.
     *
     * @param conn        active SQL connection
     * @param accountUuid unique account identifier
     * @param accountType logical account type
     * @return true when present/created
     * @throws SQLException if insert operation fails
     */
    private boolean ensureAccountExist(Connection conn, String accountUuid, String accountType) throws SQLException {
        // PostgreSQL uses ON CONFLICT instead of INSERT IGNORE
        String sql = "INSERT INTO economy_accounts (account_uuid, account_type) VALUES (?, ?) " +
                     "ON CONFLICT (account_uuid, account_type) DO NOTHING";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, accountUuid);
            pstmt.setString(2, accountType);
            pstmt.executeUpdate();
            return true;
        }
    }

    /**
     * Reads a coin balance.
     *
     * @param accountUuid unique account identifier
     * @param accountType logical account type
     * @param coinType    currency to fetch
     * @return non-negative balance
     */
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

    /**
     * Sets a coin balance to an absolute amount.
     *
     * @param accountUuid unique account identifier
     * @param accountType logical account type
     * @param coinType    currency to set
     * @param amount      new amount (must be >= 0)
     * @return true if updated
     */
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

    /**
     * Adds to a coin balance.
     *
     * @param accountUuid unique account identifier
     * @param accountType logical account type
     * @param coinType    currency to add
     * @param amount      delta to add (must be > 0)
     * @return true if updated
     */
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

    /**
     * Subtracts from a coin balance with non-negative guard.
     *
     * @param accountUuid unique account identifier
     * @param accountType logical account type
     * @param coinType    currency to subtract
     * @param amount      delta to subtract (must be > 0)
     * @return true if funds were sufficient and updated
     */
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

    /**
     * Transfers funds between two accounts transactionally.
     *
     * @param senderUuid   sender account id
     * @param senderType   sender account type
     * @param receiverUuid receiver account id
     * @param receiverType receiver account type
     * @param coinType     currency to transfer
     * @param amount       amount to transfer (>0)
     * @return true if transfer committed
     */
    @Override
    public boolean sendCoin(String senderUuid, String senderType, String receiverUuid, String receiverType, CurrencyType coinType, long amount) {
        if (amount <= 0) return false;
        String col = columnName(coinType);
        try (Connection conn = dataSource.getConnection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                if (!ensureAccountExist(conn, senderUuid, senderType) || !ensureAccountExist(conn, receiverUuid, receiverType)) {
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
     * Resolves the trusted SQL column name for a currency type.
     *
     * @param type currency enum value
     * @return trusted SQL column name
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
     * Closes the PostgreSQL connection pool and releases resources.
     */
    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
