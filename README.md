# Android Tools MCP

An Android Studio plugin that exposes the built-in Gemini agent tools as an [MCP](https://modelcontextprotocol.io) server — so any AI coding tool can use them.

> [!WARNING]
> This plugin uses **undocumented internal APIs** from Android Studio's Gemini plugin. These APIs can change or break at any time without notice. It is **not affiliated with or endorsed by Google**. Use at your own risk — best suited for experimentation and personal workflows, not production CI pipelines.

> [!NOTE]
> Requires **Android Studio 2025.3 – 2026.1** (build 253–261) with the Gemini plugin enabled (bundled by default) and **Python 3.7+** for the bridge script.

---

## Quick start

**1. Install the plugin**

Download the ZIP from [Releases](https://github.com/amsavarthan/android-tools-mcp/releases) and install via **Settings → Plugins → Install Plugin from Disk** in Android Studio.

**2. Download the bridge script**

Save [`android-studio-mcp.py`](https://github.com/amsavarthan/android-tools-mcp/blob/main/scripts/android-studio-mcp.py) somewhere on your machine.

**3. Connect to your favourite tool** (see [below](#connect-to-your-favourite-tool))

**4. Open an Android project** in Android Studio — the MCP server starts automatically.

---

## Connect to your favourite tool

> [!IMPORTANT]
> Always use **absolute paths** (e.g., `/Users/you/android-studio-mcp.py`), not `~`. Most MCP clients don't expand the tilde.

### Claude Code

```bash
claude mcp add android-studio -- python3 /absolute/path/to/android-studio-mcp.py
```

### GitHub Copilot

Add to `.vscode/mcp.json` (or user-level `settings.json` under `github.copilot.mcp`):
```json
{
  "servers": {
    "android-studio": {
      "type": "stdio",
      "command": "python3",
      "args": ["/absolute/path/to/android-studio-mcp.py"]
    }
  }
}
```

### Kilo Code

Add to `~/.config/kilo/kilo.jsonc`:
```json
{
  "mcp": {
    "android-studio": {
      "type": "local",
      "command": ["python3", "/absolute/path/to/android-studio-mcp.py"],
      "enabled": true
    }
  }
}
```

### OpenCode

Add to `~/.config/opencode/opencode.json`:
```json
{
  "mcp": {
    "android-studio": {
      "type": "local",
      "command": ["python3", "/absolute/path/to/android-studio-mcp.py"],
      "enabled": true
    }
  }
}
```

### SSE-native clients

Any MCP client that supports SSE transport can connect directly — no bridge script needed:
```
http://127.0.0.1:24601/sse
```

---

## Available tools (30)

All tools are Android-specific. Generic file/code and agent-workflow tools are intentionally excluded. Tools are discovered dynamically from the Gemini plugin at runtime — when Android Studio updates with new tools, they appear automatically.

Tools that return images (screenshots, rendered previews) are delivered as MCP image content.

<details>
<summary><b>Device tools (5)</b></summary>

| Tool | Description |
|------|-------------|
| `read_logcat` | Read logcat output from a connected Android device |
| `take_screenshot` | Capture a screenshot from a connected device |
| `ui_state` | Dump the current UI hierarchy from a connected device |
| `adb_shell_input` | Send input events to a connected device via `adb shell input` |
| `adb_pull` | Pull a file off a connected device |
</details>

<details>
<summary><b>Build &amp; Gradle tools (15)</b></summary>

| Tool | Description |
|------|-------------|
| `gradle_sync` | Trigger a Gradle sync in the open project |
| `gradle_build` | Build the project via Gradle |
| `gradle_assemble_all` | Assemble every artifact in the build |
| `deploy` | Build and deploy the app to a connected device |
| `run_test_with_coverage` | Run tests with coverage collection |
| `version_lookup` | Look up the latest stable and preview versions of a Maven artifact |
| `release_notes` | Fetch release notes for a dependency version |
| `get_top_level_sub_projects` | List top-level subprojects in the Gradle build |
| `get_build_file_location` | Get the build file path for a given artifact |
| `get_gradle_artifact_from_file` | Identify which Gradle artifact owns a source file |
| `get_assemble_task_for_artifact` | Get the assemble Gradle task for an artifact |
| `get_test_task_for_artifact` | Get the test Gradle task for an artifact |
| `get_artifact_consumers` | List artifacts that depend on a given artifact |
| `get_test_artifacts_for_sub_project` | List test artifacts for a subproject |
| `get_source_folders_for_artifact` | List source folders for a Gradle artifact |
</details>

<details>
<summary><b>Compose &amp; UI tools (5)</b></summary>

| Tool | Description |
|------|-------------|
| `render_compose_preview` | Render a Compose preview and return the image |
| `compose_visual_comparison_judge` | Judge whether a rendered preview matches a target |
| `verify_batch_visual_parity` | Check visual parity across a batch of previews |
| `compare_images` | Compare two images and report the differences |
| `visual_lint_service_tool` | Report visual lint issues for the current layout |
</details>

<details>
<summary><b>Android resource tools (2)</b></summary>

| Tool | Description |
|------|-------------|
| `locate_android_resources` | Locate the resources referenced by a layout XML file |
| `resolve_resource` | Resolve an Android resource reference to its value |
</details>

<details>
<summary><b>Documentation &amp; search tools (3)</b></summary>

| Tool | Description |
|------|-------------|
| `search_android_docs` | Search the Android developer documentation |
| `fetch_android_docs` | Fetch the content of an Android documentation page |
| `code_search` | Search code within the open project |
</details>

---

## Custom port

By default the server runs on port **24601**. To change it:

1. In Android Studio: **Help → Edit Custom VM Options** → add `-Dmcp.bridge.port=12345`
2. Pass `--port 12345` to the bridge script

---

## Build from source

```bash
git clone https://github.com/amsavarthan/android-tools-mcp
cd android-tools-mcp
./gradlew buildPlugin
```

The plugin ZIP is written to `build/distributions/`. The build compiles against your local Android Studio installation — set the path via `local.properties` (`android.studio.path=...`), env var `ANDROID_STUDIO_PATH`, or it defaults to `/Applications/Android Studio.app`.

Requires a **JDK 21** toolchain (Android Studio's bundled JBR works: `JAVA_HOME="<Android Studio>/Contents/jbr/Contents/Home"`).

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| 0 tools discovered | Open an Android project and wait for Gradle sync to complete. Ensure the Gemini plugin is enabled. |
| Connection refused | The server only runs while a project is open. Verify with `curl -sN http://127.0.0.1:24601/sse`. |
| Device tools fail | Connect an Android device or emulator via ADB. |

---

## License

[Apache 2.0](LICENSE)
