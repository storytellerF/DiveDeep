package com.storyteller_f.divedeep

import com.storyteller_f.divedeep.shared.MockTranslationService
import com.storyteller_f.divedeep.shared.TranslationItem
import com.storyteller_f.divedeep.shared.TranslationRequest
import com.storyteller_f.divedeep.shared.TranslationService
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

class OpenAiTranslationService(
    private val configProvider: () -> TranslationConfig,
) : TranslationService {
    private val mockTranslationService = MockTranslationService()

    override fun translate(request: TranslationRequest): List<TranslationItem> {
        if (request.items.isEmpty()) return emptyList()

        val config = configProvider()
        if (config.useMockTranslation) {
            return mockTranslationService.translate(request)
        }

        val content = OpenAiTranslationProtocol.extractContent(requestTranslations(config, request))
        val translatedTexts = OpenAiTranslationProtocol.parseTranslations(content, request.items.size)
        return OpenAiTranslationProtocol.toTranslationItems(request, translatedTexts)
    }

    private fun requestTranslations(config: TranslationConfig, request: TranslationRequest): String {
        val connection = (URL(chatCompletionsUrl(config.apiBaseUrl)).openConnection() as HttpURLConnection)
            .apply {
                requestMethod = "POST"
                connectTimeout = 60_000
                readTimeout = 180_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                config.apiKey.takeIf { it.isNotBlank() }?.let { apiKey ->
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }

        val payload = OpenAiTranslationProtocol.buildChatCompletionPayload(config, request)

        connection.outputStream.use { output ->
            OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
            }
        }

        val responseCode = connection.responseCode
        val body = if (responseCode in 200..299) {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
        } else {
            val errorBody = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText)
                .orEmpty()
            throw TranslationException("Translation API failed with HTTP $responseCode: $errorBody")
        }

        return body
    }

    private fun chatCompletionsUrl(apiBaseUrl: String): String =
        "${apiBaseUrl.trimEnd('/')}/v1/chat/completions"
}

object OpenAiTranslationProtocol {
    fun buildChatCompletionPayload(config: TranslationConfig, request: TranslationRequest): JSONObject =
        JSONObject()
            .put("model", config.model)
            .put("stream", false)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put(
                                "content",
                                "Translate UI text. Return only a JSON array of strings, " +
                                    "one translated string per input item, preserving order.",
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", userPrompt(request)),
                    ),
            )

    fun extractContent(responseBody: String): String =
        JSONObject(responseBody)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")

    fun parseTranslations(content: String, expectedCount: Int): List<String> {
        val normalized = stripCodeFence(content.trim())
        runCatching {
            val array = JSONArray(normalized)
            return List(array.length()) { index -> array.getString(index) }
                .also { requireExpectedCount(it, expectedCount) }
        }

        return normalized
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.replace(Regex("""^\s*(?:[-*]|\d+[.)])\s*"""), "") }
            .toList()
            .also { requireExpectedCount(it, expectedCount) }
    }

    fun toTranslationItems(
        request: TranslationRequest,
        translatedTexts: List<String>,
    ): List<TranslationItem> =
        request.items.mapIndexed { index, node ->
            TranslationItem(
                nodeId = node.id,
                sourceText = node.text,
                translatedText = translatedTexts[index],
                bounds = node.bounds,
            )
        }

    private fun userPrompt(request: TranslationRequest): String {
        val items = JSONArray()
        request.items.forEachIndexed { index, node ->
            items.put(
                JSONObject()
                    .put("index", index)
                    .put("text", node.text),
            )
        }
        return "Target language: ${request.targetLanguage}\nInput JSON:\n$items"
    }

    private fun stripCodeFence(content: String): String {
        if (!content.startsWith("```")) return content
        return content.lines()
            .drop(1)
            .dropLastWhile { it.trim() == "```" }
            .joinToString("\n")
            .trim()
    }

    private fun requireExpectedCount(translatedTexts: List<String>, expectedCount: Int) {
        if (translatedTexts.size != expectedCount) {
            throw TranslationException(
                "Translation response item count ${translatedTexts.size} did not match request item count $expectedCount",
            )
        }
    }
}

class TranslationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
