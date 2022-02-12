# Kotlin Phoenix Adapters

Adapters designed for specific GraphQL clients (e.g. Apollo Client) allowing them to handle subscriptions via Phoenix Channels.

Currently available adapters:

- [PhoenixNetworkTransport](https://github.com/ajacquierbret/kotlin-phoenix/blob/main/kotlinphoenix-adapters/src/commonMain/kotlin/io/github/ajacquierbret/kotlinphoenix/adapters/apollo/PhoenixNetworkTransport.kt) for [Apollo Client](https://github.com/apollographql/apollo-kotlin)

## Usage

### Apollo Client

These parameters are required:

- `serverUrl`: the url to use to establish the socket connection.
- `topic`: the topic to which the channel will subscribe (e.g. `__absinthe__:control` for Absinthe servers)

```kotlin
// ApolloClient.kt

val CHANNEL_TOPIC = "topic"

val apolloClient = ApolloClient.Builder()
    // .httpServerUrl("http://localhost:4000/api")
    .subscriptionNetworkTransport(
        PhoenixNetworkTransport.Builder()
            .serverUrl("ws://localhost:4000/socket/websocket")
            .topic(CHANNEL_TOPIC)
            .params(mapOf("token" to token))
            .build()
    )
    .build()
```

That's it! You should now be able to use GraphQL subscriptions with the Apollo Client.