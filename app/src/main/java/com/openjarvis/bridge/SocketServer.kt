package com.openjarvis.bridge

import android.content.Context
import android.os.Build
import com.openjarvis.agent.AgentCore
import com.openjarvis.agent.AgentState
import com.openjarvis.graphify.GraphifyRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

class SocketServer(
    private val context: Context,
    private val agentCore: AgentCore,
    private val graphifyRepo: GraphifyRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var runningPort = 0
    
    private var lastRequestTime = 0L
    private var requestCount = 0
    
    private val socketFile: java.io.File
        get() = java.io.File(context.filesDir, SOCKET_NAME)
    
    companion object {
        const val SOCKET_NAME = "jarvis.port"
        private const val MAX_COMMAND_LENGTH = 2000
        private const val MAX_REQUESTS_PER_SECOND = 10

        internal fun parseRequestLine(line: String): Pair<String, String>? = try {
            val json = org.json.JSONObject(line)
            val cmd = json.optString("cmd", "")
            val requestId = json.optString("requestId", "")
            if (cmd.isBlank()) null else Pair(requestId, cmd)
        } catch (_: Exception) {
            null
        }

        internal fun escapeJsonValue(value: String): String =
            org.json.JSONObject.quote(value).removeSurrounding("\"")

        internal fun errorResponse(requestId: String, error: String): String =
            """{"requestId":${org.json.JSONObject.quote(requestId)},"status":"error","result":${org.json.JSONObject.quote(error)}}"""
    }
    
    fun start() {
        if (isRunning) return
        
        isRunning = true
        scope.launch {
            acceptConnections()
        }
    }

    fun stop() {
        isRunning = false
        serverSocket?.close()
        serverSocket = null
        
        val sockFile = socketFile
        if (sockFile.exists()) {
            sockFile.delete()
        }
        
        scope.coroutineContext[Job]?.cancelChildren()
    }
    
    fun getPort(): Int = runningPort

    private suspend fun acceptConnections() = withContext(Dispatchers.IO) {
        try {
            serverSocket = ServerSocket().apply {
                bind(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0))
            }
            runningPort = serverSocket?.localPort ?: 0
            
            val sockFile = socketFile
            sockFile.writeText(runningPort.toString())
            
            while (isRunning) {
                try {
                    val client = serverSocket?.accept() ?: break
                    scope.launch { handleClient(client) }
                } catch (e: Exception) {
                    if (isRunning) {
                        delay(100)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun handleClient(clientSocket: Socket) = withContext(Dispatchers.IO) {
        try {
            val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
            val writer = PrintWriter(clientSocket.getOutputStream(), true)
            
            val lineBuilder = StringBuilder()
            while (true) {
                val next = reader.read()
                if (next == -1 || next == '\n'.code) break
                if (next == '\r'.code) continue
                if (lineBuilder.length >= MAX_COMMAND_LENGTH) {
                    writer.println(createErrorResponse("", "request too large"))
                    return@withContext
                }
                lineBuilder.append(next.toChar())
            }
            if (lineBuilder.isEmpty()) {
                writer.println(createErrorResponse("", "empty request"))
                return@withContext
            }
            val line = lineBuilder.toString()

            val request = parseRequest(line)
            if (request == null) {
                writer.println(createErrorResponse("", "invalid JSON"))
                return@withContext
            }
            
            val (requestId, cmd) = request
            
            when (cmd.lowercase()) {
                "status" -> handleStatus(writer, requestId)
                "history" -> handleHistory(writer, requestId)
                "providers" -> handleProviders(writer, requestId)
                "memory" -> handleMemory(writer, requestId)
                else -> handleCommand(writer, requestId, cmd)
            }
            
            writer.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                clientSocket.close()
            } catch (e: Exception) { }
        }
    }

    private fun handleStatus(writer: PrintWriter, requestId: String) {
        val accessibilityEnabled = com.openjarvis.accessibility.JarvisAccessibilityService.instance != null
        val provider = try {
            agentCore.getCurrentProviderName()
        } catch (_: Exception) { "unknown" }

        val result = org.json.JSONObject()
            .put("service", "running")
            .put("accessibility", accessibilityEnabled)
            .put("provider", provider)
            .put("port", runningPort)
        writer.println(responseJson(requestId, "done", result))
    }

    private suspend fun handleHistory(writer: PrintWriter, requestId: String) {
        val tasksJson = org.json.JSONArray()
        graphifyRepo.getRecentTasks(10).forEach { task ->
            tasksJson.put(
                org.json.JSONObject()
                    .put("id", task.id)
                    .put("command", task.command)
                    .put("result", task.result)
                    .put("timestamp", task.timestamp)
            )
        }
        writer.println(responseJson(requestId, "done", tasksJson))
    }

    private suspend fun handleProviders(writer: PrintWriter, requestId: String) {
        val providersJson = org.json.JSONArray()
        graphifyRepo.getProviderStats().forEach { provider ->
            providersJson.put(
                org.json.JSONObject()
                    .put("name", provider.name)
                    .put("useCount", provider.useCount)
                    .put("successRate", provider.successRate)
            )
        }
        writer.println(responseJson(requestId, "done", providersJson))
    }

    private suspend fun handleMemory(writer: PrintWriter, requestId: String) {
        val memoryContext = graphifyRepo.buildMemoryContext("")
        writer.println(responseJson(requestId, "done", memoryContext))
    }

    private suspend fun handleCommand(writer: PrintWriter, requestId: String, cmd: String) {
        val job = scope.launch {
            var sawRunningState = false
            agentCore.getStateFlow().collectLatest { state ->
                when (state) {
                    is AgentState.Running -> {
                        sawRunningState = true
                        writer.println(responseJson(requestId, "progress", state.step))
                        writer.flush()
                    }
                    is AgentState.Done -> {
                        if (sawRunningState) {
                            writer.println(responseJson(requestId, "done", state.result))
                            writer.flush()
                            cancel()
                        }
                    }
                    is AgentState.Error -> {
                        if (sawRunningState) {
                            writer.println(responseJson(requestId, "error", state.message))
                            writer.flush()
                            cancel()
                        }
                    }
                    is AgentState.Idle -> { }
                }
            }
        }

        agentCore.executeTask(cmd)
        job.join()
    }

    private fun responseJson(requestId: String, status: String, result: Any): String =
        org.json.JSONObject()
            .put("requestId", requestId)
            .put("status", status)
            .put("result", result)
            .toString()

    private fun parseRequest(line: String): Pair<String, String>? = parseRequestLine(line)

    internal fun createErrorResponse(requestId: String, error: String): String =
        errorResponse(requestId, error)

    internal fun escapeJson(s: String): String = escapeJsonValue(s)

}