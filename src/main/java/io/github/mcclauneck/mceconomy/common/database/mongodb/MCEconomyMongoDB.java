package io.github.mcclauneck.mceconomy.common.database.mongodb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.ClientSession;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.github.mcclauneck.mceconomy.api.database.IMCEconomyDB;
import io.github.mcclauneck.mceconomy.api.enums.CoinLogOperation;
import io.github.mcclauneck.mceconomy.api.enums.CurrencyType;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;

/**
 * MongoDB implementation for MCEconomy.
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
     * The account collection used for economy data persistence.
     */
    private final MongoCollection<Document> collection;

    /**
     * The operation log collection.
     */
    private final MongoCollection<Document> logCollection;

    /**
     * Constructs a new MongoDB database handler.
     *
     * @param connectionString The standard MongoDB URI (e.g., "mongodb://user:pass@host:port/")
     * @param databaseName     The name of the database to use
     */
    public MCEconomyMongoDB(String connectionString, String databaseName) {
        String safeConnectionString = enforceTls(connectionString);
        this.mongoClient = MongoClients.create(safeConnectionString);
        this.database = this.mongoClient.getDatabase(databaseName);
        this.collection = this.database.getCollection("economy_accounts");
        this.logCollection = this.database.getCollection("mceconomy_logs");

        // Create a unique compound index for fast lookups and preventing duplicates
        this.collection.createIndex(
                Indexes.ascending("account_uuid", "account_type"), 
                new IndexOptions().unique(true)
        );

        this.logCollection.createIndex(Indexes.ascending("account_uuid", "account_type", "created_at"));
    }

    /**
     * Ensures the connection string requests TLS unless explicitly configured otherwise.
     *
     * @param connectionString raw MongoDB connection string
     * @return connection string with TLS enforced when missing
     */
    private String enforceTls(String connectionString) {
        if (connectionString == null || connectionString.isBlank()) {
            throw new IllegalArgumentException("MongoDB connection string must not be blank");
        }

        // mongodb+srv implies TLS by default
        if (connectionString.startsWith("mongodb+srv://")) {
            return connectionString;
        }

        // Append tls=true if neither tls nor ssl flags are present
        if (!connectionString.contains("tls=") && !connectionString.contains("ssl=")) {
            char separator = connectionString.contains("?") ? '&' : '?';
            return connectionString + separator + "tls=true";
        }
        return connectionString;
    }

    /**
     * Builds a filter that uniquely identifies an account by UUID and type.
     *
     * @param accountUuid unique account identifier
     * @param accountType logical account type
     * @return BSON filter for account lookup
     */
    private Bson getAccountFilter(String accountUuid, String accountType) {
        return Filters.and(
                Filters.eq("account_uuid", accountUuid),
                Filters.eq("account_type", accountType)
        );
    }

    /**
     * Ensures an account document exists (upsert).
     *
     * @param accountUuid unique account identifier
     * @param accountType logical account type
     * @return true on success; false on error
     */
    @Override
    public boolean ensureAccountExist(String accountUuid, String accountType) {
        try {
            Bson filter = getAccountFilter(accountUuid, accountType);
            Bson update = Updates.combine(
                    Updates.setOnInsert("account_uuid", accountUuid),
                    Updates.setOnInsert("account_type", accountType),
                    Updates.setOnInsert("coin", 0L),
                    Updates.setOnInsert("copper", 0L),
                    Updates.setOnInsert("silver", 0L),
                    Updates.setOnInsert("gold", 0L)
            );
            // Upsert creates the document if it doesn't exist
            collection.updateOne(filter, update, new UpdateOptions().upsert(true));
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
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
        ensureAccountExist(accountUuid, accountType);
        try {
            Document doc = collection.find(getAccountFilter(accountUuid, accountType)).first();
            if (doc != null) {
                Number value = (Number) doc.get(coinType.getName());
                long result = value != null ? Math.max(0L, value.longValue()) : 0L;
                logCoinOperation(CoinLogOperation.GET, accountUuid, accountType, null, null, coinType, result, true, null);
                return result;
            }
            logCoinOperation(CoinLogOperation.GET, accountUuid, accountType, null, null, coinType, 0L, false, "balance row not found");
            return 0L;
        } catch (Exception e) {
            logCoinOperation(CoinLogOperation.GET, accountUuid, accountType, null, null, coinType, 0L, false, e.getMessage());
            e.printStackTrace();
            return 0L;
        }
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
        if (amount < 0) {
            logCoinOperation(CoinLogOperation.SET, accountUuid, accountType, null, null, coinType, amount, false, "amount must be >= 0");
            return false;
        }
        ensureAccountExist(accountUuid, accountType);

        try {
            Bson filter = getAccountFilter(accountUuid, accountType);
            Bson update = Updates.set(coinType.getName(), amount);
            boolean success = collection.updateOne(filter, update).getMatchedCount() > 0;
            logCoinOperation(CoinLogOperation.SET, accountUuid, accountType, null, null, coinType, amount, success,
                    success ? null : "account not found");
            return success;
        } catch (Exception e) {
            logCoinOperation(CoinLogOperation.SET, accountUuid, accountType, null, null, coinType, amount, false, e.getMessage());
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
        if (amount <= 0) {
            logCoinOperation(CoinLogOperation.ADD, accountUuid, accountType, null, null, coinType, amount, false, "amount must be > 0");
            return false;
        }
        ensureAccountExist(accountUuid, accountType);

        try {
            Bson filter = getAccountFilter(accountUuid, accountType);
            Bson update = Updates.inc(coinType.getName(), amount);
            boolean success = collection.updateOne(filter, update).getMatchedCount() > 0;
            logCoinOperation(CoinLogOperation.ADD, accountUuid, accountType, null, null, coinType, amount, success,
                    success ? null : "account not found");
            return success;
        } catch (Exception e) {
            logCoinOperation(CoinLogOperation.ADD, accountUuid, accountType, null, null, coinType, amount, false, e.getMessage());
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
        if (amount <= 0) {
            logCoinOperation(CoinLogOperation.MINUS, accountUuid, accountType, null, null, coinType, amount, false, "amount must be > 0");
            return false;
        }
        
        // Filter: Must match UUID/Type AND the current balance must be >= amount
        Bson filter = Filters.and(
                getAccountFilter(accountUuid, accountType),
                Filters.gte(coinType.getName(), amount)
        );
        Bson update = Updates.inc(coinType.getName(), -amount); // Negative inc to subtract

        try {
            boolean success = collection.updateOne(filter, update).getModifiedCount() > 0;
            logCoinOperation(CoinLogOperation.MINUS, accountUuid, accountType, null, null, coinType, amount, success,
                    success ? null : "insufficient funds or account not found");
            return success;
        } catch (Exception e) {
            logCoinOperation(CoinLogOperation.MINUS, accountUuid, accountType, null, null, coinType, amount, false, e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Transfers funds between two accounts transactionally (requires replica set).
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
        if (amount <= 0) {
            logCoinOperation(CoinLogOperation.SEND, senderUuid, senderType, receiverUuid, receiverType, coinType, amount, false, "amount must be > 0");
            return false;
        }

        // Uses a MongoDB Session for ACID transactions (Requires Replica Set deployment)
        try (ClientSession session = mongoClient.startSession()) {
            session.startTransaction();
            try {
                // 1. Ensure both exist
                ensureAccountExist(senderUuid, senderType);
                ensureAccountExist(receiverUuid, receiverType);

                String fieldName = coinType.getName();

                // 2. Withdraw from sender
                Bson withdrawFilter = Filters.and(
                        getAccountFilter(senderUuid, senderType),
                        Filters.gte(fieldName, amount)
                );
                Bson withdrawUpdate = Updates.inc(fieldName, -amount);
                
                if (collection.updateOne(session, withdrawFilter, withdrawUpdate).getModifiedCount() == 0) {
                    session.abortTransaction(); // Insufficient funds or account missing
                    logCoinOperation(CoinLogOperation.SEND, senderUuid, senderType, receiverUuid, receiverType, coinType, amount, false,
                            "insufficient funds or sender not found");
                    return false;
                }

                // 3. Deposit to receiver
                Bson depositFilter = getAccountFilter(receiverUuid, receiverType);
                Bson depositUpdate = Updates.inc(fieldName, amount);
                collection.updateOne(session, depositFilter, depositUpdate);

                // 4. Commit
                session.commitTransaction();
                logCoinOperation(CoinLogOperation.SEND, senderUuid, senderType, receiverUuid, receiverType, coinType, amount, true, null);
                return true;
            } catch (Exception e) {
                session.abortTransaction();
                logCoinOperation(CoinLogOperation.SEND, senderUuid, senderType, receiverUuid, receiverType, coinType, amount, false, e.getMessage());
                e.printStackTrace();
                return false;
            }
        } catch (Exception ex) {
            // Fallback error (e.g., if the MongoDB server isn't a replica set and doesn't support transactions)
            System.err.println("[MCEconomy] Failed to execute transaction. Is MongoDB running as a Replica Set?");
            logCoinOperation(CoinLogOperation.SEND, senderUuid, senderType, receiverUuid, receiverType, coinType, amount, false, ex.getMessage());
            ex.printStackTrace();
            return false;
        }
    }

    @Override
    public void logCoinOperation(
            CoinLogOperation operation,
            String accountUuid,
            String accountType,
            String targetUuid,
            String targetType,
            CurrencyType coinType,
            long amount,
            boolean success,
            String message
    ) {
        try {
            Document logDoc = new Document("operation", operation.name())
                    .append("account_uuid", accountUuid)
                    .append("account_type", accountType)
                    .append("target_uuid", targetUuid)
                    .append("target_type", targetType)
                    .append("currency", coinType.name())
                    .append("amount", amount)
                    .append("success", success)
                    .append("message", message)
                    .append("created_at", Instant.now().toString());
            logCollection.insertOne(logDoc);
        } catch (Exception e) {
            e.printStackTrace();
        }
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
