// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.plugin.api

/**
 * A tool the model may call (dev_plan.md §12.10: `spending_by_category(period)`,
 * `compare_periods`, `anomalies`, ... — the level 1–2 functions of §12.8, exposed so the model
 * never has to invent a number itself). [parametersJsonSchema] is a JSON Schema object, passed
 * through to the provider's own function-calling format by its implementation.
 */
data class AiTool(val name: String, val description: String, val parametersJsonSchema: String)

/** One entry of the conversation sent to [AiProvider.chat]. */
data class AiMessage(val role: Role, val content: String) {
    enum class Role { System, User, Assistant, Tool }
}

/** Either the model's final answer, or one or more tools it wants called before it continues. */
sealed interface AiResponse {
    data class Answer(val text: String) : AiResponse

    data class ToolCalls(val calls: List<ToolCall>) : AiResponse

    data class ToolCall(val id: String, val name: String, val argumentsJson: String)
}

/**
 * Any OpenAI-compatible chat API (dev_plan.md §12.10) — a user's own local server (Ollama,
 * llama.cpp server, vLLM) or a cloud provider by key. The host supplies the key via
 * [PluginHost.secretStore]; an [AiProvider] implementation never reads it from anywhere else, so
 * the privacy preview and request log (§12.10, FR-AI10) stay complete.
 */
interface AiProvider {
    suspend fun chat(host: PluginHost, messages: List<AiMessage>, tools: List<AiTool>): AiResponse
}
