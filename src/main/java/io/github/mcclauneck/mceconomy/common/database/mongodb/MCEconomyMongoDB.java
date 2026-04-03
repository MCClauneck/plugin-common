package io.github.mcclauneck.mceconomy.common.database.mongodb;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.github.mcclauneck.mceconomy.api.database.IMCEconomyDB;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.Date;
import java.util.concurrent.CompletableFuture;

/**
 * MongoDB-backed implementation of the asynchronous MCEconomy database contract.
 */
public class MCEconomyMongoDB implements IMCEconomyDB {

    /**
     * The underlying MongoDB client.
     */
    private final MongoClient mongoClient;

    /**
     * The selected MongoDB database instance.
     */
    private final MongoDatabase database;

    /**
     * The account collection storing one document per account and currency identifier.
     */
    private final MongoCollection<Document> accountCollection;

    /**
     * The currency collection storing generated metadata for currency identifiers.
     */
    private final MongoCollection<Document> currencyCollection;

    /**
     * Creates a new MongoDB economy database adapter.
     *
     * @param connectionString the MongoDB connection string
     * @param databaseName the database name
     */
    public MCEconomyMongoDB(String connectionString, String databaseName) {
        String safeConnectionString = enforceTls(connectionString);
        this.mongoClient = MongoClients.create(safeConnectionString);
        this.database = this.mongoClient.getDatabase(databaseName);
        this.accountCollection = this.database.getCollection("mceconomy_account");
        this.currencyCollection = this.database.getCollection("mceconomy_currencies");

        this.accountCollection.createIndex(
                Indexes.ascending("account_type", "account_id", "currency_id"),
                new IndexOptions().unique(true)
        );
        this.currencyCollection.createIndex(Indexes.ascending("id"), new IndexOptions().unique(true));
    }

    /**
     * Ensures TLS is enabled unless the connection string explicitly configures it otherwise.
     *
     * @param connectionString the raw MongoDB connection string
     * @return the connection string with TLS enabled when needed
     */
    private String enforceTls(String connectionString) {
        if (connectionString == null || connectionString.isBlank()) {
            throw new IllegalArgumentException("MongoDB connection string must not be blank");
        }
        if (connectionString.startsWith("mongodb+srv://")) {
            return connectionString;
        }
        if (!connectionString.contains("tls=") && !connectionString.contains("ssl=")) {
            char separator = connectionString.contains("?") ? '&' : '?';
            return connectionString + separator + "tls=true";
        }
        return connectionString;
    }

    /**
     * Retrieves the balance for the requested account and currency.
     *
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @param currencyId the currency identifier to read
     * @return a future containing the current balance
     */
    @Override
    public CompletableFuture<Long> getBalance(String accountId, String accountType, int currencyId) {
        return CompletableFuture.supplyAsync(() -> getBalanceSync(accountType, accountId, currencyId));
    }

    /**
     * Sets the balance for the requested account and currency.
     *
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @param currencyId the currency identifier to update
     * @param amount the new balance
     * @return a future that resolves to {@code true} when the update succeeds
     */
    @Override
    public CompletableFuture<Boolean> setBalance(String accountId, String accountType, int currencyId, long amount) {
        return CompletableFuture.supplyAsync(() -> setBalanceSync(accountType, accountId, currencyId, amount));
    }

    /**
     * Adds to the balance for the requested account and currency.
     *
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @param currencyId the currency identifier to update
     * @param amount the amount to add
     * @return a future that resolves to {@code true} when the update succeeds
     */
    @Override
    public CompletableFuture<Boolean> addBalance(String accountId, String accountType, int currencyId, long amount) {
        return CompletableFuture.supplyAsync(() -> addBalanceSync(accountType, accountId, currencyId, amount));
    }

    /**
     * Subtracts from the balance for the requested account and currency.
     *
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @param currencyId the currency identifier to update
     * @param amount the amount to subtract
     * @return a future that resolves to {@code true} when the subtraction succeeds
     */
    @Override
    public CompletableFuture<Boolean> subtractBalance(String accountId, String accountType, int currencyId, long amount) {
        return CompletableFuture.supplyAsync(() -> subtractBalanceSync(accountType, accountId, currencyId, amount));
    }

    /**
     * Transfers balance between two accounts for the requested currency.
     *
     * @param senderType the sender account type
     * @param senderId the sender account identifier
     * @param receiverType the receiver account type
     * @param receiverId the receiver account identifier
     * @param currencyId the currency identifier to transfer
     * @param amount the amount to transfer
     * @return a future that resolves to {@code true} when the transfer succeeds
     */
    @Override
    public CompletableFuture<Boolean> transferBalance(
            String senderId,
            String senderType,
            String receiverId,
            String receiverType,
            int currencyId,
            long amount
    ) {
        return CompletableFuture.supplyAsync(() ->
                transferBalanceSync(senderType, senderId, receiverType, receiverId, currencyId, amount)
        );
    }

    /**
     * Ensures the requested account and currency row exists.
     *
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @param currencyId the currency identifier to ensure
     * @return a future that resolves to {@code true} when the row exists
     */
    @Override
    public CompletableFuture<Boolean> ensureAccountExists(String accountId, String accountType, int currencyId) {
        return CompletableFuture.supplyAsync(() -> ensureAccountExistsSync(accountType, accountId, currencyId));
    }

    /**
     * Reads the current balance using a direct MongoDB query.
     *
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @param currencyId the currency identifier to read
     * @return the current balance, or {@code 0} when no document is available
     */
    private long getBalanceSync(String accountType, String accountId, int currencyId) {
        if (!ensureAccountExistsSync(accountType, accountId, currencyId)) {
            return 0L;
        }

        Document doc = accountCollection.find(getAccountFilter(accountType, accountId, currencyId)).first();
        if (doc == null) {
            return 0L;
        }

        Number value = (Number) doc.get("amount");
        return value != null ? Math.max(0L, value.longValue()) : 0L;
    }

    /**
     * Stores an absolute balance using a direct MongoDB update.
     *
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @param currencyId the currency identifier to update
     * @param amount the new balance
     * @return {@code true} when the update succeeds
     */
    private boolean setBalanceSync(String accountType, String accountId, int currencyId, long amount) {
        if (amount < 0 || !ensureAccountExistsSync(accountType, accountId, currencyId)) {
            return false;
        }

        Bson update = Updates.combine(
                Updates.set("amount", amount),
                Updates.currentDate("updated_at")
        );
        return accountCollection.updateOne(getAccountFilter(accountType, accountId, currencyId), update).getMatchedCount() > 0;
    }

    /**
     * Increments the current balance using a direct MongoDB update.
     *
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @param currencyId the currency identifier to update
     * @param amount the amount to add
     * @return {@code true} when the update succeeds
     */
    private boolean addBalanceSync(String accountType, String accountId, int currencyId, long amount) {
        if (amount <= 0 || !ensureAccountExistsSync(accountType, accountId, currencyId)) {
            return false;
        }

        Bson update = Updates.combine(
                Updates.inc("amount", amount),
                Updates.currentDate("updated_at")
        );
        return accountCollection.updateOne(getAccountFilter(accountType, accountId, currencyId), update).getMatchedCount() > 0;
    }

    /**
     * Decrements the current balance using a guarded MongoDB update.
     *
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @param currencyId the currency identifier to update
     * @param amount the amount to subtract
     * @return {@code true} when enough balance exists and the update succeeds
     */
    private boolean subtractBalanceSync(String accountType, String accountId, int currencyId, long amount) {
        if (amount <= 0 || currencyId <= 0) {
            return false;
        }

        ensureAccountExistsSync(accountType, accountId, currencyId);
        Bson filter = Filters.and(
                getAccountFilter(accountType, accountId, currencyId),
                Filters.gte("amount", amount)
        );
        Bson update = Updates.combine(
                Updates.inc("amount", -amount),
                Updates.currentDate("updated_at")
        );
        return accountCollection.updateOne(filter, update).getModifiedCount() > 0;
    }

    /**
     * Performs a transactional transfer between two MongoDB account documents.
     *
     * @param senderType the sender account type
     * @param senderId the sender account identifier
     * @param receiverType the receiver account type
     * @param receiverId the receiver account identifier
     * @param currencyId the currency identifier to transfer
     * @param amount the amount to transfer
     * @return {@code true} when the transaction commits successfully
     */
    private boolean transferBalanceSync(
            String senderType,
            String senderId,
            String receiverType,
            String receiverId,
            int currencyId,
            long amount
    ) {
        if (amount <= 0 || currencyId <= 0) {
            return false;
        }

        try (ClientSession session = mongoClient.startSession()) {
            session.startTransaction();
            try {
                if (!ensureAccountExists(session, senderType, senderId, currencyId)
                        || !ensureAccountExists(session, receiverType, receiverId, currencyId)) {
                    session.abortTransaction();
                    return false;
                }

                Bson withdrawFilter = Filters.and(
                        getAccountFilter(senderType, senderId, currencyId),
                        Filters.gte("amount", amount)
                );
                Bson withdrawUpdate = Updates.combine(
                        Updates.inc("amount", -amount),
                        Updates.currentDate("updated_at")
                );
                if (accountCollection.updateOne(session, withdrawFilter, withdrawUpdate).getModifiedCount() == 0) {
                    session.abortTransaction();
                    return false;
                }

                Bson depositUpdate = Updates.combine(
                        Updates.inc("amount", amount),
                        Updates.currentDate("updated_at")
                );
                accountCollection.updateOne(session, getAccountFilter(receiverType, receiverId, currencyId), depositUpdate);
                session.commitTransaction();
                return true;
            } catch (Exception e) {
                session.abortTransaction();
                e.printStackTrace();
                return false;
            }
        } catch (Exception ex) {
            System.err.println("[MCEconomy] Failed to execute transaction. Is MongoDB running as a Replica Set?");
            ex.printStackTrace();
            return false;
        }
    }

    /**
     * Ensures the account document exists for the requested account and currency.
     *
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @param currencyId the currency identifier to ensure
     * @return {@code true} when the account document exists
     */
    private boolean ensureAccountExistsSync(String accountType, String accountId, int currencyId) {
        if (currencyId <= 0) {
            return false;
        }

        try {
            ensureCurrencyExists(currencyId);
            Bson update = Updates.combine(
                    Updates.setOnInsert("account_type", accountType),
                    Updates.setOnInsert("account_id", accountId),
                    Updates.setOnInsert("currency_id", currencyId),
                    Updates.setOnInsert("amount", 0L),
                    Updates.setOnInsert("updated_at", new Date())
            );
            accountCollection.updateOne(
                    getAccountFilter(accountType, accountId, currencyId),
                    update,
                    new UpdateOptions().upsert(true)
            );
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Ensures the account document exists inside an active MongoDB transaction.
     *
     * @param session the active client session
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @param currencyId the currency identifier to ensure
     * @return {@code true} when the account document exists
     */
    private boolean ensureAccountExists(ClientSession session, String accountType, String accountId, int currencyId) {
        if (currencyId <= 0) {
            return false;
        }

        ensureCurrencyExists(session, currencyId);
        Bson update = Updates.combine(
                Updates.setOnInsert("account_type", accountType),
                Updates.setOnInsert("account_id", accountId),
                Updates.setOnInsert("currency_id", currencyId),
                Updates.setOnInsert("amount", 0L),
                Updates.setOnInsert("updated_at", new Date())
        );
        accountCollection.updateOne(
                session,
                getAccountFilter(accountType, accountId, currencyId),
                update,
                new UpdateOptions().upsert(true)
        );
        return true;
    }

    /**
     * Ensures the referenced currency document exists before account writes occur.
     *
     * @param currencyId the currency identifier to ensure
     */
    private void ensureCurrencyExists(int currencyId) {
        Bson update = Updates.combine(
                Updates.setOnInsert("id", currencyId),
                Updates.setOnInsert("name_display", defaultCurrencyName(currencyId)),
                Updates.setOnInsert("created_at", new Date())
        );
        currencyCollection.updateOne(Filters.eq("id", currencyId), update, new UpdateOptions().upsert(true));
    }

    /**
     * Ensures the referenced currency document exists inside an active MongoDB transaction.
     *
     * @param session the active client session
     * @param currencyId the currency identifier to ensure
     */
    private void ensureCurrencyExists(ClientSession session, int currencyId) {
        Bson update = Updates.combine(
                Updates.setOnInsert("id", currencyId),
                Updates.setOnInsert("name_display", defaultCurrencyName(currencyId)),
                Updates.setOnInsert("created_at", new Date())
        );
        currencyCollection.updateOne(session, Filters.eq("id", currencyId), update, new UpdateOptions().upsert(true));
    }

    /**
     * Builds the filter used to uniquely address an account and currency document.
     *
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @param currencyId the currency identifier
     * @return the MongoDB filter for the requested account document
     */
    private Bson getAccountFilter(String accountType, String accountId, int currencyId) {
        return Filters.and(
                Filters.eq("account_type", accountType),
                Filters.eq("account_id", accountId),
                Filters.eq("currency_id", currencyId)
        );
    }

    /**
     * Builds the fallback display name used for auto-created currency documents.
     *
     * @param currencyId the currency identifier
     * @return the generated display name
     */
    private String defaultCurrencyName(int currencyId) {
        return "Currency " + currencyId;
    }

    /**
     * Closes the MongoDB client.
     */
    @Override
    public void close() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }
}
