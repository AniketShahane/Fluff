package app.fluff.integrations.claude

import app.fluff.core.ai.AiCoachContract
import app.fluff.core.ai.AiPlan
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Duration

sealed interface CoachResult<out T> {
    data class Ok<T>(val value: T) : CoachResult<T>
    data class Failed(val reason: String, val retryable: Boolean) : CoachResult<Nothing>
}

/**
 * Talks to Claude through the official Anthropic Java SDK. Plans come back as JSON constrained by
 * a schema (structured outputs) and are then validated on-device by [app.fluff.core.ai.PlanValidator].
 */
class ClaudeCoach(private val apiKeyProvider: suspend () -> String?) {

    @Volatile private var cached: Pair<String, AnthropicClient>? = null

    private fun client(key: String): AnthropicClient {
        cached?.let { (k, c) -> if (k == key) return c }
        val c = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .timeout(Duration.ofSeconds(120))
            .maxRetries(2)
            .build()
        cached = key to c
        return c
    }

    suspend fun isConfigured(): Boolean = !apiKeyProvider().isNullOrBlank()

    suspend fun designPlan(contextJson: String, allowedIds: List<String>): CoachResult<AiPlan> {
        val key = apiKeyProvider()?.takeIf { it.isNotBlank() } ?: return CoachResult.Failed("Add your Claude API key to enable the AI coach", false)
        val schema = AiCoachContract.outputSchema(allowedIds)
        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16_000L)
            .systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder()
                        .text(AiCoachContract.SYSTEM_PROMPT)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build(),
                ),
            )
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.HIGH)
                    .format(JsonOutputFormat.builder().schema(toSchema(schema)).build())
                    .build(),
            )
            // If a safety classifier declines, let the API retry on its default fallback model.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .addUserMessage("Design today's session. Context:\n$contextJson")
            .build()
        return call(key, params) { msg ->
            val text = msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
            CoachResult.Ok(AiCoachContract.json.decodeFromString(AiPlan.serializer(), text))
        }
    }

    /** A short, specific post-workout debrief. */
    suspend fun debrief(summaryJson: String): CoachResult<String> {
        val key = apiKeyProvider()?.takeIf { it.isNotBlank() } ?: return CoachResult.Failed("No API key", false)
        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(2_000L)
            .system(
                "You are Fluff's strength coach. Write a 2-3 sentence debrief of the session the athlete just finished: " +
                    "name one specific thing that went well using the numbers provided, and one concrete focus for next time. " +
                    "Plain text, no lists, no emojis, under 70 words.",
            )
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .addUserMessage(summaryJson)
            .build()
        return call(key, params) { msg ->
            CoachResult.Ok(msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim())
        }
    }

    private suspend fun <T> call(key: String, params: MessageCreateParams, onMessage: (Message) -> CoachResult<T>): CoachResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val msg = client(key).messages().create(params)
                when (msg.stopReason().orElse(null)) {
                    StopReason.REFUSAL -> CoachResult.Failed("The coach declined this request", false)
                    StopReason.MAX_TOKENS -> CoachResult.Failed("The coach's answer was cut off", true)
                    else -> onMessage(msg)
                }
            } catch (e: UnauthorizedException) {
                CoachResult.Failed("Your Claude API key was rejected", false)
            } catch (e: PermissionDeniedException) {
                CoachResult.Failed("Your Claude API key can't use this model", false)
            } catch (e: RateLimitException) {
                CoachResult.Failed("The coach is rate limited right now", true)
            } catch (e: BadRequestException) {
                CoachResult.Failed("The coach couldn't read the request", false)
            } catch (e: AnthropicServiceException) {
                CoachResult.Failed("The coach is unavailable (${e.statusCode()})", true)
            } catch (e: AnthropicIoException) {
                CoachResult.Failed("No connection to the coach", true)
            } catch (e: kotlinx.serialization.SerializationException) {
                CoachResult.Failed("The coach's answer wasn't valid", true)
            } catch (e: IllegalArgumentException) {
                CoachResult.Failed("The coach's answer wasn't valid", true)
            }
        }

    companion object {
        const val MODEL = "claude-opus-5-5"

        /** Converts a kotlinx JSON schema into the SDK's schema type. */
        fun toSchema(obj: JsonObject): JsonOutputFormat.Schema {
            val b = JsonOutputFormat.Schema.builder()
            obj.forEach { (k, v) -> b.putAdditionalProperty(k, JsonValue.from(toPlain(v))) }
            return b.build()
        }

        internal fun toPlain(e: JsonElement): Any? = when (e) {
            is JsonNull -> null
            is JsonObject -> e.mapValues { toPlain(it.value) }
            is JsonArray -> e.map { toPlain(it) }
            is JsonPrimitive -> when {
                e.isString -> e.content
                e.booleanOrNull != null -> e.booleanOrNull
                e.longOrNull != null -> e.longOrNull
                else -> e.doubleOrNull
            }
        }
    }
}
