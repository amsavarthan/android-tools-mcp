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
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * Discovers Android Studio's Gemini agent tools via extension points
 * and exposes them as an MCP server over SSE.
 *
 * API chain (from javap decompilation):
 *   ToolsProvider.getToolSets(Project) → List<ToolSet>
 *   ToolSet.getTools(Project) → List<Tool<Args>>
 *   Tool.getName(), Tool.getToolDescription(), Tool.getToolArguments()
 *   Tool.createToolHandler(ToolContext, FunctionCall) → ToolHandler
 *   ToolHandler.handle() → Response (suspend)
 *   Response.text() → String
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

    private data class DiscoveredTool(
        val name: String,
        val description: String,
        val arguments: Map<String, ToolArg>,
        val rawTool: Any,
    )

    private fun discoverTools(): List<DiscoveredTool> {
        val result = mutableListOf<DiscoveredTool>()
        try {
            val ep = ExtensionPointName.create<Any>("com.google.aiplugin.agentToolsProvider")
            for (provider in ep.extensionList) {
                result += extractFromProvider(provider)
            }
        } catch (e: Exception) {
            log.warn("Failed to enumerate tool providers", e)
        }
        return result.filter { it.name in ANDROID_TOOLS }
    }

    private fun extractFromProvider(provider: Any): List<DiscoveredTool> {
        val result = mutableListOf<DiscoveredTool>()
        try {
            val toolSets = invoke(provider, "getToolSets", project) as? List<*> ?: return result
            for (toolSet in toolSets) {
                if (toolSet == null) continue
                val tools = invoke(toolSet, "getTools", project) as? List<*> ?: continue
                for (tool in tools) {
                    if (tool == null) continue
                    extractTool(tool)?.let { result += it }
                }
            }
        } catch (e: Exception) {
            log.warn("Error extracting tools from ${provider.javaClass.name}", e)
        }
        return result
    }

    private fun extractTool(tool: Any): DiscoveredTool? {
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

        return DiscoveredTool(name = name, description = desc, arguments = args, rawTool = tool)
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
            CallToolResult(content = listOf(TextContent(invokeTool(tool, arguments))))
        } catch (e: Exception) {
            log.warn("Tool '${tool.name}' failed", e)
            CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
        }
    }

    private suspend fun invokeTool(tool: DiscoveredTool, arguments: JsonObject): String {
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
        val argsMap: Map<String, Any> = arguments.entries.associate { (k, v) ->
            k to when (v) {
                is JsonPrimitive -> v.content
                is JsonArray -> v.map { elem ->
                    (elem as? JsonPrimitive)?.content ?: elem.toString()
                }

                else -> v.toString()
            }
        }
        val functionCall = functionCallClass.declaredConstructors
            .first { it.parameterCount == 8 } // default constructor
            .also { it.isAccessible = true }
            .newInstance(tool.name, argsMap, null, null, null, null, 0b111100, null)

        // Tool.createToolHandler(ToolContext, FunctionCall) → ToolHandler
        val handler = invoke(raw, "createToolHandler", toolContext, functionCall)
            ?: return "Failed to create handler for ${tool.name}"

        // ToolHandler.handle() → Response (suspend)
        val response = withContext(Dispatchers.IO) { callSuspend(handler, "handle") }
            ?: return "Handler returned null"

        // Response.text() → String
        return invoke(response, "text") as? String
            ?: invoke(response, "getStatus") as? String
            ?: response.toString()
    }

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

    private fun invoke(obj: Any, methodName: String, vararg args: Any?): Any? {
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
            runCatching {
                method.isAccessible = true
                return method.invoke(obj, *args)
            }
        }
        return null
    }

    private suspend fun callSuspend(obj: Any, methodName: String): Any? {
        val method = obj.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterTypes.lastOrNull()?.name == "kotlin.coroutines.Continuation"
        } ?: return invoke(obj, methodName)

        method.isAccessible = true
        return suspendCancellableCoroutine<Any?> { cont ->
            try {
                val result = method.invoke(obj, cont)
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

        /** Android-specific tools — the ones that can't be replicated outside the IDE. */
        private val ANDROID_TOOLS = setOf(
            // Device
            "read_logcat", "take_screenshot", "ui_state", "adb_shell_input",
            // Build & Gradle
            "gradle_sync", "gradle_build", "deploy", "version_lookup",
            "get_gradle_artifact_from_file", "get_assemble_task_for_artifact",
            "get_artifact_consumers", "get_build_file_location",
            "get_test_task_for_artifact", "get_top_level_sub_projects",
            "get_test_artifacts_for_sub_project", "get_source_folders_for_artifact",
            // Compose. As of build 261 this only exists on the newer
            // com.google.studiobot.agentsdk.toolsProvider extension point, so it is not
            // discovered there; kept here for older builds that still expose it on V1.
            "render_compose_preview",
            // Android docs & code search
            "code_search", "search_android_docs", "fetch_android_docs",
        )
    }
}
