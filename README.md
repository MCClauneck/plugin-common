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

## ⚡ Usage Example

<details>
<summary><strong>Click to expand example</strong></summary>

```java
provider.getBalance("PLAYER", playerUUID, 1)
    .thenAccept(balance -> {
        System.out.println("Balance: " + balance);
    });

provider.addBalance("PLAYER", playerUUID, 1, 100)
    .thenAccept(success -> {
        if (success) {
            System.out.println("Balance updated!");
        }
    });
```

</details>

