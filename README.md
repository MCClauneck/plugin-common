# MCEconomy Common

Shared library for the MCEconomy ecosystem (group `io.github.mcclauneck`, artifact `mceconomy-common`).

## Dependency coordinates

Version: `2026.0.4-2`

### Maven

Add GitHub Packages (with credentials) and dependency:

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/MCClauneck/plugin-common</url>
    <releases>
      <enabled>true</enabled>
    </releases>
    <snapshots>
      <enabled>true</enabled>
    </snapshots>
  </repository>
</repositories>

<servers>
  <server>
    <id>github</id>
    <username>${env.GITHUB_ACTOR}</username>
    <password>${env.GITHUB_TOKEN}</password>
  </server>
</servers>

<dependencies>
  <dependency>
    <groupId>io.github.mcclauneck</groupId>
    <artifactId>mceconomy-common</artifactId>
    <version>2026.0.4-2</version>
  </dependency>
</dependencies>
```

### Gradle (Groovy DSL)

```groovy
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/MCClauneck/plugin-common")
        credentials {
            username = System.getenv('GITHUB_ACTOR') ?: System.getenv('AGENT_NAME') ?: System.getenv('USER_GITHUB_NAME')
            password = System.getenv('GITHUB_TOKEN') ?: System.getenv('AGENT_TOKEN') ?: System.getenv('USER_GITHUB_TOKEN')
        }
    }
}

dependencies {
    implementation "io.github.mcclauneck:mceconomy-common:2026.0.4-2"
}
```

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/MCClauneck/plugin-common")
        credentials {
            username = System.getenv("GITHUB_ACTOR") ?: System.getenv("AGENT_NAME") ?: System.getenv("USER_GITHUB_NAME")
            password = System.getenv("GITHUB_TOKEN") ?: System.getenv("AGENT_TOKEN") ?: System.getenv("USER_GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("io.github.mcclauneck:mceconomy-common:2026.0.4-2")
}
```

## Authentication

Artifacts are served via GitHub Packages. Even though this repository is public, consumers must provide a GitHub username and a token with at least the `read:packages` scope (e.g., via `GITHUB_ACTOR`/`AGENT_NAME`/`USER_GITHUB_NAME` and `GITHUB_TOKEN`/`AGENT_TOKEN`/`USER_GITHUB_TOKEN` environment variables) when resolving dependencies.
