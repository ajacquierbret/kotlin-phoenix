# Kotlin Phoenix

[![Maven Central](https://img.shields.io/maven-central/v/io.github.ajacquierbret/kotlinphoenix.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22io.github.ajacquierbret%22%20AND%20a:%22kotlinphoenix%22)
![Android](https://img.shields.io/badge/platform-android-%23a4c639)
![iOS](https://img.shields.io/badge/platform-ios-%23007AFF)

This client is a refactored version of [JavaPhoenixClient](https://github.com/dsrees/JavaPhoenixClient) with coroutines and multiplatform support in mind.

**As of now, this project is still experimental and should NOT be used in production.
Please feel free to share feedbacks and issues.**

## Features

- Multiplatform (Android & iOS)
- Built with Coroutines
- Uses OkHttp3 (v4) & NSURLSession
- Support for iOS multithreaded coroutines

## Installation

Add the `mavenCentral` repository

```kotlin
// build.gradle.kts

repositories {
	mavenCentral()
}
```

Add the `kotlinphoenix` dependency in `commonMain` source set.

```kotlin
// shared/build.gradle.kts

sourceSets {
	val commonMain by getting {
		dependencies {
			implementation("io.github.ajacquierbret:kotlinphoenix:1.0.2")
		}
	}
}
```

Enable coroutines multithreading (required for iOS)

```kotlin
// gradle.properties

kotlin.native.binary.memoryModel=experimental
```

If some of your dependencies rely on coroutines, please force the dependency resolution to version `1.6.0-native-mt` with :

```kotlin
// shared/build.gradle.kts

implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.0-native-mt") {
    version {
        strictly("1.6.0-native-mt")
    }
}
```

OR :

```kotlin
// shared/build.gradle.kts

configurations {
    all {
        resolutionStrategy {
            force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.0-native-mt")
            force("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.0-native-mt")
        }
    }
}
```

## Usage

```kotlin
val CHANNEL_TOPIC = "topic"

val phxSocket = Socket(url = "ws://localhost:4000/socket", params = mapOf("token" to token), scope = coroutineScope)

val connection = phxSocket.connect() ?: throw Throwable("Error while creating socket")

// Suspends until websocket is connected and return a hot SharedFlow collector

launch {
    connection.collect {
        when (it) {
            is SocketEvent.MessageEvent -> println("Received message from server !")
            is SocketEvent.FailureEvent -> throw it.throwable
            else -> Unit
        }
    }
}

val channel = phxSocket.channel(CHANNEL_TOPIC, connection)

channel.join().collect {
	when (it.status) {
	    "ok" -> {
	    	println("Channel joined !")
	    	pushMessage(channel)
	    }
	    "error" -> throw Throwable("Connection error while joining Phoenix channel : $it")
	    else -> println("Unhandled message status while joining Phoenix channel : ${it.status}")
	}
}

suspend fun pushMessage(channel: Channel) {
val payload: Payload = mapOf("key" to "value")

channel.push(
    	event = "doc",
    	payload = payload
).collect {
	when (it.status) {
           "ok" -> println("Received ok from server !")
           "error" -> throw Throwable("Error while pushing doc to Phoenix channel")
        }
	}
}

// Leave the channel and close websocket connection

channel.leave()
phxSocket.disconnect()

// OPTIONAL: Log socket/channel operations

phxSocket.logger = {
    println(it)
}
```