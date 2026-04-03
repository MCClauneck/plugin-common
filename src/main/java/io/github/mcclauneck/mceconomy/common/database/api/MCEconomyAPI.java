package io.github.mcclauneck.mceconomy.common.database.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mcclauneck.mceconomy.api.database.IMCEconomyDB;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket-backed remote implementation of the asynchronous economy database API.
 */
public class MCEconomyAPI implements IMCEconomyDB, WebSocket.Listener {

    /**
     * The HMAC algorithm used to sign outgoing payloads.
     */
    private static final String HMAC_ALGO = "HmacSHA256";

    /**
     * The fully resolved WebSocket endpoint URL.
     */
    private final String apiUrl;

    /**
     * The shared API token used for authentication and signing.
     */
    private final String apiToken;

    /**
     * The JSON serializer used for outbound payloads.
     */
    private final Gson gson;

    /**
     * The map of pending request identifiers to their completion futures.
     */
    private final ConcurrentHashMap<String, CompletableFuture<JsonObject>> pendingRequests;

    /**
     * The temporary message buffer used when fragmented WebSocket frames arrive.
     */
    private final StringBuilder messageBuffer;

    /**
     * The active WebSocket connection.
     */
    private WebSocket webSocket;

    /**
     * Creates a new WebSocket-backed economy database adapter.
     *
     * @param apiUrl the WebSocket endpoint URL
     * @param apiToken the shared API token
     */
    public MCEconomyAPI(String apiUrl, String apiToken) {
        this.apiUrl = apiUrl;
        this.apiToken = apiToken;
        this.gson = new Gson();
        this.pendingRequests = new ConcurrentHashMap<>();
        this.messageBuffer = new StringBuilder();
        connect();
    }

    /**
     * Opens the WebSocket connection to the configured API endpoint.
     */
    private void connect() {
        HttpClient client = HttpClient.newBuilder().build();
        this.webSocket = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(this.apiUrl), this)
                .join();
    }

    /**
     * Handles the WebSocket open event.
     *
     * @param webSocket the opened WebSocket connection
     */
    @Override
    public void onOpen(WebSocket webSocket) {
        System.out.println("[MCEconomy API] WebSocket connected successfully.");
        WebSocket.Listener.super.onOpen(webSocket);
    }

    /**
     * Handles inbound text frames and resolves the matching pending request.
     *
     * @param webSocket the active WebSocket connection
     * @param data the received text fragment
     * @param last whether the fragment completes the message
     * @return the completion stage for the listener callback
     */
    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        synchronized (messageBuffer) {
            messageBuffer.append(data);
            if (last) {
                try {
                    JsonObject response = JsonParser.parseString(messageBuffer.toString()).getAsJsonObject();
                    if (response.has("request_id")) {
                        CompletableFuture<JsonObject> future = pendingRequests.remove(response.get("request_id").getAsString());
                        if (future != null) {
                            future.complete(response);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[MCEconomy API] Failed to parse incoming WebSocket message: " + e.getMessage());
                } finally {
                    messageBuffer.setLength(0);
                }
            }
        }

        webSocket.request(1);
        return null;
    }

    /**
     * Handles the WebSocket close event and completes all pending requests.
     *
     * @param webSocket the closed WebSocket connection
     * @param statusCode the close status code
     * @param reason the close reason
     * @return the completion stage for the listener callback
     */
    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        System.err.println("[MCEconomy API] WebSocket closed: " + statusCode + " - " + reason);
        for (CompletableFuture<JsonObject> future : pendingRequests.values()) {
            future.complete(null);
        }
        pendingRequests.clear();
        return null;
    }

    /**
     * Handles WebSocket transport errors.
     *
     * @param webSocket the active WebSocket connection
     * @param error the transport error
     */
    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        System.err.println("[MCEconomy API] WebSocket Error: " + error.getMessage());
        WebSocket.Listener.super.onError(webSocket, error);
    }

    /**
     * Sends a request on a background thread and resolves with the raw JSON response.
     *
     * @param payload the payload to send
     * @return a future containing the raw response, or {@code null} on timeout
     */
    private CompletableFuture<JsonObject> sendWsRequestAsync(JsonObject payload) {
        return CompletableFuture.supplyAsync(() -> sendWsRequest(payload));
    }

    /**
     * Sends a request synchronously and waits for the correlated response.
     *
     * @param payload the payload to send
     * @return the raw JSON response, or {@code null} on timeout or failure
     */
    private JsonObject sendWsRequest(JsonObject payload) {
        if (this.webSocket == null || this.webSocket.isOutputClosed()) {
            connect();
        }

        String requestId = UUID.randomUUID().toString();
        payload.addProperty("request_id", requestId);
        payload.addProperty("token", this.apiToken);
        payload.addProperty("timestamp", System.currentTimeMillis());
        payload.addProperty("nonce", UUID.randomUUID().toString());

        String signature = signPayload(payload);
        if (signature != null) {
            payload.addProperty("signature", signature);
        }

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        this.webSocket.sendText(gson.toJson(payload), true);

        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("[MCEconomy API] Request timed out or failed: " + requestId);
            pendingRequests.remove(requestId);
            return null;
        }
    }

    /**
     * Builds a standard single-account payload with ordered account fields.
     *
     * @param action the action name to execute remotely
     * @param accountType the logical account type
     * @param accountId the unique account identifier
     * @param currencyId the currency identifier
     * @param amount the optional amount payload, or {@code null} when unused
     * @return the constructed JSON payload
     */
    private JsonObject buildBalancePayload(String action, String accountType, String accountId, int currencyId, Long amount) {
        JsonObject json = new JsonObject();
        json.addProperty("action", action);
        json.addProperty("account_type", accountType);
        json.addProperty("account_id", accountId);
        json.addProperty("currency_id", currencyId);
        if (amount != null) {
            json.addProperty("amount", amount);
        }
        return json;
    }

    /**
     * Computes the HMAC signature for the provided payload.
     *
     * @param payload the payload to sign
     * @return the Base64-encoded signature, or {@code null} when signing fails
     */
    private String signPayload(JsonObject payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(apiToken.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] signature = mac.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature);
        } catch (Exception e) {
            System.err.println("[MCEconomy API] Failed to sign payload: " + e.getMessage());
            return null;
        }
    }

    /**
     * Retrieves the balance for the requested account and currency identifier.
     *
     * @param accountId the unique account identifier
     * @param accountType the logical account type
     * @param currencyId the currency identifier to read
     * @return a future containing the remote balance value
     */
    @Override
    public CompletableFuture<Long> getBalance(String accountId, String accountType, int currencyId) {
        JsonObject payload = buildBalancePayload("get_balance", accountType, accountId, currencyId, null);
        return sendWsRequestAsync(payload).thenApply(response -> {
            if (response != null && response.has("success") && response.get("success").getAsBoolean()) {
                return response.has("data") ? response.get("data").getAsLong() : 0L;
            }
            return 0L;
        });
    }

    /**
     * Sets the balance for the requested account and currency identifier.
     *
     * @param accountId the unique account identifier
     * @param accountType the logical account type
     * @param currencyId the currency identifier to update
     * @param amount the new balance
     * @return a future that resolves to {@code true} when the remote update succeeds
     */
    @Override
    public CompletableFuture<Boolean> setBalance(String accountId, String accountType, int currencyId, long amount) {
        if (amount < 0 || currencyId <= 0) {
            return CompletableFuture.completedFuture(false);
        }
        JsonObject payload = buildBalancePayload("set_balance", accountType, accountId, currencyId, amount);
        return sendWsRequestAsync(payload).thenApply(this::isSuccessfulResponse);
    }

    /**
     * Adds to the balance for the requested account and currency identifier.
     *
     * @param accountId the unique account identifier
     * @param accountType the logical account type
     * @param currencyId the currency identifier to update
     * @param amount the amount to add
     * @return a future that resolves to {@code true} when the remote update succeeds
     */
    @Override
    public CompletableFuture<Boolean> addBalance(String accountId, String accountType, int currencyId, long amount) {
        if (amount <= 0 || currencyId <= 0) {
            return CompletableFuture.completedFuture(false);
        }
        JsonObject payload = buildBalancePayload("add_balance", accountType, accountId, currencyId, amount);
        return sendWsRequestAsync(payload).thenApply(this::isSuccessfulResponse);
    }

    /**
     * Subtracts from the balance for the requested account and currency identifier.
     *
     * @param accountId the unique account identifier
     * @param accountType the logical account type
     * @param currencyId the currency identifier to update
     * @param amount the amount to subtract
     * @return a future that resolves to {@code true} when the remote update succeeds
     */
    @Override
    public CompletableFuture<Boolean> subtractBalance(String accountId, String accountType, int currencyId, long amount) {
        if (amount <= 0 || currencyId <= 0) {
            return CompletableFuture.completedFuture(false);
        }
        JsonObject payload = buildBalancePayload("subtract_balance", accountType, accountId, currencyId, amount);
        return sendWsRequestAsync(payload).thenApply(this::isSuccessfulResponse);
    }

    /**
     * Transfers the requested currency between two accounts.
     *
     * @param senderId the sender account identifier
     * @param senderType the sender account type
     * @param receiverId the receiver account identifier
     * @param receiverType the receiver account type
     * @param currencyId the currency identifier to transfer
     * @param amount the amount to transfer
     * @return a future that resolves to {@code true} when the remote transfer succeeds
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
        if (amount <= 0 || currencyId <= 0) {
            return CompletableFuture.completedFuture(false);
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("action", "transfer_balance");
        payload.addProperty("sender_type", senderType);
        payload.addProperty("sender_id", senderId);
        payload.addProperty("receiver_type", receiverType);
        payload.addProperty("receiver_id", receiverId);
        payload.addProperty("currency_id", currencyId);
        payload.addProperty("amount", amount);

        return sendWsRequestAsync(payload).thenApply(this::isSuccessfulResponse);
    }

    /**
     * Ensures the requested account and currency row exists remotely.
     *
     * @param accountId the unique account identifier
     * @param accountType the logical account type
     * @param currencyId the currency identifier to ensure
     * @return a future that resolves to {@code true} when the remote row exists
     */
    @Override
    public CompletableFuture<Boolean> ensureAccountExists(String accountId, String accountType, int currencyId) {
        if (currencyId <= 0) {
            return CompletableFuture.completedFuture(false);
        }
        JsonObject payload = buildBalancePayload("ensure_account_exists", accountType, accountId, currencyId, null);
        return sendWsRequestAsync(payload).thenApply(this::isSuccessfulResponse);
    }

    /**
     * Evaluates whether the provided JSON response represents success.
     *
     * @param response the raw JSON response
     * @return {@code true} when the response contains a successful result
     */
    private boolean isSuccessfulResponse(JsonObject response) {
        return response != null && response.has("success") && response.get("success").getAsBoolean();
    }

    /**
     * Closes the active WebSocket connection.
     */
    @Override
    public void close() {
        if (this.webSocket != null && !this.webSocket.isOutputClosed()) {
            this.webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Server shutting down");
        }
    }
}
