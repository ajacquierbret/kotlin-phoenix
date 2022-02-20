# Kotlin Phoenix Client

A modern Kotlin Multiplatform Phoenix Channels client, built with coroutines.

This client is a refactored version of [JavaPhoenixClient](https://github.com/dsrees/JavaPhoenixClient) with coroutines and multiplatform support in mind.

**As of now, this project is still experimental and should NOT be used in production.
Please feel free to share feedbacks and issues.**

## Features

- Multiplatform (Android & iOS)
- Built with Coroutines
- Uses OkHttp3 (v4) & NSURLSession

## Usage

```kotlin
val CHANNEL_TOPIC = "topic"

val phxSocket = Socket(url = "ws://localhost:4000/socket", params = mapOf("token" to token), scope = coroutineScope)

// OPTIONAL: Log socket/channel operations
phxSocket.logger = {
    println(it)
}

val connection = try {
	phxSocket.connect()
} catch (error: SocketEvent.FailureEvent) {
	throw error.throwable
}

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
```