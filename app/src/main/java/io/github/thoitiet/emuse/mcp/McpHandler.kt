package io.github.thoitiet.emuse.mcp

import io.github.thoitiet.emuse.CommandDispatcher
import io.github.thoitiet.emuse.DeviceCommand
import io.github.thoitiet.emuse.Prefs
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/**
 * On-device MCP endpoint (JSON-RPC 2.0 over Streamable HTTP), served behind
 * the Cloudflare Tunnel. This is the only MCP surface: the old Cloudflare
 * Worker relay was removed.
 */
class McpHandler(
    private val dispatcher: CommandDispatcher,
    private val prefs: Prefs,
    /** JSON describing this device for device_list. */
    private val deviceJson: () -> JSONObject,
) {
    data class HttpResult(
        val status: Int,
        val json: JSONObject?,
        val headers: Map<String, String> = emptyMap(),
    )

    companion object {
        const val PROTOCOL = "2026-07-28"
        private val SUPPORTED = setOf("2026-07-28", "2025-11-25", "2025-06-18")
        private const val MAX_BODY_BYTES = 1024 * 1024

        private fun negotiate(requested: String?): String =
            if (requested != null && SUPPORTED.contains(requested)) requested else PROTOCOL
    }

    suspend fun handleHttp(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): HttpResult {
        if (path == "/health") {
            return HttpResult(
                200,
                JSONObject().put("ok", true).put("mode", "direct"),
            )
        }
        if (path != "/mcp") return HttpResult(404, JSONObject().put("error", "Not Found"))
        if (method != "POST") return HttpResult(405, JSONObject().put("error", "Method not allowed"))
        if (body.size > MAX_BODY_BYTES) {
            return HttpResult(413, JSONObject().put("error", "request_too_large"))
        }
        val msg: JSONObject = try {
            JSONObject(body.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return HttpResult(400, JSONObject().put("error", "invalid_json"))
        }
        val id = if (msg.has("id")) msg.get("id") else null
        if (!validKey(headers)) {
            val err = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id ?: JSONObject.NULL)
                .put("error", JSONObject().put("code", -32000).put("message", "invalid_token"))
            return HttpResult(
                401, err,
                mapOf("WWW-Authenticate" to "Bearer realm=\"mcp\""),
            )
        }
        return dispatchRpc(msg, id)
    }

    private fun validKey(headers: Map<String, String>): Boolean {
        val expected = prefs.apiKey.trim()
        if (expected.isEmpty()) return false
        val presented = headers["emuse_api_key"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: headers["authorization"]?.trim()?.let {
                if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7).trim() else ""
            } ?: ""
        if (presented.isEmpty()) return false
        return MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
    }

    private suspend fun dispatchRpc(msg: JSONObject, id: Any?): HttpResult {
        val method = msg.optString("method", "")
        val params = msg.optJSONObject("params") ?: JSONObject()
        return when (method) {
            "initialize" -> HttpResult(
                200, rpcResult(
                    id, JSONObject()
                        .put("protocolVersion", negotiate(params.optString("protocolVersion", null)))
                        .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                        .put("serverInfo", JSONObject().put("name", "e-muse-mcp").put("version", "1.0.0")),
                )
            )
            "server/discover" -> HttpResult(
                200, rpcResult(
                    id, JSONObject()
                        .put("supportedVersions", org.json.JSONArray(SUPPORTED.toList()))
                        .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                        .put("_meta", JSONObject().put("io.modelcontextprotocol/serverInfo",
                            JSONObject().put("name", "e-muse-mcp").put("version", "1.0.0"))),
                )
            )
            "ping" -> HttpResult(200, rpcResult(id, JSONObject()))
            "notifications/initialized" -> HttpResult(202, null)
            "tools/list" -> {
                val flags = toolFlags()
                val tools = org.json.JSONArray()
                for (t in TOOL_DEFS) {
                    if (t.name == "tool_flags" || flags[t.name] != false) {
                        tools.put(
                            JSONObject()
                                .put("name", t.name)
                                .put("description", t.description)
                                .put("inputSchema", JSONObject(t.inputSchemaJson))
                                .put("annotations", JSONObject()
                                    .put("readOnlyHint", t.readOnlyHint)
                                    .put("destructiveHint", t.destructiveHint)
                                    .put("idempotentHint", t.idempotentHint)
                                    .put("openWorldHint", t.openWorldHint)),
                        )
                    }
                }
                HttpResult(200, rpcResult(id, JSONObject().put("tools", tools)))
            }
            "tools/call" -> handleToolCall(id, params)
            else -> HttpResult(200, rpcError(id, -32601, "Unknown method $method"))
        }
    }

    private suspend fun handleToolCall(id: Any?, params: JSONObject): HttpResult {
        val name = params.optString("name", "")
        val def = TOOL_BY_NAME[name]
            ?: return HttpResult(200, rpcError(id, -32601, "Unknown tool $name"))
        val args = params.optJSONObject("arguments") ?: JSONObject()
        if (name == "tool_flags") {
            return HttpResult(200, toolOk(id, handleToolFlags(args)))
        }
        if (toolFlags()[name] == false) {
            return HttpResult(
                200,
                rpcError(id, -32000, "Tool \"$name\" is disabled. Call tool_flags to see or change the enabled tools."),
            )
        }
        return try {
            if (name == "device_list") {
                return HttpResult(200, toolOk(id, org.json.JSONArray().put(deviceJson())))
            }
            val cmd = def.cmd
                ?: return HttpResult(200, rpcError(id, -32601, "Unknown tool $name"))
            // deviceId is meaningless in direct mode (single device); drop it.
            val cmdArgs = JSONObject(args.toString()).also { it.remove("deviceId") }
            val timeoutMs = cmdArgs.optLong("timeoutMs", 30_000).coerceIn(1_000L, 120_000L)
            val res = dispatcher.executeCommand(
                DeviceCommand(UUID.randomUUID().toString(), cmd, cmdArgs),
                timeoutMs,
            )
            if (!res.ok) {
                return HttpResult(200, toolFailed(id, res.error ?: "device error"))
            }
            if (name == "screen_capture" && res.result is JSONObject) {
                val shot = res.result as JSONObject
                return HttpResult(
                    200, rpcResult(id, JSONObject()
                        .put("content", org.json.JSONArray()
                            .put(JSONObject()
                                .put("type", "image")
                                .put("data", shot.optString("pngBase64", ""))
                                .put("mimeType", "image/png"))
                            .put(JSONObject()
                                .put("type", "text")
                                .put("text", JSONObject()
                                    .put("width", shot.optInt("width"))
                                    .put("height", shot.optInt("height")).toString())))
                        .put("structuredContent", JSONObject()
                            .put("width", shot.optInt("width"))
                            .put("height", shot.optInt("height")))),
                )
            }
            HttpResult(200, toolOk(id, res.result))
        } catch (e: Exception) {
            HttpResult(200, toolFailed(id, e.message ?: "Internal error"))
        }
    }

    private fun handleToolFlags(args: JSONObject): JSONObject {
        val flags = toolFlags().toMutableMap()
        when {
            args.optBoolean("reset", false) -> {
                for (t in TOOL_DEFS) {
                    if (t.name != "tool_flags") flags[t.name] = true
                }
            }
            args.has("set") -> {
                val set = args.optJSONObject("set") ?: JSONObject()
                val keys = set.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = set.opt(k)
                    if (v is Boolean) flags[k] = v
                }
            }
        }
        prefs.toolFlagsJson = JSONObject(flags as Map<*, *>).toString()
        val tools = org.json.JSONArray()
        for (t in TOOL_DEFS) {
            if (t.name == "tool_flags") continue
            tools.put(JSONObject()
                .put("name", t.name)
                .put("enabled", flags[t.name] != false)
                .put("kind", t.kind))
        }
        return JSONObject().put("tools", tools)
    }

    private fun toolFlags(): Map<String, Boolean> {
        val out = mutableMapOf<String, Boolean>()
        try {
            val o = JSONObject(prefs.toolFlagsJson)
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = o.opt(k)
                if (v is Boolean) out[k] = v
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun rpcResult(id: Any?, result: JSONObject): JSONObject {
        val envelope = JSONObject()
            .put("ttlMs", 0)
            .put("cacheScope", "private")
        val keys = result.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            envelope.put(k, result.get(k))
        }
        envelope.put("resultType", "complete")
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("result", envelope)
    }

    private fun rpcError(id: Any?, code: Int, message: String): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))

    private fun toolOk(id: Any?, result: Any?): JSONObject {
        // MCP spec: structuredContent must be an object. Mirror the Worker:
        // wrap arrays/primitives so strict clients don't reject the response.
        val structured = when (result) {
            is JSONObject -> result
            is org.json.JSONArray -> JSONObject().put("value", result)
            is Collection<*> -> JSONObject().put("value", org.json.JSONArray(result))
            is Map<*, *> -> JSONObject(result)
            else -> JSONObject().put("value", result ?: JSONObject.NULL)
        }
        val text = when (result) {
            is JSONObject, is org.json.JSONArray -> result.toString()
            is Collection<*> -> org.json.JSONArray(result).toString()
            is Map<*, *> -> JSONObject(result).toString()
            else -> JSONObject.quote(result?.toString() ?: "null")
        }
        return rpcResult(id, JSONObject()
            .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            .put("structuredContent", structured))
    }

    private fun toolFailed(id: Any?, message: String): JSONObject =
        rpcResult(id, JSONObject()
            .put("isError", true)
            .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", message)))
            .put("structuredContent", JSONObject()
                .put("code", "DEVICE_ERROR")
                .put("message", message)
                .put("retryable", false)))
}
