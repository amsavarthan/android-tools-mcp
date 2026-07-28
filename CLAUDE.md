# android-tools-mcp

IntelliJ plugin that bridges Android Studio's built-in Gemini agent tools to external AI coding tools via the Model Context Protocol (MCP).

## Build

Requires a local Android Studio installation. The build resolves its path from (in order): `local.properties`, env var `ANDROID_STUDIO_PATH`, or `/Applications/Android Studio.app`.

```
./gradlew buildPlugin
```

Output ZIP lands in `build/distributions/`. Install it via Settings > Plugins > Install from Disk.

## Run / Test

There are no automated tests. To verify manually:

1. Install the plugin ZIP in Android Studio and open a project
2. Run `python3 scripts/health-check.py` (or `python3 scripts/health-check.py <port>`) to confirm the SSE server is up
3. Connect via Claude Code (see below) and invoke a tool

## Connect with Claude Code

```
claude mcp add android-studio -- python3 scripts/android-studio-mcp.py
```

The Python wrapper script converts stdio ↔ SSE so CLI-based MCP clients can connect. Works on macOS, Linux, and Windows (Python 3.7+, stdlib only).

## Architecture

### Startup flow

1. `McpBridgeStartupActivity` fires on project open
2. Calls `McpBridgeService.start()` which discovers tools and starts the SSE server
3. A second discovery pass runs after 30 s to catch late-loading tools

### Tool discovery

`McpBridgeService` uses reflection to query the `com.google.aiplugin.agentToolsProvider` extension point. It filters to Android-specific tools across three categories:

- **Device** — `read_logcat`, `take_screenshot`, `ui_state`, `adb_shell_input`, `deploy`
- **Build / Gradle** — `gradle_sync`, `gradle_build`, `get_gradle_artifact_from_file`, `get_assemble_task_for_artifact`, `get_artifact_consumers`, `get_build_file_location`, `get_test_task_for_artifact`, `get_top_level_sub_projects`, `get_test_artifacts_for_sub_project`, `get_source_folders_for_artifact`
- **Docs / Search** — `search_android_docs`, `fetch_android_docs`, `code_search`, `version_lookup`

A special `_refresh_tools` meta-tool allows clients to trigger rediscovery at any time.

The Gemini plugin now carries two parallel agent-tool APIs: the V1 extension point above
(`com.google.aiplugin.agents.*`) and a newer V2 one (`com.google.studiobot.agentsdk.toolsProvider` /
`com.google.studiobot.agentsdk.tools.*`). This bridge uses V1, which is still fully populated
in build 261. The one casualty is `render_compose_preview`, which moved to V2-only — supporting
it would mean driving the V2 `ToolHandler.run(MutableToolCallStep, Trajectory)` API.

### Transport

MCP SDK server (bundled with the Gemini plugin) exposes tools over SSE via Ktor CIO on port **24601** (configurable via `-Dandroid.tools.mcp.port=PORT`). The `scripts/android-studio-mcp.py` Python wrapper translates stdio JSON-RPC to/from the SSE session endpoint.

The route is mounted explicitly at `/sse` — `Application.mcp {}` from the SDK would mount at the
root instead. The SSE stream (GET) and JSON-RPC messages (POST) share that path; the SDK advertises
the message endpoint as a relative `?sessionId=…` URI, which clients resolve against the SSE URL.

### Key files

| File | Purpose |
|------|---------|
| `src/main/kotlin/.../McpBridgeService.kt` | Core service — discovery, MCP server, tool invocation, SSE transport |
| `src/main/kotlin/.../McpBridgeStartupActivity.kt` | Startup hook — triggers service on project open |
| `src/main/resources/META-INF/plugin.xml` | Plugin descriptor — IDs, dependencies, extension registrations |
| `build.gradle.kts` | Build config — IntelliJ platform plugin, Kotlin 2.1.0, JDK 21 |
| `scripts/android-studio-mcp.py` | Cross-platform stdio ↔ SSE bridge (Python 3, stdlib only) |
| `scripts/health-check.py` | Cross-platform diagnostic check for the SSE endpoint |

### Dependencies

All dependencies come from the IntelliJ platform and the bundled Gemini plugin — there are no external Maven dependencies:

- IntelliJ Platform APIs (`com.intellij.openapi.*`)
- MCP Kotlin SDK — protocol types live under `io.modelcontextprotocol.kotlin.sdk.types.*`, the
  server under `io.modelcontextprotocol.kotlin.sdk.server.*` (shaded into the Gemini plugin's
  `aiplugin.jar`; earlier builds put the types directly in `io.modelcontextprotocol.kotlin.sdk.*`)
- Ktor CIO server (`io.ktor.server.*`) from the platform, plus the Ktor SSE plugin from the
  Gemini plugin's `mcp-sdk-libraries.jar`
- kotlinx.coroutines, kotlinx.serialization.json

### Target platform

- **Since build:** 253 (Android Studio Ladybug Feature Drop 2025.3.3)
- **Until build:** 261.* (Android Studio 2026.1.2)

Build 261 requires a **JDK 21 toolchain** — the Gemini plugin ships Java 21 bytecode, which a JDK 17
toolchain cannot read. Bumping `untilBuild` is mandatory when a new Studio ships: an out-of-range
`until-build` makes the IDE refuse to load the plugin outright, with no error beyond the plugin
being absent.

## Commit Message Convention

This project follows [Conventional Commits](https://www.conventionalcommits.org/).

### Format

```
<type>(<optional scope>): <short summary>

<optional body>

<optional footer>
```

### Types

- `feat` — new feature or capability
- `fix` — bug fix
- `refactor` — code restructuring without behavior change
- `docs` — documentation only
- `chore` — build, CI, tooling, dependencies
- `test` — adding or updating tests
- `style` — formatting, whitespace (no logic change)
- `perf` — performance improvement

### Rules

1. Subject line must be imperative mood, lowercase, no period, max 72 chars (e.g., "add session cleanup on disconnect")
2. Scope is optional but encouraged when the change targets a specific module (e.g., `feat(mcp): ...`, `fix(bridge): ...`)
3. Body (if present) should explain **why**, not what — the diff shows what changed
4. Breaking changes must include `BREAKING CHANGE:` in the footer or `!` after the type (e.g., `feat!: ...`)

## Versioning

This project follows [Semantic Versioning](https://semver.org/). The current version is managed in `gradle.properties` under `plugin.version`.

- **Patch** (`x.y.Z`) — bug fixes, no behaviour change
- **Minor** (`x.Y.0`) — new features, backwards compatible
- **Major** (`X.0.0`) — breaking changes (transport, port, dropped tools)

The project is in `0.x.y` (unstable) while it depends on undocumented Gemini plugin APIs. Do not bump to `1.0.0` until the API surface stabilises.

To release, run `./scripts/release.sh <version>`. Never manually edit `plugin.version` and push — always go through the release script so the tag, commit, and GitHub release stay in sync.

## Path hygiene

Never hardcode local machine paths (e.g. `/Users/<username>/...`) in any tracked file. Use relative paths, environment variables, or `local.properties` (which is gitignored) instead.
