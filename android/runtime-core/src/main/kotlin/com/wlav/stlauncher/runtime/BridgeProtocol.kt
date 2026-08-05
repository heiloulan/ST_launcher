package com.wlav.stlauncher.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class BridgeRequest(
    val id: String,
    val type: String,
    val payload: JsonObject,
) {
    fun string(
        name: String,
        default: String? = null,
    ): String {
        val element =
            payload[name]
                ?: return default
                    ?: throw BridgeProtocolException(
                        "MISSING_FIELD",
                        "Missing payload field: $name",
                    )
        val primitive = element as? JsonPrimitive
        if (primitive?.isString != true) {
            throw BridgeProtocolException("INVALID_FIELD", "Invalid string field: $name")
        }
        return primitive.content
    }

    fun stringOrNull(name: String): String? {
        if (payload[name] == null) return null
        return string(name)
    }

    fun int(name: String): Int =
        (payload[name] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.intOrNull
            ?: throw BridgeProtocolException("INVALID_FIELD", "Invalid integer field: $name")

    fun longOrNull(name: String): Long? {
        val element = payload[name] ?: return null
        return (element as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.longOrNull
            ?: throw BridgeProtocolException("INVALID_FIELD", "Invalid integer field: $name")
    }
}

object BridgeProtocol {
    const val VERSION = 1
    private const val MAXIMUM_MESSAGE_CHARACTERS = 512 * 1024
    private val idPattern = Regex("[a-zA-Z0-9._-]{1,96}")
    private val typePattern = Regex("[a-z][a-z0-9]*(?:\\.[a-z0-9]+){1,4}")

    fun parse(source: String): BridgeRequest {
        if (source.length > MAXIMUM_MESSAGE_CHARACTERS) {
            throw BridgeProtocolException("MESSAGE_TOO_LARGE", "Bridge message is too large")
        }
        val root =
            runCatching { Json.parseToJsonElement(source).jsonObject }
                .getOrElse {
                    throw BridgeProtocolException("INVALID_JSON", "Bridge message is not valid JSON")
                }
        val version =
            (root["version"] as? JsonPrimitive)
                ?.takeUnless(JsonPrimitive::isString)
                ?.intOrNull
        if (version != VERSION) {
            throw BridgeProtocolException(
                "UNSUPPORTED_VERSION",
                "Unsupported bridge protocol version",
            )
        }
        val id =
            (root["id"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.contentOrNull
                .orEmpty()
        if (!idPattern.matches(id)) {
            throw BridgeProtocolException("INVALID_ID", "Invalid bridge request ID")
        }
        val type =
            (root["type"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.contentOrNull
                .orEmpty()
        if (!typePattern.matches(type)) {
            throw BridgeProtocolException("INVALID_TYPE", "Invalid bridge message type")
        }
        val payload =
            root["payload"] as? JsonObject
                ?: throw BridgeProtocolException("INVALID_PAYLOAD", "Bridge payload must be an object")
        return BridgeRequest(id, type, payload)
    }

    fun success(
        id: String,
        result: JsonObject = buildJsonObject {},
    ): String =
        buildJsonObject {
            put("version", VERSION)
            put("id", id)
            put("ok", true)
            put("result", result)
        }.toString()

    fun error(
        id: String?,
        code: String,
        message: String,
        retryable: Boolean = false,
    ): String =
        buildJsonObject {
            put("version", VERSION)
            if (id == null) put("id", kotlinx.serialization.json.JsonNull) else put("id", id)
            put("ok", false)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message.take(500))
                    put("retryable", retryable)
                },
            )
        }.toString()
}

class BridgeProtocolException(
    val code: String,
    override val message: String,
) : IllegalArgumentException(message)
