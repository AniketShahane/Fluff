package app.fluff.integrations.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class CloudException(message: String) : Exception(message)

@Serializable
data class InboxItem(val id: String, val kind: String, val payload: JsonObject, val status: String, val createdAt: String)

@Serializable
data class InboxResult(val id: String, val status: String, val result: String? = null)

@Serializable private data class InboxResponse(val inbox: List<InboxItem>)
@Serializable private data class AckBody(val results: List<InboxResult>)

/** HTTP client for the Fluff MCP server's /sync API (mcp/src/sync.ts). */
class CloudApi(private val http: OkHttpClient, private val json: Json) {
    private val jsonType = "application/json".toMediaType()

    suspend fun status(base: String, token: String) { call(base, token, "/sync/status", null) }

    suspend fun inbox(base: String, token: String): List<InboxItem> =
        json.decodeFromString(InboxResponse.serializer(), call(base, token, "/sync/inbox", null)).inbox

    suspend fun upload(base: String, token: String, snapshot: JsonObject) {
        call(base, token, "/sync/snapshot", json.encodeToString(JsonObject.serializer(), snapshot))
    }

    suspend fun ack(base: String, token: String, results: List<InboxResult>) {
        if (results.isNotEmpty()) call(base, token, "/sync/inbox/ack", json.encodeToString(AckBody.serializer(), AckBody(results)))
    }

    private suspend fun call(base: String, token: String, path: String, body: String?): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(base.trimEnd('/') + path)
            .header("Authorization", "Bearer $token")
            .apply { if (body != null) post(body.toRequestBody(jsonType)) }
            .build()
        try {
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                when {
                    res.isSuccessful -> text
                    res.code == 401 -> throw CloudException("The sync token was not accepted")
                    res.code == 413 -> throw CloudException("Upload too large")
                    else -> throw CloudException("Server error ${res.code}: ${text.take(200)}")
                }
            }
        } catch (e: IOException) {
            throw CloudException("Couldn't reach the server (connection): ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
