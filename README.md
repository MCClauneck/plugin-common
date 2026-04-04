# MCEconomy Common

**MCEconomy Common** is a lightweight, asynchronous economy interface designed for high-performance environments like FoliaMC.  
It uses `CompletableFuture` to ensure non-blocking database operations and supports multiple account types with dynamic currencies.

---

## 📦 Dependency

- **Group:** `io.github.mcclauneck`  
- **Artifact:** `mceconomy-common`  
- **Version:** `2026.0.5-3` 

---

## 📥 Installation

### Maven

<details>
<summary><strong>Click to expand Maven setup</strong></summary>

#### 1. Add Repository
```xml
<repositories>
    <repository>
        <id>github</id>
        <name>GitHub Packages</name>
        <url>https://maven.pkg.github.com/mcclauneck/plugin-common</url>
    </repository>
</repositories>
```

#### 2. Add Dependency
```xml
<dependency>
  <groupId>io.github.mcclauneck</groupId>
  <artifactId>mceconomy-common</artifactId>
  <version>2026.0.5-3</version>
</dependency>
```

#### 3. Credentials (settings.xml)
```xml
<servers>
    <server>
        <id>github</id>
        <username>${env.GITHUB_ACTOR}</username>
        <password>${env.GITHUB_TOKEN}</password>
    </server>
</servers>
```

</details>

---

### Gradle (Groovy)

<details>
<summary><strong>Click to expand Gradle Groovy setup</strong></summary>

#### 1. Repository
```groovy
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/mcclauneck/plugin-common")
        credentials {
            username = System.getenv('GITHUB_ACTOR') 
                ?: System.getenv('AGENT_GITHUB_NAME') 
                ?: System.getenv('USER_GITHUB_NAME')

            password = System.getenv('GITHUB_TOKEN') 
                ?: System.getenv('AGENT_GITHUB_TOKEN') 
                ?: System.getenv('USER_GITHUB_TOKEN')
        }
    }
}
```

#### 2. Dependency
```groovy
dependencies {
    implementation 'io.github.mcclauneck:mceconomy-common:2026.0.5-3'
}
```

</details>

---

### Gradle (Kotlin DSL)

<details>
<summary><strong>Click to expand Gradle Kotlin setup</strong></summary>

#### 1. Repository
```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/mcclauneck/plugin-common")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
                ?: System.getenv("AGENT_GITHUB_NAME")
                ?: System.getenv("USER_GITHUB_NAME")

            password = System.getenv("GITHUB_TOKEN")
                ?: System.getenv("AGENT_GITHUB_TOKEN")
                ?: System.getenv("USER_GITHUB_TOKEN")
        }
    }
}
```

#### 2. Dependency
```kotlin
dependencies {
    implementation("io.github.mcclauneck:mceconomy-common:2026.0.5-3")
}
```

</details>

---

## 🔐 Credentials Configuration

```text
CREDENTIALS_STRUCTURE:

USERNAME = System.getenv('GITHUB_ACTOR')
        ?: System.getenv('AGENT_GITHUB_NAME') 
        ?: System.getenv('USER_GITHUB_NAME')

PASSWORD = System.getenv('GITHUB_TOKEN')
        ?: System.getenv('AGENT_GITHUB_TOKEN') 
        ?: System.getenv('USER_GITHUB_TOKEN')
```

---

## ⚙️ How it Works

MCEconomy Common is designed around **Asynchronous Non-Blocking Operations**. This means all interactions with the database (whether local like SQLite, or remote like MongoDB, MySQL, PostgreSQL, or a WebSocket API) run on separate threads and do not block the main server thread. This is crucial for maintaining server performance (TPS), especially on modern server software like FoliaMC.

### 1. Asynchronous Futures (`CompletableFuture`)
Every method in the `MCEconomyProvider` and `IMCEconomyDB` interfaces returns a `CompletableFuture`. A `CompletableFuture` represents a value that might not be available yet. 

Instead of waiting (blocking) for the database to respond, you attach a callback (like `.thenAccept(...)` or `.thenApply(...)`) that will execute once the database operation finishes.

```java
// Good (Asynchronous - Non-blocking):
provider.getBalance("PLAYER", playerUUID.toString(), 1)
    .thenAccept(balance -> {
        // This code runs LATER, when the database responds.
        player.sendMessage("Your balance is: " + balance);
    });
// The server continues ticking immediately without waiting.

// BAD (Synchronous - Blocking):
// long balance = provider.getBalance("PLAYER", playerUUID.toString(), 1).join();
// This freezes the server thread until the database responds! Avoid this!
```

### 2. Provider Pattern (`MCEconomyProvider`)
The `MCEconomyProvider` is a high-level wrapper around the underlying database implementation (`IMCEconomyDB`). It acts as a singleton access point for the entire economy ecosystem.

It provides convenience methods that default to a `currencyId` of `1` if you don't specify one, simplifying code when you only use a single primary currency.

```java
// Using the provider (assumes currencyId = 1)
MCEconomyProvider.getInstance().addBalance("PLAYER", playerUUID.toString(), 500)
    .thenAccept(success -> {
        if (success) System.out.println("Added 500 to default currency.");
    });
```

### 3. Account Types & IDs (`accountType`, `accountId`)
MCEconomy is flexible and isn't just for players. It uses a generic "Account Type" and "Account ID" system.

- **`accountType`**: Defines what *kind* of entity owns the account. (e.g., `"PLAYER"`, `"CLAN"`, `"FACTION"`, `"TOWN"`, `"SERVER"`).
- **`accountId`**: The unique identifier for that specific entity. (e.g., a Player's UUID string, a Clan's ID).

This structure allows you to use the exact same API to manage clan banks as you use for player wallets.

### 4. Dynamic Currencies (`currencyId`)
Instead of hardcoding "Coins" or "Tokens", MCEconomy supports infinite custom currencies identified by an Integer `currencyId`.
- `1` might be your primary "Coins".
- `2` might be a premium "Gems" currency.
- `3` might be a seasonal event currency.

### 5. Thread Safety & Synchronization (Internal)
While you interact with the asynchronous `CompletableFuture` API, the internal database implementations (e.g., `MCEconomyMySQL`, `MCEconomyMongoDB`) handle the actual synchronization safely. They ensure that operations like `transferBalance` or `subtractBalance` correctly check balances before modifying them, preventing race conditions or money duplication bugs, even under heavy load.

---

## 📘 API Overview

<details>
<summary><strong>MCEconomyProvider Interface</strong></summary>

### Methods

```java
CompletableFuture<Long> getBalance(String accountType, String accountId, int currencyId);
CompletableFuture<Boolean> setBalance(String accountType, String accountId, int currencyId, long amount);
CompletableFuture<Boolean> addBalance(String accountType, String accountId, int currencyId, long amount);
CompletableFuture<Boolean> subtractBalance(String accountType, String accountId, int currencyId, long amount);
CompletableFuture<Boolean> transferBalance(String senderType, String senderId, String receiverType, String receiverId, int currencyId, long amount);
CompletableFuture<Boolean> ensureAccountExists(String accountType, String accountId, int currencyId);
void shutdown();
```

</details>

---

## ⚡ Usage Examples

### Fetching a Balance
```java
MCEconomyProvider provider = MCEconomyProvider.getInstance();

// Fetch balance for currency ID 1
provider.getBalance("PLAYER", playerUUID.toString(), 1)
    .thenAccept(balance -> {
        System.out.println("Balance: " + balance);
    });
```

### Adding Funds
```java
// Add 100 to currency ID 1
provider.addBalance("PLAYER", playerUUID.toString(), 1, 100)
    .thenAccept(success -> {
        if (success) {
            System.out.println("Balance successfully updated!");
        } else {
            System.out.println("Failed to update balance.");
        }
    });
```

### Transferring Funds (e.g., Player to Clan)
```java
// Transfer 500 of currency ID 1 from a Player to a Clan Bank
provider.transferBalance(
        "PLAYER", playerUUID.toString(),  // Sender
        "CLAN", clanId.toString(),        // Receiver
        1,                                // Currency ID
        500                               // Amount
    ).thenAccept(success -> {
        if (success) {
            System.out.println("Transfer successful!");
        } else {
            System.out.println("Transfer failed! (Insufficient funds or error)");
        }
    });
```


