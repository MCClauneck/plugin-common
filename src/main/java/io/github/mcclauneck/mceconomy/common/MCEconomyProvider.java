package io.github.mcclauneck.mceconomy.common;

import io.github.mcclauneck.mceconomy.api.database.IMCEconomyDB;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * High-level provider that exposes the active asynchronous economy database implementation.
 */
public class MCEconomyProvider {

    /**
     * The default currency identifier used by convenience overloads.
     */
    public static final int DEFAULT_CURRENCY_ID = 1;

    /**
     * The active singleton provider instance.
     */
    private static MCEconomyProvider instance;

    /**
     * The underlying asynchronous database implementation.
     */
    private final IMCEconomyDB db;

    /**
     * Creates a new provider with the supplied asynchronous database implementation.
     *
     * @param db the database implementation used for all economy operations
     */
    public MCEconomyProvider(IMCEconomyDB db) {
        this.db = Objects.requireNonNull(db, "db");
        instance = this;
    }

    /**
     * Creates a new provider while preserving the previous constructor shape.
     * <p>
     * The executor is ignored because the new database contract is already asynchronous.
     * </p>
     *
     * @param db the database implementation used for all economy operations
     * @param asyncExecutor the legacy executor argument retained for compatibility
     */
    public MCEconomyProvider(IMCEconomyDB db, Executor asyncExecutor) {
        this(db);
    }

    /**
     * Returns the currently registered provider instance.
     *
     * @return the active provider, or {@code null} when one has not been created yet
     */
    public static MCEconomyProvider getInstance() {
        return instance;
    }

    /**
     * Retrieves the default currency balance for the requested account.
     *
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @return a future containing the balance for the default currency
     */
    public CompletableFuture<Long> getBalance(String accountType, String accountId) {
        return getBalance(accountType, accountId, DEFAULT_CURRENCY_ID);
    }

    /**
     * Retrieves the balance for the requested account and currency identifier.
     *
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @param currencyId the currency identifier to read
     * @return a future containing the current balance
     */
    public CompletableFuture<Long> getBalance(String accountType, String accountId, int currencyId) {
        return db.getBalance(accountType, accountId, currencyId);
    }

    /**
     * Sets the default currency balance for the requested account.
     *
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @param amount the balance value to store
     * @return a future that resolves to {@code true} when the update succeeds
     */
    public CompletableFuture<Boolean> setBalance(String accountType, String accountId, long amount) {
        return setBalance(accountType, accountId, DEFAULT_CURRENCY_ID, amount);
    }

    /**
     * Sets the balance for the requested account and currency identifier.
     *
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @param currencyId the currency identifier to update
     * @param amount the balance value to store
     * @return a future that resolves to {@code true} when the update succeeds
     */
    public CompletableFuture<Boolean> setBalance(String accountType, String accountId, int currencyId, long amount) {
        return db.setBalance(accountType, accountId, currencyId, amount);
    }

    /**
     * Adds to the default currency balance for the requested account.
     *
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @param amount the amount to add
     * @return a future that resolves to {@code true} when the update succeeds
     */
    public CompletableFuture<Boolean> addBalance(String accountType, String accountId, long amount) {
        return addBalance(accountType, accountId, DEFAULT_CURRENCY_ID, amount);
    }

    /**
     * Adds to the balance for the requested account and currency identifier.
     *
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @param currencyId the currency identifier to update
     * @param amount the amount to add
     * @return a future that resolves to {@code true} when the update succeeds
     */
    public CompletableFuture<Boolean> addBalance(String accountType, String accountId, int currencyId, long amount) {
        return db.addBalance(accountType, accountId, currencyId, amount);
    }

    /**
     * Subtracts from the default currency balance for the requested account.
     *
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @param amount the amount to subtract
     * @return a future that resolves to {@code true} when the subtraction succeeds
     */
    public CompletableFuture<Boolean> subtractBalance(String accountType, String accountId, long amount) {
        return subtractBalance(accountType, accountId, DEFAULT_CURRENCY_ID, amount);
    }

    /**
     * Subtracts from the balance for the requested account and currency identifier.
     *
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @param currencyId the currency identifier to update
     * @param amount the amount to subtract
     * @return a future that resolves to {@code true} when the subtraction succeeds
     */
    public CompletableFuture<Boolean> subtractBalance(String accountType, String accountId, int currencyId, long amount) {
        return db.subtractBalance(accountType, accountId, currencyId, amount);
    }

    /**
     * Transfers the default currency between two accounts.
     *
     * @param senderType the sender account type
     * @param senderId the sender account identifier
     * @param receiverType the receiver account type
     * @param receiverId the receiver account identifier
     * @param amount the amount to transfer
     * @return a future that resolves to {@code true} when the transfer succeeds
     */
    public CompletableFuture<Boolean> transferBalance(
            String senderType,
            String senderId,
            String receiverType,
            String receiverId,
            long amount
    ) {
        return transferBalance(senderType, senderId, receiverType, receiverId, DEFAULT_CURRENCY_ID, amount);
    }

    /**
     * Transfers a specific currency between two accounts.
     *
     * @param senderType the sender account type
     * @param senderId the sender account identifier
     * @param receiverType the receiver account type
     * @param receiverId the receiver account identifier
     * @param currencyId the currency identifier to transfer
     * @param amount the amount to transfer
     * @return a future that resolves to {@code true} when the transfer succeeds
     */
    public CompletableFuture<Boolean> transferBalance(
            String senderType,
            String senderId,
            String receiverType,
            String receiverId,
            int currencyId,
            long amount
    ) {
        return db.transferBalance(senderId, senderType, receiverId, receiverType, currencyId, amount);
    }

    /**
     * Ensures the default currency record exists for the requested account.
     *
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @return a future that resolves to {@code true} when the account row exists
     */
    public CompletableFuture<Boolean> ensureAccountExists(String accountType, String accountId) {
        return ensureAccountExists(accountType, accountId, DEFAULT_CURRENCY_ID);
    }

    /**
     * Ensures the requested account and currency record exists.
     *
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @param currencyId the currency identifier to ensure
     * @return a future that resolves to {@code true} when the account row exists
     */
    public CompletableFuture<Boolean> ensureAccountExists(String accountType, String accountId, int currencyId) {
        return db.ensureAccountExists(accountType, accountId, currencyId);
    }

    /**
     * Closes the active database implementation and clears the provider singleton.
     */
    public void shutdown() {
        db.close();
        instance = null;
    }
}
