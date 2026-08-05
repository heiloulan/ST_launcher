package com.wlav.stlauncher.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BridgeProtocolTest {
    @Test
    fun `valid versioned request is parsed`() {
        val request =
            BridgeProtocol.parse(
                """{"version":1,"id":"request-1","type":"blob.chunk","payload":{"sequence":2}}""",
            )

        assertEquals("request-1", request.id)
        assertEquals("blob.chunk", request.type)
        assertEquals(2, request.int("sequence"))
    }

    @Test
    fun `request accessors apply defaults and typed validation`() {
        val request =
            BridgeProtocol.parse(
                """
                {
                  "version": 1,
                  "id": "request.accessors",
                  "type": "blob.start",
                  "payload": {"name": "backup", "count": "bad", "size": 42}
                }
                """.trimIndent(),
            )

        assertEquals("backup", request.string("name"))
        assertEquals("backup", request.stringOrNull("name"))
        assertNull(request.stringOrNull("missing"))
        assertEquals("fallback", request.string("missing", "fallback"))
        assertEquals(42L, request.longOrNull("size"))
        assertNull(request.longOrNull("missing"))
        assertEquals(
            "MISSING_FIELD",
            assertThrows(BridgeProtocolException::class.java) {
                request.string("missing")
            }.code,
        )
        assertEquals(
            "INVALID_FIELD",
            assertThrows(BridgeProtocolException::class.java) {
                request.int("count")
            }.code,
        )
        assertEquals(
            "INVALID_FIELD",
            assertThrows(BridgeProtocolException::class.java) {
                request.longOrNull("count")
            }.code,
        )
    }

    @Test
    fun `unknown protocol version is rejected`() {
        val error =
            assertThrows(BridgeProtocolException::class.java) {
                BridgeProtocol.parse(
                    """{"version":2,"id":"request-1","type":"logs.open","payload":{}}""",
                )
            }
        assertEquals("UNSUPPORTED_VERSION", error.code)
    }

    @Test
    fun `malformed messages are rejected before dispatch`() {
        val cases =
            listOf(
                "not-json" to "INVALID_JSON",
                "[]" to "INVALID_JSON",
                """{"version":1,"id":"","type":"logs.open","payload":{}}""" to "INVALID_ID",
                """{"version":1,"id":"bad id","type":"logs.open","payload":{}}""" to "INVALID_ID",
                """{"version":1,"id":"ok","type":"Logs","payload":{}}""" to "INVALID_TYPE",
                """{"version":1,"id":"ok","type":"logs.open","payload":[]}""" to "INVALID_PAYLOAD",
                """{"version":{},"id":"ok","type":"logs.open","payload":{}}""" to
                    "UNSUPPORTED_VERSION",
                """{"version":1,"id":{},"type":"logs.open","payload":{}}""" to "INVALID_ID",
            )

        cases.forEach { (source, expectedCode) ->
            val error =
                assertThrows(BridgeProtocolException::class.java) {
                    BridgeProtocol.parse(source)
                }
            assertEquals(expectedCode, error.code)
        }
    }

    @Test
    fun `oversized bridge message is rejected without parsing`() {
        val error =
            assertThrows(BridgeProtocolException::class.java) {
                BridgeProtocol.parse("x".repeat(512 * 1024 + 1))
            }

        assertEquals("MESSAGE_TOO_LARGE", error.code)
    }

    @Test
    fun `success response includes result envelope`() {
        val response =
            Json
                .parseToJsonElement(
                    BridgeProtocol.success(
                        "request-2",
                        buildJsonObject { put("value", 7) },
                    ),
                ).jsonObject

        assertTrue(response["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            7,
            response["result"]!!
                .jsonObject["value"]!!
                .jsonPrimitive.content
                .toInt(),
        )
    }

    @Test
    fun `response uses typed error envelope`() {
        val response =
            Json
                .parseToJsonElement(
                    BridgeProtocol.error("request-1", "DENIED", "No", retryable = false),
                ).jsonObject

        assertEquals(false, response["ok"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(
            "DENIED",
            response["error"]
                ?.jsonObject
                ?.get("code")
                ?.jsonPrimitive
                ?.content,
        )

        val anonymous =
            Json
                .parseToJsonElement(
                    BridgeProtocol.error(
                        id = null,
                        code = "INVALID",
                        message = "x".repeat(700),
                        retryable = true,
                    ),
                ).jsonObject
        assertEquals("null", anonymous["id"].toString())
        assertTrue(
            anonymous["error"]!!
                .jsonObject["retryable"]!!
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertEquals(
            500,
            anonymous["error"]!!
                .jsonObject["message"]!!
                .jsonPrimitive.content.length,
        )
    }
}
