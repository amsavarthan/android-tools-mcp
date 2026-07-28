package dev.amsavarthan.androidtoolsmcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * Discovers Android Studio's Gemini agent tools via extension points
 * and exposes them as an MCP server over SSE.
 *
 * The Gemini plugin ships two parallel agent-tool APIs and both are queried. Shared metadata
 * (`getName`, `getToolDescription`, `getToolArguments`) is identical between them; only
 * enumeration and invocation differ.
 *
 * V1 — `com.google.aiplugin.agentToolsProvider`, package `com.google.aiplugin.agents`:
 *   ToolsProvider.getToolSets(Project) → List<ToolSet>
 *   ToolSet.getTools(Project) → List<Tool<Args>>
 *   Tool.createToolHandler(ToolContext, Content.FunctionCall) → ToolHandler
 *   ToolHandler.handle() → Response (suspend);  Response.text()
 *
 * V2 — `com.google.studiobot.agentsdk.toolsProvider`, package `com.google.studiobot.agentsdk`:
 *   ToolsProvider.getInstance(Project, ToolSetId) → ToolSet?   (no bulk enumeration)
 *   ToolSet.getTools(Project) → List<Tool<Args>>
 *   Tool.createToolHandler(ToolContext, Map<String, Any?>) → ToolHandler
 *   ToolHandler.run(MutableToolCallStep, Trajectory) (suspend) — writes its result into the
 *   step rather than returning it;  MutableToolCallStep.getResponse().getMessage()
 *
 * All of this is undocumented internal API, recovered by decompilation.
 */
class McpBridgeService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(McpBridgeService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ktorEngine: EmbeddedServer<*, *>? = null
    @Volatile
    private var cachedTools: List<DiscoveredTool> = emptyList()
    private var mcpServer: Server? = null

    fun start() {
        scope.launch {
            try {
                doStart()
            } catch (e: Exception) {
                log.error("MCP bridge failed to start", e)
            }
        }
    }

    override fun dispose() {
        log.info("Shutting down MCP bridge server")
        runCatching { ktorEngine?.stop(gracePeriodMillis = 1_000, timeoutMillis = 3_000) }
        scope.cancel("McpBridgeService disposed")
    }

    // ---- startup -----------------------------------------------------------

    private suspend fun doStart() {
        val port = System.getProperty("android.tools.mcp.port", "24601").toIntOrNull() ?: 24601

        refreshTools()

        val server = buildMcpServer()
        mcpServer = server
        startSseTransport(server, port)
        log.info("MCP bridge server listening on http://localhost:$port/sse (${cachedTools.size} tools)")

        // Safety net: re-discover after 30s in case providers loaded late
        scope.launch {
            delay(30_000)
            val before = cachedTools.size
            refreshTools()
            if (cachedTools.size != before) {
                log.info("Late discovery found ${cachedTools.size - before} new tool(s), rebuilding")
                mcpServer?.let { rebuildTools(it) }
            }
        }
    }

    private fun refreshTools() {
        cachedTools = discoverTools()
        log.info("Discovered ${cachedTools.size} tool(s): ${cachedTools.map { it.name }}")
    }

    // ---- tool discovery ----------------------------------------------------

    private data class ToolArg(val description: String, val typeName: String)

    /** Which of the Gemini plugin's two parallel agent-tool APIs a tool came from. */
    private enum class ToolApi { V1, V2 }

    private data class DiscoveredTool(
        val name: String,
        val description: String,
        val arguments: Map<String, ToolArg>,
        val rawTool: Any,
        val api: ToolApi,
    )

    /**
     * Discovers tools from both agent-tool APIs.
     *
     * V2 (`com.google.studiobot.agentsdk.toolsProvider`) is the current one and carries tools
     * that V1 never got, such as `render_compose_preview`. V1
     * (`com.google.aiplugin.agentToolsProvider`) is legacy — every implementation there is
     * `…V1`-suffixed — but is still fully populated, and is the only API present on older
     * builds. Where both expose the same tool name, V2 wins.
     */
    private fun discoverTools(): List<DiscoveredTool> {
        val v2 = discoverV2Tools()
        val v1 = discoverV1Tools()

        val seen = v2.mapTo(mutableSetOf()) { it.name }
        val all = v2 + v1.filterNot { it.name in seen }

        if (log.isDebugEnabled) {
            log.debug("Available before filtering — V2: ${v2.map { it.name }.sorted()}")
            log.debug("Available before filtering — V1: ${v1.map { it.name }.sorted()}")
        }
        return all.filter { it.name in ANDROID_TOOLS }
    }

    private fun discoverV1Tools(): List<DiscoveredTool> {
        val result = mutableListOf<DiscoveredTool>()
        try {
            val ep = ExtensionPointName.create<Any>("com.google.aiplugin.agentToolsProvider")
            for (provider in ep.extensionList) {
                try {
                    val toolSets = invoke(provider, "getToolSets", project) as? List<*> ?: continue
                    for (toolSet in toolSets) {
                        result += extractToolsFrom(toolSet ?: continue, ToolApi.V1)
                    }
                } catch (e: Exception) {
                    log.warn("Error extracting V1 tools from ${provider.javaClass.name}", e)
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to enumerate V1 tool providers", e)
        }
        return result
    }

    /**
     * V2's `ToolsProvider` dropped `getToolSets(Project)` and only offers
     * `getInstance(Project, ToolSetId)`, so tool sets are found by asking every provider for
     * every value of the `ToolSetId` enum. Providers return null for ids they do not own.
     */
    private fun discoverV2Tools(): List<DiscoveredTool> {
        val result = mutableListOf<DiscoveredTool>()
        try {
            val ep = ExtensionPointName.create<Any>("com.google.studiobot.agentsdk.toolsProvider")
            val providers = ep.extensionList
            if (providers.isEmpty()) return result

            val toolSetIds = Class
                .forName(
                    "com.google.studiobot.datamodel.tools.ToolSetId",
                    true,
                    providers.first().javaClass.classLoader,
                )
                .enumConstants ?: return result

            for (provider in providers) {
                for (id in toolSetIds) {
                    try {
                        val toolSet = invoke(provider, "getInstance", project, id) ?: continue
                        result += extractToolsFrom(toolSet, ToolApi.V2)
                    } catch (e: Exception) {
                        log.debug("V2 provider ${provider.javaClass.name} failed for $id", e)
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to enumerate V2 tool providers", e)
        }
        return result.distinctBy { it.name }
    }

    private fun extractToolsFrom(toolSet: Any, api: ToolApi): List<DiscoveredTool> {
        val tools = invoke(toolSet, "getTools", project) as? List<*> ?: return emptyList()
        return tools.mapNotNull { tool -> tool?.let { extractTool(it, api) } }
    }

    private fun extractTool(tool: Any, api: ToolApi): DiscoveredTool? {
        val name = invoke(tool, "getName") as? String ?: return null

        var desc = ""
        runCatching {
            val annotation = invoke(tool, "getToolDescription")
            if (annotation != null) {
                desc = (invoke(annotation, "summary") as? String)
                    ?: (invoke(annotation, "description") as? String) ?: ""
            }
        }

        val args = mutableMapOf<String, ToolArg>()
        runCatching {
            val toolArgs = invoke(tool, "getToolArguments") as? Map<*, *>
            toolArgs?.forEach { (param, annotation) ->
                val paramName = invoke(param!!, "getName") as? String ?: return@forEach
                val argDesc = invoke(annotation!!, "description") as? String ?: ""
                val typeName = invoke(param, "getType")?.toString() ?: "String"
                args[paramName] = ToolArg(argDesc, typeName)
            }
        }

        return DiscoveredTool(
            name = name,
            description = desc,
            arguments = args,
            rawTool = tool,
            api = api,
        )
    }

    // ---- MCP server --------------------------------------------------------

    private fun buildMcpServer(): Server {
        val server = Server(
            Implementation(name = "android-tools-mcp", version = "0.1.0"),
            ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(
                        listChanged = true
                    )
                )
            ),
        )

        server.addTool(
            name = "_refresh_tools",
            description = "Re-discovers tools from Android Studio. Call this if tools seem missing.",
            inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
        ) { _ ->
            refreshTools()
            rebuildTools(server)
            CallToolResult(content = listOf(TextContent("Refreshed. Now serving ${cachedTools.size} tools: ${cachedTools.map { it.name }}")))
        }

        rebuildTools(server)
        return server
    }

    private fun rebuildTools(server: Server) {
        // Drop tools that vanished since the last pass; addTool overwrites the rest.
        val current = cachedTools.mapTo(mutableSetOf()) { it.name }
        server.removeTools(server.tools.keys.filter { it != "_refresh_tools" && it !in current })

        for (tool in cachedTools) {
            val props = buildJsonObject {
                for ((argName, arg) in tool.arguments) {
                    put(argName, buildJsonObject {
                        val isListType =
                            arg.typeName.contains("List") || arg.typeName.contains("Collection")
                        if (isListType) {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string") })
                        } else {
                            put("type", "string")
                        }
                        if (arg.description.isNotEmpty()) put("description", arg.description)
                    })
                }
            }
            server.addTool(
                name = tool.name,
                description = tool.description,
                inputSchema = ToolSchema(
                    properties = props,
                    required = tool.arguments.keys.toList()
                ),
            ) { request -> handleToolCall(tool, request.arguments ?: JsonObject(emptyMap())) }
        }
    }

    private suspend fun handleToolCall(
        tool: DiscoveredTool,
        arguments: JsonObject
    ): CallToolResult {
        return try {
            val args = toArgsMap(arguments)
            val result = when (tool.api) {
                ToolApi.V1 -> invokeV1Tool(tool, args)
                ToolApi.V2 -> invokeV2Tool(tool, args)
            }
            CallToolResult(content = result.toContent())
        } catch (e: Exception) {
            log.warn("Tool '${tool.name}' failed", e)
            CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
        }
    }

    /** A tool's output: its text plus any images (screenshots, rendered previews) it produced. */
    private class ToolOutput(val text: String, val images: List<Pair<ByteArray, String>>) {
        fun toContent(): List<ContentBlock> = buildList {
            if (text.isNotEmpty() || images.isEmpty()) add(TextContent(text))
            for ((bytes, mimeType) in images) {
                add(
                    ImageContent(
                        data = Base64.getEncoder().encodeToString(bytes),
                        mimeType = mimeType.ifEmpty { "image/png" },
                    )
                )
            }
        }
    }

    private fun toArgsMap(arguments: JsonObject): Map<String, Any> =
        arguments.entries.associate { (k, v) ->
            k to when (v) {
                is JsonPrimitive -> v.content
                is JsonArray -> v.map { elem ->
                    (elem as? JsonPrimitive)?.content ?: elem.toString()
                }

                else -> v.toString()
            }
        }

    /** Reads the `data`/`mimeType` pair out of either API's Blob type. */
    private fun blobToImage(blob: Any): Pair<ByteArray, String>? {
        val data = invoke(blob, "getData") as? ByteArray ?: return null
        // V1's Blob exposes mimeType as an inline value class, so the JVM name is mangled.
        val mime = (invoke(blob, "getMimeType")
            ?: blob.javaClass.methods
                .firstOrNull { it.name.startsWith("getMimeType") && it.parameterCount == 0 }
                ?.also { it.isAccessible = true }
                ?.invoke(blob)) as? String
        return data to (mime ?: "image/png")
    }

    // ---- V1 invocation -----------------------------------------------------

    private suspend fun invokeV1Tool(tool: DiscoveredTool, argsMap: Map<String, Any>): ToolOutput {
        val raw = tool.rawTool

        // Create a minimal InvocationContext via dynamic proxy.
        // Returns safe defaults for all methods based on return type.
        val invCtxClass = Class.forName("com.google.aiplugin.agents.InvocationContext")
        val invocationContext = Proxy.newProxyInstance(
            invCtxClass.classLoader,
            arrayOf(invCtxClass),
        ) { proxy, method, _ ->
            when (method.name) {
                "getProject" -> project
                "getSessionId" -> "mcp-bridge"
                "getAgentTaskId" -> "mcp"
                "isSubAgent" -> false
                "isAgentStopped" -> false
                "stopAgent" -> Unit
                "getChanges", "getImageAttachments" -> emptyList<Any>()
                "getGetFilesWithRecentlyRevertedChanges" -> ({ emptySet<Any>() } as () -> Set<Any>)
                "getTokenUsageReporter" -> ({ _: Long -> } as (Long) -> Unit)
                "toString" -> "McpBridgeInvocationContext"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> (proxy === (if (method.parameterCount > 0) null else null))
                "getPermissions" -> permissionCheckerProxy()
                "getSessionStorage", "getRootSessionStorage" -> proxyInterface("com.google.aiplugin.agents.SessionStorage")
                "getDocumentTracker" -> proxyInterface("com.google.studiobot.agentsdk.io.AgentDocumentTracker")
                "makeSubAgentContext", "withPermissions" -> proxy // return self
                else -> defaultForReturnType(method.returnType)
            }
        }

        // ToolContext(project, invocationContext, userApprovalProvider=null)
        val toolContextClass = Class.forName("com.google.aiplugin.agents.tools.ToolContext")
        val toolContext = toolContextClass.declaredConstructors
            .firstOrNull { it.parameterCount >= 4 } // default constructor
            ?.also { it.isAccessible = true }
            ?.newInstance(
                project,
                invocationContext,
                null,
                0b100,
                null
            ) // mask bit 2 = UserApprovalProvider default
            ?: toolContextClass.declaredConstructors.first()
                .also { it.isAccessible = true }
                .newInstance(project, invocationContext, null)

        // FunctionCall(name, args, tool=null, toolHandler=null, metadata=null, thoughtSignature=null)
        val functionCallClass =
            Class.forName("com.android.tools.idea.studiobot.Content\$FunctionCall")
        val functionCall = functionCallClass.declaredConstructors
            .first { it.parameterCount == 8 } // default constructor
            .also { it.isAccessible = true }
            .newInstance(tool.name, argsMap, null, null, null, null, 0b111100, null)

        // Tool.createToolHandler(ToolContext, FunctionCall) → ToolHandler
        val handler = invoke(raw, "createToolHandler", toolContext, functionCall)
            ?: return ToolOutput("Failed to create handler for ${tool.name}", emptyList())

        // ToolHandler.handle() → Response (suspend)
        val response = withContext(Dispatchers.IO) { callSuspend(handler, "handle") }
            ?: return ToolOutput("Handler returned null", emptyList())

        // Response.text() → String
        val text = invoke(response, "text") as? String
            ?: invoke(response, "getStatus") as? String
            ?: response.toString()
        val image = (invoke(response, "getBlob"))?.let { blobToImage(it) }
        return ToolOutput(text, listOfNotNull(image))
    }

    // ---- V2 invocation -----------------------------------------------------

    /**
     * V2 handlers do not return a result — `ToolHandler.run(MutableToolCallStep, Trajectory)`
     * writes it into the step. Both of those are interfaces, so the step is a recording proxy
     * whose captured `Response` becomes the tool's output.
     */
    private suspend fun invokeV2Tool(tool: DiscoveredTool, argsMap: Map<String, Any>): ToolOutput {
        val cl = tool.rawTool.javaClass.classLoader
        val toolContext = v2ToolContextProxy(cl)

        // Tool.createToolHandler(ToolContext, Map<String, Any?>) → ToolHandler.
        // The default implementation reflectively instantiates the tool's typed Args class, and
        // throws from here if the arguments don't satisfy it.
        val handler = invokeOrThrow(tool.rawTool, "createToolHandler", toolContext, argsMap)
            ?: return ToolOutput("Failed to create handler for ${tool.name}", emptyList())

        val recorder = V2StepRecorder(tool.name, argsMap)
        val step = v2StepProxy(cl, recorder)
        val trajectory = proxyInterface("com.google.studiobot.agentsdk.trajectory.Trajectory")

        withContext(Dispatchers.IO) { callSuspend(handler, "run", step, trajectory) }

        val response = recorder.response
            ?: return ToolOutput("Tool '${tool.name}' produced no response", emptyList())

        val text = invoke(response, "getMessage") as? String ?: ""
        val images = (invoke(response, "getBlobs") as? List<*>)
            .orEmpty()
            .mapNotNull { blob -> blob?.let { blobToImage(it) } }
        return ToolOutput(text, images)
    }

    /** Mutable state backing the [MutableToolCallStep][v2StepProxy] proxy. */
    private class V2StepRecorder(val toolName: String, val rawArgs: Map<String, Any>) {
        val id: String = "mcp-${UUID.randomUUID()}"

        @Volatile
        var response: Any? = null

        @Volatile
        var payload: Any? = null

        @Volatile
        var status: Any? = null

        @Volatile
        var blockedDurationMs: Long = 0
    }

    private fun v2StepProxy(cl: ClassLoader, recorder: V2StepRecorder): Any {
        val stepClass =
            Class.forName("com.google.studiobot.agentsdk.trajectory.MutableToolCallStep", true, cl)
        val stepStatus = enumConstant(cl, "com.google.studiobot.agentsdk.trajectory.StepStatus", "RUNNING")
        val stepType = enumConstant(cl, "com.google.studiobot.agentsdk.trajectory.StepType", "TOOL_CALL")
        recorder.status = stepStatus

        return Proxy.newProxyInstance(cl, arrayOf(stepClass)) { proxy, method, args ->
            when (method.name) {
                "getId" -> recorder.id
                "getType" -> stepType
                "getToolName" -> recorder.toolName
                "getRawArgs" -> recorder.rawArgs
                "getArgs" -> recorder.rawArgs.mapValues { (_, v) -> v.toString() }
                "getStatus" -> recorder.status
                "setStatus" -> { recorder.status = args?.firstOrNull(); Unit }
                "getResponse" -> recorder.response
                "setResponse" -> { recorder.response = args?.firstOrNull(); Unit }
                "getPayload" -> recorder.payload
                // Two overloads: one takes a payload, the other a mutating lambda.
                "updatePayload" -> {
                    val arg = args?.firstOrNull()
                    if (arg is Function1<*, *>) {
                        recorder.payload?.let { current ->
                            @Suppress("UNCHECKED_CAST")
                            runCatching { (arg as Function1<Any, Any?>).invoke(current) }
                        }
                    } else {
                        recorder.payload = arg
                    }
                    Unit
                }

                "getBlockedDurationMs" -> recorder.blockedDurationMs
                "setBlockedDurationMs" -> {
                    recorder.blockedDurationMs = args?.firstOrNull() as? Long ?: 0L
                    Unit
                }

                "getOriginalCallInfo" -> null
                "isFinalState" -> false
                "toString" -> "McpBridgeToolCallStep(${recorder.toolName})"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> defaultForReturnType(method.returnType)
            }
        }
    }

    private fun v2ToolContextProxy(cl: ClassLoader): Any {
        val ctxClass = Class.forName("com.google.studiobot.agentsdk.tools.ToolContext", true, cl)
        return Proxy.newProxyInstance(cl, arrayOf(ctxClass)) { proxy, method, args ->
            when (method.name) {
                "getProject" -> project
                "getConversationId" -> "mcp-bridge"
                "getScope" -> scope
                "getPermissionChecker" -> permissionCheckerProxy()
                "getSessionStorage", "getRootSessionStorage" ->
                    proxyInterface("com.google.studiobot.agentsdk.tools.SessionStorage")

                "getDocumentTracker" ->
                    proxyInterface("com.google.studiobot.agentsdk.io.AgentDocumentTracker")

                // Suspend functions: returning a value directly (rather than COROUTINE_SUSPENDED)
                // is the "completed without suspending" protocol.
                "isPermissionApproved" -> true
                "requestUserInput" -> null
                "queryModel" -> ""
                "stopAgent" -> Unit
                "toString" -> "McpBridgeToolContext"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> defaultForReturnType(method.returnType)
            }
        }
    }

    private fun enumConstant(cl: ClassLoader, className: String, name: String): Any? =
        runCatching {
            Class.forName(className, true, cl).enumConstants
                ?.firstOrNull { (it as Enum<*>).name == name }
        }.getOrNull()

    // ---- proxy helpers -----------------------------------------------------

    /** Classloader that can find Gemini plugin classes. Resolved lazily from a discovered tool. */
    private val geminiClassLoader: ClassLoader by lazy {
        cachedTools.firstOrNull()?.rawTool?.javaClass?.classLoader ?: javaClass.classLoader
    }

    /** Create a real PermissionChecker that always approves (never blocks). */
    private fun permissionCheckerProxy(): Any? {
        return runCatching {
            val cl = geminiClassLoader

            // Get PermissionDecision.APPROVED
            val decisionClass = Class.forName(
                "com.google.studiobot.agentsdk.permissions.PermissionDecision",
                true,
                cl
            )
            val approved =
                decisionClass.getDeclaredField("APPROVED").also { it.isAccessible = true }.get(null)

            // Create PermissionPolicy proxy that always returns APPROVED
            val policyClass = Class.forName(
                "com.google.studiobot.agentsdk.permissions.PermissionPolicy",
                true,
                cl
            )
            val policy = Proxy.newProxyInstance(cl, arrayOf(policyClass)) { _, method, _ ->
                when {
                    method.returnType.name.contains("PermissionDecision") -> approved
                    else -> null
                }
            }

            // PermissionSettings(idToState, parent, policy, project) — use default constructor
            val settingsClass = Class.forName(
                "com.google.studiobot.agentsdk.permissions.PermissionSettings",
                true,
                cl
            )
            val settings = settingsClass.declaredConstructors
                .first { it.parameterCount >= 5 } // default constructor with mask
                .also { it.isAccessible = true }
                .newInstance(emptyMap<String, Any>(), null, policy, project, 0b0000, null)

            // PermissionChecker(project, settings)
            val checkerClass = Class.forName(
                "com.google.studiobot.agentsdk.permissions.PermissionChecker",
                true,
                cl
            )
            checkerClass.declaredConstructors
                .first { it.parameterCount == 2 }
                .also { it.isAccessible = true }
                .newInstance(project, settings)
        }.getOrElse { e ->
            log.warn("Failed to create PermissionChecker", e)
            null
        }
    }

    /** Create a dynamic proxy for an interface that returns safe defaults for all methods. */
    private fun proxyInterface(className: String): Any? {
        return runCatching {
            val cls = Class.forName(className, true, geminiClassLoader)
            Proxy.newProxyInstance(cls.classLoader, arrayOf(cls)) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "McpBridgeProxy($className)"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> defaultForReturnType(method.returnType)
                }
            }
        }.getOrNull()
    }

    private fun defaultForReturnType(type: Class<*>): Any? = when {
        type == Boolean::class.javaPrimitiveType || type == Boolean::class.javaObjectType -> false
        type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType -> 0
        type == Long::class.javaPrimitiveType || type == Long::class.javaObjectType -> 0L
        type == Float::class.javaPrimitiveType -> 0f
        type == Double::class.javaPrimitiveType -> 0.0
        type == Void.TYPE -> null
        type == String::class.java -> ""
        // Enums are classes, so they would otherwise fall through to null and NPE inside the SDK.
        type.isEnum -> type.enumConstants?.firstOrNull()
        List::class.java.isAssignableFrom(type) -> emptyList<Any>()
        Set::class.java.isAssignableFrom(type) -> emptySet<Any>()
        Map::class.java.isAssignableFrom(type) -> emptyMap<Any, Any>()
        type.isInterface -> runCatching {
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { p, m, a ->
                when (m.name) {
                    "toString" -> "McpProxy(${type.simpleName})"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === a?.firstOrNull()
                    else -> defaultForReturnType(m.returnType)
                }
            }
        }.getOrNull()

        else -> null
    }

    // ---- reflection helpers ------------------------------------------------

    private fun invoke(obj: Any, methodName: String, vararg args: Any?): Any? =
        runCatching { invokeOrThrow(obj, methodName, *args) }.getOrNull()

    /**
     * Like [invoke], but propagates whatever the target method threw. Tools reject bad arguments
     * by throwing out of `createToolHandler`, and that message is the only useful diagnostic the
     * caller gets.
     */
    private fun invokeOrThrow(obj: Any, methodName: String, vararg args: Any?): Any? {
        val allMethods = mutableListOf<Method>()
        allMethods += obj.javaClass.methods
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            allMethods += cls.declaredMethods
            cls = cls.superclass
        }
        for (method in allMethods) {
            if (method.name != methodName) continue
            val params =
                method.parameterTypes.filter { it.name != "kotlin.coroutines.Continuation" }
            if (params.size != args.size) continue
            val compatible = params.zip(args).all { (type, arg) ->
                arg == null || type.isAssignableFrom(arg.javaClass) || type.isPrimitive
            }
            if (!compatible) continue
            method.isAccessible = true
            try {
                return method.invoke(obj, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException as? Exception ?: e
            }
        }
        throw NoSuchMethodException("${obj.javaClass.name}.$methodName taking ${args.size} arg(s)")
    }

    private suspend fun callSuspend(obj: Any, methodName: String, vararg args: Any?): Any? {
        val method = obj.javaClass.methods.firstOrNull {
            it.name == methodName &&
                it.parameterCount == args.size + 1 &&
                it.parameterTypes.last().name == "kotlin.coroutines.Continuation"
        } ?: return invoke(obj, methodName, *args)

        method.isAccessible = true
        return suspendCancellableCoroutine<Any?> { cont ->
            try {
                val result = method.invoke(obj, *args, cont)
                if (result !== COROUTINE_SUSPENDED) {
                    cont.resumeWith(Result.success(result))
                }
            } catch (e: Exception) {
                cont.resumeWith(Result.failure(e))
            }
        }
    }

    // ---- SSE transport -----------------------------------------------------

    /**
     * Mounts the MCP server under /sse.
     *
     * `Application.mcp {}` would mount at the root instead, so the route is declared
     * explicitly to keep the documented http://localhost:PORT/sse endpoint stable.
     * Both the SSE stream (GET) and the JSON-RPC messages (POST) live on that path —
     * the SDK advertises the message endpoint as a relative "?sessionId=..." URI that
     * clients resolve against the SSE URL.
     */
    private suspend fun startSseTransport(mcpServer: Server, port: Int) {
        val engine = embeddedServer(CIO, port = port) {
            install(SSE)
            routing {
                route(SSE_PATH) {
                    mcp { mcpServer }
                }
            }
        }
        ktorEngine = engine
        withContext(Dispatchers.IO) { engine.start(wait = false) }
    }

    companion object {
        private const val SSE_PATH = "/sse"

        fun getInstance(project: Project): McpBridgeService =
            project.getService(McpBridgeService::class.java)

        /**
         * Android-specific tools — the ones that can't be replicated outside the IDE.
         * Generic file/code/agent-workflow tools are deliberately excluded even though
         * discovery sees them.
         *
         * Names marked V2 are only reachable through the newer
         * `com.google.studiobot.agentsdk.toolsProvider` extension point; `ui_state` is the
         * reverse, existing only on V1. Everything unmarked is on both.
         */
        private val ANDROID_TOOLS = setOf(
            // Device
            "read_logcat", "take_screenshot", "adb_shell_input",
            "ui_state",           // V1 only
            "adb_pull",           // V2 only
            // Build & Gradle
            "gradle_sync", "gradle_build", "deploy", "version_lookup",
            "get_gradle_artifact_from_file", "get_assemble_task_for_artifact",
            "get_artifact_consumers", "get_build_file_location",
            "get_test_task_for_artifact", "get_top_level_sub_projects",
            "get_test_artifacts_for_sub_project", "get_source_folders_for_artifact",
            "gradle_assemble_all",     // V2 only
            "run_test_with_coverage",  // V2 only
            "release_notes",           // V2 only
            // Compose & UI — all V2 only
            "render_compose_preview", "compose_visual_comparison_judge",
            "verify_batch_visual_parity", "compare_images", "visual_lint_service_tool",
            // Android resources — V2 only
            "locate_android_resources", "resolve_resource",
            // Android docs & code search
            "code_search", "search_android_docs", "fetch_android_docs",
        )
    }
}
