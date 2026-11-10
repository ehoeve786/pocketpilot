# PocketPilot

PocketPilot turns an Android phone into an MCP server that any AI client can drive: Claude, ChatGPT, Gemini, DeepSeek, Qwen, Ollama or a local model. It needs no root and no PC after setup, and works through Shizuku, Accessibility and MediaProjection.

**Status:** beta in development. Shizuku and Accessibility control, remote access (Tailscale and WireGuard), the MCP server, Agent mode and the automation engine are built. Remaining work is tracked in the technical specification.

## Build

Requirements: JDK 17 or newer, the Android SDK (API 36) with the NDK, and Go with `gomobile` and `gobind` on the path (the `network/gonet` module builds the Tailscale and WireGuard bindings with them).

```sh
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # release APK (signed when the release key is set)
./gradlew checkModuleGraph        # module dependency rules
./gradlew ktlintCheck test        # style and unit tests
```

## Application ID

The app has no product flavors. It is the open-source distribution, `app.pocketpilot.oss`; debug builds add `.debug`, so they install next to release builds. The pro and play flavors were removed.

## Layout

| Path | What lives there |
|---|---|
| `app/` | The Android app: activity, DI graph, automation host |
| `core/` | Domain and engine: `model` and `common` (pure Kotlin), `orchestrator` (routes MCP calls), `capabilities`, `policy`, `tools`, `automation` (rules engine), `audit`, `imaging`, `designsystem` |
| `capability/` | Phone access: `shizuku`, `accessibility`, `screencapture`, `files`, `notifications`, `apps`, `device`, `api` |
| `server/` | MCP over HTTP (`mcp`, `http`) and OAuth for remote clients (`oauth`) |
| `network/` | Remote access: `tailscale` (embedded, via `go/`), `wireguard`, `public-access`, `certificates`, `api` |
| `agent/` | Agent mode: model `providers-api`, provider adapters (`anthropic`, `gemini`, `openai`, `openai-compatible`), `runtime`, `model-registry` |
| `feature/` | Screens: `agent`, `automations`, `approvals`, `clients`, `network`, `onboarding` |
| `build-logic/` | Gradle convention plugins and the module graph guard (`checkModuleGraph`) |

Each Gradle path mirrors its folder, so `core/policy` is `:core:policy`. Pure Kotlin modules run their tests with `./gradlew :core:policy:test`; Android modules are tested and built in CI.

## License

Apache-2.0.
