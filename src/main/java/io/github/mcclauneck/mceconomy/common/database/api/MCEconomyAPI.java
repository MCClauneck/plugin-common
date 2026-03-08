package io.github.mcclauneck.mceconomy.common.database.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mcclauneck.mceconomy.api.database.IMCEconomyDB;
import io.github.mcclauneck.mceconomy.api.enums.CurrencyType;

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
 * WebSocket API implementation for MCEconomy.
 */
public class MCEconomyAPI implements IMCEconomyDB, WebSocket.Listener {

    /**
     * The fully resolved WebSocket API endpoint URL.
     */
    private final String apiUrl;

    /**
     * The shared API token used for authentication and request signing.
     */
    private final String apiToken;

    /**
     * Gson serializer/deserializer for payload conversion.
     */
    private final Gson gson;

    /**
     * HMAC algorithm used to sign request payloads.
     */
    private static final String HMAC_ALGO = "HmacSHA256";

    /**
     * Active WebSocket connection instance.
     */
    private WebSocket webSocket;

    /**
     * Maps request IDs to waiting futures for correlated async responses.
     */
    private final ConcurrentHashMap<String, CompletableFuture<JsonObject>> pendingRequests;

    /**
     * Temporary buffer for fragmented incoming WebSocket frames.
     */
    private final StringBuilder messageBuffer;

    /**
     * Constructs a new WebSocket database handler.
     *
     * @param apiUrl   The fully resolved WebSocket URL (e.g., ws://api.domain.com/ws)
     * @param apiToken The authorization token for the API
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
     * Initializes and connects the WebSocket.
     */
    private void connect() {
        HttpClient client = HttpClient.newBuilder().build();
        this.webSocket = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(this.apiUrl), this)
                .join();
    }

    // --- WebSocket Listener Methods ---

    /**
     * Called when the WebSocket connection is opened.
     *
     * @param webSocket the opened WebSocket connection
     */
    @Override
    public void onOpen(WebSocket webSocket) {
        System.out.println("[MCEconomy API] WebSocket connected successfully.");
        WebSocket.Listener.super.onOpen(webSocket);
    }

    /**
     * Handles incoming WebSocket text frames and resolves pending requests.
     *
     * @param webSocket the active WebSocket connection
     * @param data       text frame content
     * @param last       whether this frame is the final fragment of the message
     * @return completion stage for the listener callback
     */
    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        synchronized (messageBuffer) {
            // Accumulate data because WebSocket messages can arrive in fragmented parts
            messageBuffer.append(data);

            if (last) {
                try {
                    JsonObject response = JsonParser.parseString(messageBuffer.toString()).getAsJsonObject();
                    if (response.has("request_id")) {
                        String reqId = response.get("request_id").getAsString();
                        CompletableFuture<JsonObject> future = pendingRequests.remove(reqId);
                        if (future != null) {
                            future.complete(response); // This unblocks the waiting thread
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[MCEconomy API] Failed to parse incoming WebSocket message: " + e.getMessage());
                } finally {
                    messageBuffer.setLength(0); // Clear buffer for the next message
                }
            }
        }

        webSocket.request(1);
        return null;
    }

    /**
     * Called when the WebSocket is closed and clears pending requests.
     *
     * @param webSocket  the closed WebSocket connection
     * @param statusCode close status code
     * @param reason     close reason text
     * @return completion stage for the listener callback
     */
    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        System.err.println("[MCEconomy API] WebSocket closed: " + statusCode + " - " + reason);
        // Fail all pending requests so they don't hang forever
        for (CompletableFuture<JsonObject> future : pendingRequests.values()) {
            future.complete(null);
        }
        pendingRequests.clear();
        return null;
    }

    /**
     * Called when a WebSocket error occurs.
     *
     * @param webSocket the active WebSocket connection
     * @param error     the error thrown by the WebSocket client
     */
    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        System.err.println("[MCEconomy API] WebSocket Error: " + error.getMessage());
        WebSocket.Listener.super.onError(webSocket, error);
    }

    // --- Core Request Logic ---

    /**
     * Sends the JSON payload and blocks until the specific response arrives.
     *
     * @param payload request payload to send
     * @return JSON response object, or null if timeout/failure occurs
     */
    private JsonObject sendWsRequest(JsonObject payload) {
        // Ensure connection is active (very basic check, you may want a robust reconnect loop)
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

        // Send over WebSocket
        this.webSocket.sendText(gson.toJson(payload), true);

        try {
            // Block this thread for up to 5 seconds waiting for the API response.
            // If it takes longer than 5 seconds, it will throw a TimeoutException.
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("[MCEconomy API] Request timed out or failed: " + requestId);
            pendingRequests.remove(requestId);
            return null;
        }
    }

    /**
     * Builds a standard request payload.
     *
     * @param action      action name to execute
     * @param accountUuid account UUID
     * @param accountType account type
     * @param coinType    optional currency type
     * @param amount      amount to include (ignored when negative)
     * @return constructed payload object
     */
    private JsonObject buildStandardPayload(String action, String accountUuid, String accountType, CurrencyType coinType, long amount) {
        JsonObject json = new JsonObject();
        json.addProperty("action", action);
        json.addProperty("account_uuid", accountUuid);
        json.addProperty("account_type", accountType);
        if (coinType != null) json.addProperty("coin_type", coinType.getName());
        if (amount >= 0) json.addProperty("amount", amount);
        return json;
    }

    /**
     * Computes an HMAC signature for the payload to mitigate replay/tampering (server must validate).
     *
     * @param payload payload to sign
     * @return Base64-encoded signature string, or null on failure
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

    // --- IMCEconomyDB Implementation ---

    /**
     * Ensures an account row exists via WebSocket.
     *
     * @param accountUuid unique account identifier
     * @param accountType logical account type
     * @return true when present/created; false on error
     */
    @Override
    public boolean ensureAccountExist(String accountUuid, String accountType) {
        JsonObject payload = buildStandardPayload("ensure_account", accountUuid, accountType, null, -1);
        JsonObject response = sendWsRequest(payload);
        return response != null && response.has("success") && response.get("success").getAsBoolean();
    }

    /**
     * Reads a coin balance via WebSocket.
     *
     * @param accountUuid unique account identifier
     * @param accountType logical account type
     * @param coinType    currency to fetch
     * @return non-negative balance
     */
    @Override
    public long getCoin(String accountUuid, String accountType, CurrencyType coinType) {
        JsonObject payload = buildStandardPayload("get_coin", accountUuid, accountType, coinType, -1);
        JsonObject response = sendWsRequest(payload);
        
        if (response != null && response.has("success") && response.get("success").getAsBoolean()) {
            return response.has("data") ? response.get("data").getAsLong() : 0L;
        }
        return 0L;
    }

    /**
     * Sets a coin balance to an absolute amount via WebSocket.
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
        JsonObject payload = buildStandardPayload("set_coin", accountUuid, accountType, coinType, amount);
        JsonObject response = sendWsRequest(payload);
        return response != null && response.has("success") && response.get("success").getAsBoolean();
    }

    /**
     * Adds to a coin balance via WebSocket.
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
        JsonObject payload = buildStandardPayload("add_coin", accountUuid, accountType, coinType, amount);
        JsonObject response = sendWsRequest(payload);
        return response != null && response.has("success") && response.get("success").getAsBoolean();
    }

    /**
     * Subtracts from a coin balance with non-negative guard via WebSocket.
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
        JsonObject payload = buildStandardPayload("minus_coin", accountUuid, accountType, coinType, amount);
        JsonObject response = sendWsRequest(payload);
        return response != null && response.has("success") && response.get("success").getAsBoolean();
    }

    /**
     * Transfers funds between two accounts via WebSocket.
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
        
        JsonObject payload = new JsonObject();
        payload.addProperty("action", "send_coin");
        payload.addProperty("sender_uuid", senderUuid);
        payload.addProperty("sender_type", senderType);
        payload.addProperty("receiver_uuid", receiverUuid);
        payload.addProperty("receiver_type", receiverType);
        payload.addProperty("coin_type", coinType.getName());
        payload.addProperty("amount", amount);

        JsonObject response = sendWsRequest(payload);
        return response != null && response.has("success") && response.get("success").getAsBoolean();
    }

    /**
     * Closes the WebSocket connection.
     */
    @Override
    public void close() {
        if (this.webSocket != null && !this.webSocket.isOutputClosed()) {
            this.webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Server shutting down");
        }
    }
}
