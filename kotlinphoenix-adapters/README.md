# Kotlin Phoenix Adapters

Adapters designed for specific GraphQL clients (e.g. Apollo Client) allowing them to handle subscriptions via Phoenix Channels.

Currently available adapters:

- [PhoenixNetworkTransport](https://github.com/ajacquierbret/kotlin-phoenix/blob/main/kotlinphoenix-adapters/src/commonMain/kotlin/io/github/ajacquierbret/kotlinphoenix/adapters/apollo/PhoenixNetworkTransport.kt) for [Apollo Client](https://github.com/apollographql/apollo-kotlin)

## Usage

### Apollo Client

Required parameters:

- `serverUrl`: the url to use to establish the socket connection.
- `topic`: the topic to which the channel will subscribe (e.g. `__absinthe__:control` for Absinthe servers)

Optional parameters:

- `params`: the parameters you whish to pass along the socket connection
- `logger`: a lambda providing logs emitted from the Phoenix Socket
- `reconnectWhen `: a lambda providing a NetworkError and expecting to return a boolean. Returning true will enable automatic reconnection for every NetworkError.
- `reconnectWith `: a lambda expecting to return a `Map<String, Any?>?` that will be passed along the socket reconnection

```kotlin
val SERVER_URL = "ws://localhost:4000/socket/websocket"
val CHANNEL_TOPIC = "topic"

fun buildParams() {
	return getSession()?.token?.let {
        mapOf("token" to it)
    }
}
 
val phoenixNetworkTransport = PhoenixNetworkTransport.Builder()
            .serverUrl(SERVER_URL)
            .topic(CHANNEL_TOPIC)
            .params(buildParams())
            .logger { println(it) }
            .reconnectWhen { cause ->
            	println("Socket will try to reconnect because of: $cause")
            	true
            }
            .reconnectWith { buildParams() }
            .build()

val apolloClient = ApolloClient.Builder()
    .httpServerUrl("http://localhost:4000/api")
    .subscriptionNetworkTransport(phoenixNetworkTransport)
    .build()
```

If you need to reconnect the socket programmatically (e.g. because user session changed), use `PhoenixNetworkTransport.reconnect` function:

```kotlin
userSessionChangeEvents.collect {
    println("Session changed. Will reconnect the socket...")
    phoenixNetworkTransport.reconnect(
        scope = this,
        // By default, all already registered subscriptions will try to subscribe again on reconnection.
        // Disable this behaviour by setting 'queueMessages = false'
        queueMessages = false
    )
}
```

That's it! You should now be able to use GraphQL subscriptions with the Apollo Client.