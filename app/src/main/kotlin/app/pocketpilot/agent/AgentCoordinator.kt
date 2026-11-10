package app.pocketpilot.agent

import app.pocketpilot.agent.providers.anthropic.AnthropicProvider
import app.pocketpilot.agent.providers.api.ModelProvider
import app.pocketpilot.agent.providers.gemini.GeminiCallMemory
import app.pocketpilot.agent.providers.gemini.GeminiProvider
import app.pocketpilot.agent.providers.openai.OpenAiProvider
import app.pocketpilot.agent.providers.openaicompatible.CompatibleProfile
import app.pocketpilot.agent.providers.openaicompatible.compatibleProvider
import app.pocketpilot.agent.registry.ModelRegistry
import app.pocketpilot.agent.registry.ToolSupport
import app.pocketpilot.agent.registry.estimateCost
import app.pocketpilot.agent.runtime.AgentProfile
import app.pocketpilot.agent.runtime.AgentRunner
import app.pocketpilot.agent.runtime.AgentTask
import app.pocketpilot.agent.runtime.FileRunStore
import app.pocketpilot.agent.runtime.KeyRotatingProvider
import app.pocketpilot.agent.runtime.ProfileStore
import app.pocketpilot.agent.runtime.ProviderKind
import app.pocketpilot.agent.runtime.RunModel
import app.pocketpilot.agent.runtime.RunState
import app.pocketpilot.agent.runtime.TextAnswer
import app.pocketpilot.agent.runtime.TextAsk
import app.pocketpilot.agent.runtime.ToolMode
import app.pocketpilot.core.model.Scope
import app.pocketpilot.feature.agent.ModelList
import app.pocketpilot.feature.agent.ModelOption
import app.pocketpilot.network.api.SecretStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent mode in the app: turns the owner's [AgentProfile]s into providers, starts runs on the
 * process scope so they outlive the screen, and keeps the transcripts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentCoordinator(
    private val http: HttpClient,
    private val registry: ModelRegistry,
    private val runner: AgentRunner,
    val profiles: ProfileStore,
    private val runs: FileRunStore,
    private val secrets: SecretStore,
    private val scope: CoroutineScope,
    /** Shows the live run outside the app, so the owner can take over or stop it from anywhere. */
    private val onRunState: (RunState?) -> Unit = {},
    /** Scopes the owner turned on beyond [AGENT_SCOPES], such as the raw shell. Read when a run starts. */
    private val extraScopes: () -> Set<Scope> = { emptySet() },
) {
    val current: StateFlow<RunState?> =
        runner.current
            .flatMapLatest { it?.state ?: flowOf(null) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    private val mutableHistory = MutableStateFlow<List<RunState>>(emptyList())
    val history: StateFlow<List<RunState>> = mutableHistory.asStateFlow()

    init {
        scope.launch {
            refreshHistory()
            // A run's transcript lands in the history when it ends.
            current.collect {
                onRunState(it)
                if (it != null && !it.open) refreshHistory()
            }
        }
    }

    /**
     * Runs [goal] for an automation with the owner's first usable model, falling back to the others, and
     * waits for it to end. Null when no model is set up or another run is in progress.
     */
    suspend fun runForAutomation(
        goal: String,
        fromOutside: Boolean,
    ): RunState? {
        val chain = profiles.profiles.value.filter(::usable)
        if (chain.isEmpty() || current.value?.active == true) return null
        // A run another app or an outside event started gets no extra scopes: the owner's switches are for the owner's runs.
        val scopes = if (fromOutside) AGENT_SCOPES else AGENT_SCOPES + extraScopes()
        val run =
            try {
                val skipped = mutableListOf<AgentProfile>()
                val model = chainedModel(chain, skipped)
                runner.start(
                    AgentTask(goal, scopes, notes = skippedNotes(skipped)),
                    model,
                    chain.first().budgets().copy(maxCostUsd = chain.mapNotNull { it.budgets().maxCostUsd }.minOrNull()),
                    scope,
                )
            } catch (e: IllegalStateException) {
                return null
            } catch (e: IllegalArgumentException) {
                return null
            }
        return try {
            run.state.first { !it.active }
        } catch (e: CancellationException) {
            // The rule was stopped. The run is on the app's scope, so it would go on working the phone without this.
            run.stop()
            throw e
        }
    }

    /** Asks the owner's models [prompt] as text only, with no tools; null when no model is set up. */
    suspend fun askText(prompt: String): TextAnswer? {
        val chain = profiles.profiles.value.filter(::usable)
        if (chain.isEmpty()) return null
        return try {
            TextAsk.ask(chainedModel(chain, mutableListOf()), prompt)
        } catch (e: IllegalArgumentException) {
            TextAnswer(null, costUsd = null, problem = e.message ?: "The model is not set up right")
        }
    }

    fun pause() = runner.current.value?.pause()

    fun resume() = runner.current.value?.resume()

    fun stop() = runner.current.value?.stop()

    /** Saves [profile]; [newKeys] join its saved keys, or replace them when [clearSaved]. */
    fun saveProfile(
        profile: AgentProfile,
        newKeys: List<String>,
        clearSaved: Boolean,
    ) {
        // A key belongs to the provider and server it was saved for, so a change of either drops it.
        val previous = profiles.profiles.value.firstOrNull { it.id == profile.id }
        val movedAway = previous == null || previous.kind != profile.kind || hostOf(previous.baseUrl) != hostOf(profile.baseUrl)
        val kept = if (clearSaved || movedAway) emptyList() else keys(profile)
        val all = (kept + newKeys).distinct()
        if (all.isEmpty()) secrets.remove(profile.keyName) else secrets.put(profile.keyName, all.joinToString(KEY_SEPARATOR))
        profiles.save(profile)
    }

    fun deleteProfile(id: String) {
        profiles.profiles.value
            .firstOrNull { it.id == id }
            ?.let { secrets.remove(it.keyName) }
        profiles.remove(id)
    }

    fun keyCount(profile: AgentProfile): Int = keys(profile).size

    fun moveUp(id: String) = profiles.moveUp(id)

    private fun hostOf(url: String?): String? = url?.let { runCatching { java.net.URI(it).host }.getOrNull() }

    /**
     * The saved keys for [profile] while it still has the kind and host they were saved for. Once the owner has
     * edited either, the saved keys are not sent anywhere, so listing models cannot leak them to the new host.
     */
    private fun keysFor(profile: AgentProfile): List<String> {
        val saved = profiles.profiles.value.firstOrNull { it.id == profile.id } ?: return emptyList()
        return if (saved.kind == profile.kind && hostOf(saved.baseUrl) == hostOf(profile.baseUrl)) keys(profile) else emptyList()
    }

    /** A profile's API keys, kept as one secret with one key per line. */
    private fun keys(profile: AgentProfile): List<String> =
        secrets
            .get(profile.keyName)
            .orEmpty()
            .split(KEY_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** Lists the models the profile reaches, for the model picker; also checks the key or the server address. */
    fun listModels(
        profile: AgentProfile,
        apiKey: String?,
        onResult: (ModelList) -> Unit,
    ) {
        scope.launch {
            val result =
                try {
                    val models = provider(profile, apiKey ?: keysFor(profile).firstOrNull()).listModels()
                    ModelList.Loaded(models.map { ModelOption(it.id, it.displayName) }.sortedBy { it.label.lowercase() })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ModelList.Failed(problem(e))
                }
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    /** A provider failure in words the owner can act on. */
    private fun problem(e: Exception): String {
        val message = e.message.orEmpty()
        return when {
            e is java.net.UnknownHostException || e is java.net.ConnectException ||
                e is java.net.NoRouteToHostException || e is java.net.SocketTimeoutException -> {
                "the server did not answer. Check the address, and that the server listens on the network."
            }

            "API_KEY_INVALID" in message || message.startsWith("HTTP 401") || message.startsWith("HTTP 403") -> {
                "the API key was not accepted."
            }

            else -> {
                message.take(MAX_PROBLEM_CHARS).ifBlank { e::class.simpleName.orEmpty() }
            }
        }
    }

    /** Needs a key unless it is a local or self-hosted server. */
    private fun usable(profile: AgentProfile): Boolean = profile.kind == ProviderKind.OPENAI_COMPATIBLE || keys(profile).isNotEmpty()

    /**
     * The first profile's model, falling back to the others in order. A fallback that cannot be built is left out and
     * added to [skipped], so the run log can say so.
     */
    private fun chainedModel(
        chain: List<AgentProfile>,
        skipped: MutableList<AgentProfile>,
    ): RunModel {
        var next: RunModel? = null
        for (p in chain.drop(1).asReversed()) {
            next =
                try {
                    runModel(p, next)
                } catch (e: IllegalArgumentException) {
                    skipped += p
                    next
                }
        }
        return try {
            runModel(chain.first(), next)
        } catch (e: IllegalArgumentException) {
            // The first model's settings cannot be used: the run starts on the next one, or fails when there is none.
            skipped += chain.first()
            next ?: throw e
        }
    }

    private fun skippedNotes(skipped: List<AgentProfile>): List<String> =
        skipped.map { "Skipped the fallback ${it.name}: its settings cannot be used. Check its address and key in Agent mode." }

    private fun runModel(
        profile: AgentProfile,
        fallback: RunModel? = null,
    ): RunModel {
        val descriptor = registry.describe(profile.providerId, profile.model)
        return RunModel(
            provider = keyed(profile),
            model = profile.model,
            toolMode = if (descriptor.tools == ToolSupport.NATIVE) ToolMode.NATIVE else ToolMode.JSON_FALLBACK,
            vision = descriptor.vision,
            estimateCost = { input, output -> estimateCost(descriptor, input.toInt(), output.toInt()) },
            maxOutputTokens = (descriptor.maxOutputTokens ?: MAX_OUTPUT_TOKENS).coerceAtMost(MAX_OUTPUT_TOKENS),
            fallback = fallback,
            label = profile.name,
        )
    }

    /** The key-rotating provider of each profile with several keys, with the profile and keys it was built for. */
    private val rotating = ConcurrentHashMap<String, Pair<Any, KeyRotatingProvider>>()

    /**
     * The provider for [profile]. With several keys it is kept from one run to the next while the profile and its
     * keys stay the same, so the keys take turns across runs and a limited or refused key is not tried first each time.
     */
    private fun keyed(profile: AgentProfile): ModelProvider {
        val all = keys(profile)
        if (all.size <= 1) {
            rotating.remove(profile.id)
            return provider(profile, all.firstOrNull())
        }
        val built = profile to all
        rotating[profile.id]?.takeIf { it.first == built }?.let { return it.second }
        return KeyRotatingProvider(all.map { provider(profile, it) }, roundRobin = true).also { rotating[profile.id] = built to it }
    }

    /** One for the coordinator, so every key and fallback provider it builds shares the Gemini call state. */
    private val geminiMemory = GeminiCallMemory()

    private fun provider(
        profile: AgentProfile,
        key: String?,
    ): ModelProvider =
        when (profile.kind) {
            ProviderKind.ANTHROPIC -> {
                AnthropicProvider(http, key.orEmpty())
            }

            ProviderKind.OPENAI -> {
                OpenAiProvider(http, key)
            }

            ProviderKind.GEMINI -> {
                GeminiProvider(http, key.orEmpty(), memory = geminiMemory)
            }

            ProviderKind.OPENAI_COMPATIBLE -> {
                compatibleProvider(
                    http,
                    CompatibleProfile(
                        name = profile.compatibleName(),
                        baseUrl = profile.baseUrl.orEmpty(),
                        apiKey = key?.ifBlank { null },
                        displayName = profile.name,
                        allowCleartext = profile.allowCleartext,
                    ),
                )
            }
        }

    private suspend fun refreshHistory() {
        mutableHistory.value = withContext(Dispatchers.IO) { runs.list() }
    }

    private companion object {
        /** Everything the local token gets: raw shell and Termux stay behind their own toggles. */
        val AGENT_SCOPES = Scope.KNOWN - setOf(Scope.SHELL_EXEC, Scope.TERMUX_RUN)
        const val KEY_SEPARATOR = "\n"
        const val MAX_PROBLEM_CHARS = 200
        const val MAX_OUTPUT_TOKENS = 4096
    }
}
