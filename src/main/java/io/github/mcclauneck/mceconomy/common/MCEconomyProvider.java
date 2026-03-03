package io.github.mcclauneck.mceconomy.common;

import io.github.mcclauneck.mceconomy.api.database.IMCEconomyDB;
import io.github.mcclauneck.mceconomy.api.enums.CurrencyType;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * High-level provider that wraps the underlying database implementation.
 * <p>
 * Platform-agnostic and ready for third-party use; relies on an injected {@link Executor}
 * for async work and an {@link IMCEconomyDB} implementation for persistence.
 * </p>
 */
public class MCEconomyProvider {

    /**
     * The singleton instance of the provider.
     */
    private static MCEconomyProvider instance;

    /**
     * The underlying database implementation (e.g., MySQL or SQLite).
     */
    private final IMCEconomyDB db;

    /**
     * The platform-specific executor used to run database tasks off the main thread.
     */
    private final Executor asyncExecutor;

    /**
     * The default currency identifier used when no specific coin type is provided.
     */
    private static final CurrencyType DEFAULT_COIN = CurrencyType.COIN;

    /**
     * Initializes the provider with a database implementation and an async executor.
     * Sets the static singleton instance upon creation.
     *
     * @param db            The database logic implementation.
     * @param asyncExecutor The executor used for asynchronous operations.
     */
    public MCEconomyProvider(IMCEconomyDB db, Executor asyncExecutor) {
        this.db = db;
        this.asyncExecutor = asyncExecutor;
        instance = this; // Set the singleton instance
    }

    /**
     * Gets the active singleton instance of the MCEconomyProvider.
     *
     * @return The active MCEconomyProvider instance, or null if not initialized.
     */
    public static MCEconomyProvider getInstance() {
        return instance;
    }

    /**
     * Internal helper to wrap blocking database calls into a CompletableFuture.
     */
    private <T> CompletableFuture<T> runAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, asyncExecutor);
    }

    // --- GETTERS ---

    public CompletableFuture<Integer> getCoin(String accountUuid, String accountType) {
        return getCoin(accountUuid, accountType, DEFAULT_COIN);
    }

    public CompletableFuture<Integer> getCoin(String accountUuid, String accountType, CurrencyType coinType) {
        return runAsync(() -> db.getCoin(accountUuid, accountType, coinType));
    }

    // --- SETTERS ---

    public CompletableFuture<Boolean> setCoin(String accountUuid, String accountType, int amount) {
        return setCoin(accountUuid, accountType, DEFAULT_COIN, amount);
    }

    public CompletableFuture<Boolean> setCoin(String accountUuid, String accountType, CurrencyType coinType, int amount) {
        return runAsync(() -> db.setCoin(accountUuid, accountType, coinType, amount));
    }

    // --- ADD ---

    public CompletableFuture<Boolean> addCoin(String accountUuid, String accountType, int amount) {
        return addCoin(accountUuid, accountType, DEFAULT_COIN, amount);
    }

    public CompletableFuture<Boolean> addCoin(String accountUuid, String accountType, CurrencyType coinType, int amount) {
        return runAsync(() -> db.addCoin(accountUuid, accountType, coinType, amount));
    }

    // --- MINUS ---

    public CompletableFuture<Boolean> minusCoin(String accountUuid, String accountType, int amount) {
        return minusCoin(accountUuid, accountType, DEFAULT_COIN, amount);
    }

    public CompletableFuture<Boolean> minusCoin(String accountUuid, String accountType, CurrencyType coinType, int amount) {
        return runAsync(() -> db.minusCoin(accountUuid, accountType, coinType, amount));
    }

    // --- SEND ---

    public CompletableFuture<Boolean> sendCoin(String senderUuid, String senderType, String receiverUuid, String receiverType, int amount) {
        return sendCoin(senderUuid, senderType, receiverUuid, receiverType, DEFAULT_COIN, amount);
    }

    public CompletableFuture<Boolean> sendCoin(String senderUuid, String senderType, String receiverUuid, String receiverType, CurrencyType coinType, int amount) {
        return runAsync(() -> db.sendCoin(senderUuid, senderType, receiverUuid, receiverType, coinType, amount));
    }

    // --- UTILITY ---

    public CompletableFuture<Boolean> ensureAccountExist(String accountUuid, String accountType) {
        return runAsync(() -> db.ensureAccountExist(accountUuid, accountType));
    }

    /**
     * Properly shuts down the database connection and releases resources.
     */
    public void shutdown() {
        if (db != null) {
            db.close();
        }
        instance = null; // Clear singleton
    }
}
