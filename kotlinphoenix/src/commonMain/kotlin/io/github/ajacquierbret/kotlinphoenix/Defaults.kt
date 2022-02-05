/*
 * Copyright (c) 2019 Daniel Rees <daniel.rees18@gmail.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.github.ajacquierbret.kotlinphoenix

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

expect object DefaultsPlatform {
  /**
   * Takes an endpoint and a params closure given by the User and constructs a URL that
   * is ready to be sent to the Socket connection.
   *
   * Will convert "ws://" and "wss://" to http/s which is what OkHttp expects.
   *
   * @throws IllegalArgumentException if [endpoint] is not a valid URL endpoint.
   */
  internal fun buildEndpointUrl(
    endpoint: String,
    paramsClosure: PayloadClosure,
    vsn: String
  ): URL
}

object Defaults {

  /** The default parser configuration to use when parsing messages */
  private val parser: Json = Json

  /** Default timeout of 10s */
  const val TIMEOUT: Long = 10_000

  /** Default heartbeat interval of 30s */
  const val HEARTBEAT: Long = 30_000

  /** Default JSON Serializer Version set to 2.0.0 */
  const val VSN: String = "2.0.0"

  /** Default reconnect algorithm for the socket */
  val reconnectSteppedBackOff: (Int) -> Long = { tries ->
    if (tries > 9) 5_000 else listOf(
      10L, 50L, 100L, 150L, 200L, 250L, 500L, 1_000L, 2_000L
    )[tries - 1]
  }

  /** Default rejoin algorithm for individual channels */
  val rejoinSteppedBackOff: (Int) -> Long = { tries ->
    if (tries > 3) 10_000 else listOf(1_000L, 2_000L, 5_000L)[tries - 1]
  }

  /**
   * Default JSON decoder, backed by kotlinx.serialization, that takes JSON and converts it
   * into a Message object.
   */
  @Suppress("UNCHECKED_CAST")
  val decode: DecodeClosure = { rawMessage ->
    val result = parser.decodeFromString<JsonArray>(rawMessage)
    val resultList = result.mapIndexed { index, item ->
      when (index) {
        4 -> {
          val jsonObject = parser.decodeFromJsonElement<JsonObject>(item)
          jsonObject.toPrimitiveMap()
        }
        else -> parser.decodeFromJsonElement<String?>(item)
      }
    }

    // vsn=2.0.0 message structure
    // [join_ref, ref, topic, event, payload]
    Message(
      joinRef = resultList[0] as? String?,
      ref = resultList[1] as? String ?: "",
      topic = resultList[2] as? String ?: "",
      event = resultList[3] as? String ?: "",
      rawPayload = resultList[4] as? Payload ?: emptyMap()
    )
  }

  /**
   * Default JSON encoder, backed by kotlinx.serialization, that takes a Payload /* JsonObject */ and
   * converts it into a JSON String.
   */
  val encode: EncodeClosure = { payload ->
      parser.encodeToString(payload.toJsonArray())
  }
}

fun List<*>.toJsonArray(): JsonArray {
  val list: MutableList<JsonElement> = mutableListOf()
  this.forEach { value ->
    list.add(value.toJsonElement())
  }
  return JsonArray(list)
}

fun Map<*, *>.toJsonObject(): JsonObject {
  val map: MutableMap<String, JsonElement> = mutableMapOf()
  this.forEach { (key, value) ->
    key as String
    map[key] = value.toJsonElement()
  }
  return JsonObject(map)
}

fun Any?.toJsonElement(): JsonElement = when (this) {
  null -> JsonNull
  is Number -> JsonPrimitive(this)
  is String -> JsonPrimitive(this)
  is Boolean -> JsonPrimitive(this)
  is Map<*, *> -> this.toJsonObject()
  is Iterable<*> -> JsonArray(this.map { it.toJsonElement() })
  is Array<*> -> JsonArray(this.map { it.toJsonElement() })
  is List<*> -> JsonArray(this.map { it.toJsonElement() })
  is Enum<*> -> JsonPrimitive(this.toString())
  else -> {
    try {
      JsonPrimitive(this.toString())
    } catch (error: Throwable) {
      throw IllegalStateException("Can't serialize unknown type: $this")
    }
  }
}

fun JsonObject.toPrimitiveMap(): Map<String, Any?> =
  this.entries.associate {
    it.key to it.value.toPrimitive()
  }

fun JsonElement.toPrimitive(): Any? = when (this) {
  is JsonNull -> null
  is JsonObject -> this.toPrimitiveMap()
  is JsonArray -> this.map { it.toPrimitive() }
  is JsonPrimitive -> {
    if (isString) {
      contentOrNull
    } else {
      booleanOrNull ?: longOrNull ?: doubleOrNull
    }
  }
  else -> null
}