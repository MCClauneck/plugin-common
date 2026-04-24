package io.github.mcclauneck.mceconomy.common.database.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mcclauneck.mceconomy.api.database.IMCEconomyDB;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP POST-backed remote implementation of the asynchronous economy database API.
 */
public class MCEconomyAPI implements IMCEconomyDB {

    /**
     * The environment variable name for the API endpoint URL.
     */
    private static final String ENV_URL = "MCSERVER_URL";

    /**
     * The environment variable name for the API authentication token.
     */
    private static final String ENV_TOKEN = "MCSERVER_API_TOKEN";

    /**
     * The fully resolved API endpoint URL.
     */
    private final String apiUrl;

    /**
     * The shared API token used for authentication.
     */
    private final String apiToken;

    /**
     * The HTTP client used for making requests.
     */
    private final HttpClient httpClient;

    /**
     * The JSON serializer used for payloads.
     */
    private final Gson gson;

    /**
     * Creates a new HTTP-backed economy database adapter using environment variables.
     */
    public MCEconomyAPI() {
        this.apiUrl = System.getenv(ENV_URL);
        this.apiToken = System.getenv(ENV_TOKEN);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.gson = new Gson();

        if (this.apiUrl == null || this.apiToken == null) {
            System.err.println("[MCEconomy API] Missing environment variables: " + ENV_URL + " or " + ENV_TOKEN);
        }
    }

    /**
     * Sends a POST request to the economy API.
     *
     * @param payload the JSON payload to send
     * @return a future containing the JSON response, or an empty JsonObject on failure
     */
    private CompletableFuture<JsonObject> sendRequest(JsonObject payload) {
        if (this.apiUrl == null || this.apiToken == null) {
            return CompletableFuture.completedFuture(new JsonObject());
        }

        // Token is now handled via Authorization Header instead of JSON payload
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(this.apiUrl + "/api/economy"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + this.apiToken)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return JsonParser.parseString(response.body()).getAsJsonObject();
                    } else {
                        System.err.println("[MCEconomy API] Request failed with status code: " + response.statusCode());
                        return new JsonObject();
                    }
                })
                .exceptionally(ex -> {
                    System.err.println("[MCEconomy API] Request exception: " + ex.getMessage());
                    return new JsonObject();
                });
    }

    @Override
    public CompletableFuture<Long> getBalance(String accountType, String accountId, int currencyId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("action", "get_coin");
        payload.addProperty("account_type", accountType);
        payload.addProperty("account_uuid", accountId);

        return sendRequest(payload).thenApply(response -> {
            if (response.has("balances")) {
                JsonObject balances = response.getAsJsonObject("balances");
                String coinType = mapCurrencyIdToType(currencyId);
                if (balances.has(coinType)) {
                    return balances.get(coinType).getAsLong();
                }
            }
            return 0L;
        });
    }

    @Override
    public CompletableFuture<Boolean> setBalance(String accountType, String accountId, int currencyId, long amount) {
        // The new API doesn't seem to have a direct 'set_coin' action.
        // We'll have to get the current balance and then add/minus the difference.
        return getBalance(accountType, accountId, currencyId).thenCompose(currentBalance -> {
            long difference = amount - currentBalance;
            if (difference > 0) {
                return addBalance(accountType, accountId, currencyId, difference);
            } else if (difference < 0) {
                return subtractBalance(accountType, accountId, currencyId, Math.abs(difference));
            }
            return CompletableFuture.completedFuture(true);
        });
    }

    @Override
    public CompletableFuture<Boolean> addBalance(String accountType, String accountId, int currencyId, long amount) {
        JsonObject payload = new JsonObject();
        payload.addProperty("action", "add_coin");
        payload.addProperty("account_type", accountType);
        payload.addProperty("account_uuid", accountId);
        payload.addProperty("coin_type", mapCurrencyIdToType(currencyId));
        payload.addProperty("amount", amount);

        return sendRequest(payload).thenApply(response -> response.has("message") && response.get("message").getAsString().equals("Coin added"));
    }

    @Override
    public CompletableFuture<Boolean> subtractBalance(String accountType, String accountId, int currencyId, long amount) {
        JsonObject payload = new JsonObject();
        payload.addProperty("action", "minus_coin");
        payload.addProperty("account_type", accountType);
        payload.addProperty("account_uuid", accountId);
        payload.addProperty("coin_type", mapCurrencyIdToType(currencyId));
        payload.addProperty("amount", amount);

        return sendRequest(payload).thenApply(response -> response.has("message") && response.get("message").getAsString().equals("Coin deducted"));
    }

    @Override
    public CompletableFuture<Boolean> transferBalance(String senderId, String senderType, String receiverId, String receiverType, int currencyId, long amount) {
        // The new API doesn't have a direct 'transfer' action.
        // Implementing as a sequence of subtract and add.
        return subtractBalance(senderType, senderId, currencyId, amount)
                .thenCompose(success -> {
                    if (success) {
                        return addBalance(receiverType, receiverId, currencyId, amount);
                    }
                    return CompletableFuture.completedFuture(false);
                });
    }

    @Override
    public CompletableFuture<Boolean> ensureAccountExists(String accountType, String accountId, int currencyId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("action", "create_account");
        payload.addProperty("account_type", accountType);
        payload.addProperty("account_uuid", accountId);

        return sendRequest(payload).thenApply(response -> response.has("message") && response.get("message").getAsString().contains("Account verified/created"));
    }

    @Override
    public void close() {
        // HttpClient doesn't need explicit closing in this context.
    }

    /**
     * Maps the internal currency identifier to the API's coin_type string.
     *
     * @param currencyId the currency identifier
     * @return the corresponding coin_type string
     */
    private String mapCurrencyIdToType(int currencyId) {
        switch (currencyId) {
            case 1: return "coin";
            case 2: return "copper";
            case 3: return "silver";
            case 4: return "gold";
            default: return "coin";
        }
    }
}
