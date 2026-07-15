# AI Java

Archives containing JAR files are available as [releases](https://github.com/intisy-ai/ai-java/releases).

## What is ai-java?

Server-side AI library for Java — providers, accounts/quota, and a routing proxy, ported from the intisy TypeScript AI stack.

## Usage in private projects

 * Maven (inside the  file)
```xml
  <repository>
      <id>github</id>
      <url>https://maven.pkg.github.com/intisy-ai/ai-java</url>
      <snapshots><enabled>true</enabled></snapshots>
  </repository>
  <dependency>
      <groupId>io.github.intisy</groupId>
      <artifactId>ai-java</artifactId>
      <version>1.0.7</version>
  </dependency>
```

 * Maven (inside the  file)
```xml
  <servers>
      <server>
          <id>github</id>
          <username>your-username</username>
          <password>your-access-token</password>
      </server>
  </servers>
```

 * Gradle (inside the  or  file)
```groovy
  repositories {
      maven {
          url "https://maven.pkg.github.com/intisy-ai/ai-java"
          credentials {
              username = "<your-username>"
              password = "<your-access-token>"
          }
      }
  }
  dependencies {
      implementation 'io.github.intisy:ai-java:1.0.7'
  }
```

## Usage in public projects

 * Gradle (inside the  or  file)
```groovy
  plugins {
      id "io.github.intisy.github-gradle" version "1.3.7"
  }
  dependencies {
      githubImplementation "intisy:ai-java:1.0.7"
  }
```


Once you have it installed you can use it like so:

```
AccountManager mgr = new AccountManager("claude-code", opts);
Acquired a = mgr.acquire("messages");
```

## License

[![Apache License 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
