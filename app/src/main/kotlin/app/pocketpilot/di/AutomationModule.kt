package app.pocketpilot.di

import android.content.Context
import app.pocketpilot.agent.AgentCoordinator
import app.pocketpilot.approvals.ApprovalCenter
import app.pocketpilot.automation.AndroidAutomationHost
import app.pocketpilot.automation.AutomationNotifier
import app.pocketpilot.automation.AutomationRuntime
import app.pocketpilot.automation.Calendars
import app.pocketpilot.automation.FloatingTexts
import app.pocketpilot.automation.HomeAssistant
import app.pocketpilot.automation.PhoneState
import app.pocketpilot.automation.PlaceAlerts
import app.pocketpilot.automation.PlaceWatcher
import app.pocketpilot.automation.Prompts
import app.pocketpilot.automation.SecretStoreRuleSecrets
import app.pocketpilot.automation.TermuxRunner
import app.pocketpilot.automation.TriggerSources
import app.pocketpilot.capability.api.notifications.NotificationPoster
import app.pocketpilot.capability.api.screen.ScreenCapturer
import app.pocketpilot.capability.api.screen.ScreenReader
import app.pocketpilot.capability.files.SafFileStore
import app.pocketpilot.capability.shizuku.ShizukuConnection
import app.pocketpilot.capability.shizuku.ShizukuShellRunner
import app.pocketpilot.capability.shizuku.ShizukuSystemReader
import app.pocketpilot.core.automation.AutomationCatalogTool
import app.pocketpilot.core.automation.AutomationHistoryTool
import app.pocketpilot.core.automation.AutomationListTool
import app.pocketpilot.core.automation.AutomationProposeTool
import app.pocketpilot.core.automation.AutomationRunTool
import app.pocketpilot.core.automation.AutomationUpdateTool
import app.pocketpilot.core.automation.FilePersistence
import app.pocketpilot.core.automation.RuleStore
import app.pocketpilot.core.imaging.ImagePipeline
import app.pocketpilot.core.model.Flavor
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.core.orchestrator.CallDispatcher
import app.pocketpilot.core.orchestrator.ToolHandler
import app.pocketpilot.core.policy.AppMode
import app.pocketpilot.core.policy.AppPolicy
import app.pocketpilot.core.policy.PolicyEngine
import app.pocketpilot.core.tools.HomeControlSecureTool
import app.pocketpilot.core.tools.HomeControlTool
import app.pocketpilot.core.tools.HomeEntitiesTool
import app.pocketpilot.core.tools.HomeStateTool
import app.pocketpilot.core.tools.NotifyPostTool
import app.pocketpilot.network.api.SecretStore
import app.pocketpilot.network.ssh.SshServers
import app.pocketpilot.security.ShellSwitch
import app.pocketpilot.server.http.McpHttpServer
import app.pocketpilot.ssh.SshAddServerTool
import app.pocketpilot.ssh.SshServersTool
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import java.io.File
import javax.inject.Singleton

/** Automations: the rule store, the engine's phone side, and the automation.* and notify.post tools. */
@Module
@InstallIn(SingletonComponent::class)
object AutomationModule {
    private const val NEIGHBOR_TIMEOUT_MS = 5_000L

    @Provides
    @Singleton
    fun ruleStore(
        @ApplicationContext context: Context,
        secrets: SecretStore,
    ): RuleStore =
        RuleStore(
            FilePersistence(File(context.noBackupFilesDir, "automations.json")),
            secrets = SecretStoreRuleSecrets(secrets),
        )

    @Provides
    @Singleton
    fun automationNotifier(
        @ApplicationContext context: Context,
    ): AutomationNotifier = AutomationNotifier(context)

    @Provides
    fun notificationPoster(notifier: AutomationNotifier): NotificationPoster = notifier

    @Provides
    @Singleton
    fun homeAssistant(
        store: RuleStore,
        secrets: SecretStore,
        http: HttpClient,
        scope: CoroutineScope,
    ): HomeAssistant = HomeAssistant(store, secrets, http, scope)

    /** Termux commands for rules and `termux.run`; Termux's permission is granted through privileged access. */
    @Provides
    @Singleton
    fun termuxRunner(
        @ApplicationContext context: Context,
        shell: ShizukuShellRunner,
    ): TermuxRunner =
        TermuxRunner(context) {
            if (shell.available.value) {
                runCatching { shell.run("pm grant ${context.packageName} ${TermuxRunner.PERMISSION}", NEIGHBOR_TIMEOUT_MS) }
            }
        }

    /** The questions "Ask for text" and "Choose from a list" put to the owner. */
    @Provides
    @Singleton
    fun prompts(
        @ApplicationContext context: Context,
    ): Prompts = Prompts(context)

    /** SSH servers for rules; a server on the phone's hotspot is found by its MAC address through privileged access. */
    @Provides
    @Singleton
    fun sshServers(
        @ApplicationContext context: Context,
        secrets: SecretStore,
        logs: ShizukuSystemReader,
    ): SshServers =
        SshServers(context, secrets) {
            if (!logs.available.value) {
                null
            } else {
                runCatching { logs.read(listOf("ip", "neigh", "show"), "", NEIGHBOR_TIMEOUT_MS) }
                    .getOrNull()
                    ?.takeIf { it.exitCode == 0 }
                    ?.stdout
            }
        }

    @Provides
    @Singleton
    fun phoneState(
        @ApplicationContext context: Context,
        shizuku: ShizukuConnection,
        shell: ShizukuShellRunner,
        server: Lazy<McpHttpServer>,
    ): PhoneState = PhoneState(context, shizuku, shell) { server.get() }

    @Provides
    @Singleton
    fun automationRuntime(
        @ApplicationContext context: Context,
        store: RuleStore,
        dispatcher: CallDispatcher,
        policy: PolicyEngine,
        phone: PhoneState,
        shell: ShizukuShellRunner,
        shellSwitch: ShellSwitch,
        appPolicy: AppPolicy,
        agent: Lazy<AgentCoordinator>,
        profile: PolicyProfile,
        notifier: AutomationNotifier,
        shizuku: ShizukuConnection,
        scope: CoroutineScope,
        files: SafFileStore,
        homeAssistant: HomeAssistant,
        ssh: SshServers,
        prompts: Prompts,
        termux: TermuxRunner,
        logs: ShizukuSystemReader,
        screen: ScreenReader,
        capturer: ScreenCapturer,
        imaging: ImagePipeline,
        approvals: ApprovalCenter,
        placeAlerts: PlaceAlerts,
    ): AutomationRuntime {
        val hidden = { pkg: String -> appPolicy.modeFor(pkg) == AppMode.DENY }
        val calendars = Calendars(context)
        val places = PlaceWatcher(context, placeAlerts)
        // A floating text's tap starts a rule through the runtime, which needs the host first.
        var runtime: AutomationRuntime? = null
        val floatingTexts =
            FloatingTexts(context, scope, { store.data.value.stopwatches }, { id -> runtime?.startRule(id, "Floating text tapped") })
        val host =
            AndroidAutomationHost(
                context = context,
                dispatcher = dispatcher,
                policy = policy,
                phone = phone,
                shell = shell,
                shellOn = { profile.shellExecAvailable && shellSwitch.enabled.value },
                hidden = hidden,
                agent = { agent.get() },
                files = files,
                homeAssistant = homeAssistant,
                ssh = ssh,
                prompts = prompts,
                termux = termux,
                calendars = calendars,
                places = places,
                screen = screen,
                screenCapturer = capturer,
                imagePipeline = imaging,
                confirmer = approvals,
                floatingTexts = floatingTexts,
                // Sideload-only triggers and conditions follow the distribution, not the shell capability.
                sideload = profile.flavor != Flavor.PLAY,
            )
        return AutomationRuntime(
            context,
            store,
            host,
            notifier,
            shell,
            scope,
            calendars,
            homeAssistant,
            ssh,
            profile.shellExecAvailable,
        ) { emit ->
            TriggerSources(
                context,
                phone,
                scope,
                hidden,
                places,
                calendars,
                homeAssistant,
                logs,
                profile.shellExecAvailable,
                shizuku.state,
                emit,
            )
        }.also { runtime = it }
    }

    /**
     * The tools AI apps use to see and suggest automations. A proposal only waits in the app until the
     * owner accepts it; running a rule needs one the owner made for MCP calls.
     */
    @Provides
    @ElementsIntoSet
    fun automationTools(
        store: RuleStore,
        runtime: Lazy<AutomationRuntime>,
        notifier: AutomationNotifier,
        profile: PolicyProfile,
    ): Set<ToolHandler> =
        setOf(
            NotifyPostTool(notifier),
            AutomationCatalogTool(profile.shellExecAvailable),
            AutomationListTool(store),
            AutomationHistoryTool(store),
            AutomationProposeTool(store, profile.shellExecAvailable) { notifier.proposed(it) },
            AutomationUpdateTool(store, profile.shellExecAvailable, notifier::changed) { notifier.proposed(it) },
            AutomationRunTool(store) { runtime.get().engine },
        )

    /** ssh.servers and ssh.add_server, so an AI app can set up a server for rules; adding one is confirmed every time. */
    @Provides
    @ElementsIntoSet
    fun sshTools(servers: SshServers): Set<ToolHandler> = setOf(SshServersTool(servers), SshAddServerTool(servers))

    /** home.*: the owner's Home Assistant for the agent and connected apps; they say how to set it up until it is. */
    @Provides
    @ElementsIntoSet
    fun homeTools(home: HomeAssistant): Set<ToolHandler> =
        setOf(HomeEntitiesTool(home), HomeStateTool(home), HomeControlTool(home), HomeControlSecureTool(home))
}
