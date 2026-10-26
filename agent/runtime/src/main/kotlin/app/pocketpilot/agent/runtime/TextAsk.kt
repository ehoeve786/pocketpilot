package app.pocketpilot.agent.runtime

import app.pocketpilot.agent.providers.api.Message
import app.pocketpilot.agent.providers.api.ModelEvent
import app.pocketpilot.agent.providers.api.ModelRequest

/** The reply to [TextAsk.ask]: the answer, or the reason there is none, and what it cost. */
data class TextAnswer(
    val text: String?,
    val costUsd: Double?,
    val problem: String? = null,
)

/**
 * One question to the model with no tools: it reads the text it is given and answers, and never sees
 * or touches the phone. For rule steps that only need text worked on, such as pulling an address out
 * of a screen read. A failed model hands over to the owner's next one, as in an Agent run.
 */
object TextAsk {
    internal const val SYSTEM =
        "You answer one request from the owner's phone automation. Work only with the text in the request and " +
            "what you know; you cannot see or use the phone. Your reply is saved word for word and used by the " +
            "next step, so reply with only the requested result: no reasoning, no explanation, no quotes and no markdown."

    suspend fun ask(
        model: RunModel,
        prompt: String,
    ): TextAnswer {
        var next: RunModel? = model
        var cost: Double? = null
        var problem = "No model is set up"
        while (next != null) {
            val current = next
            val text = StringBuilder()
            var error: ModelEvent.Error? = null
            current.provider
                .stream(
                    ModelRequest(
                        model = current.model,
                        system = SYSTEM,
                        messages = listOf(Message.user(prompt)),
                        maxTokens = current.maxOutputTokens,
                    ),
                ).collect { event ->
                    when (event) {
                        is ModelEvent.TextDelta -> {
                            text.append(event.text)
                        }

                        is ModelEvent.Usage -> {
                            current
                                .estimateCost(event.inputTokens.toLong(), event.outputTokens.toLong())
                                ?.let { cost = (cost ?: 0.0) + it }
                        }

                        is ModelEvent.Error -> {
                            error = event
                        }

                        else -> {
                            Unit
                        }
                    }
                }
            val failed = error
            val answer = text.toString().trim()
            if (failed == null && answer.isNotEmpty()) return TextAnswer(answer, cost)
            problem = failed?.let { "${current.label}: ${it.message}" } ?: "${current.label} gave no answer"
            next = current.fallback
        }
        return TextAnswer(null, cost, problem)
    }
}
