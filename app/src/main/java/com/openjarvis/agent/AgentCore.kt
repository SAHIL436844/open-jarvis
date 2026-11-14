package com.openjarvis.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.openjarvis.accessibility.JarvisAccessibilityService
import com.openjarvis.accessibility.ScreenReader
import com.openjarvis.graphify.AnalysisEngine
import com.openjarvis.graphify.GraphifyRepository
import com.openjarvis.intelligence.AIAppInteractor
import com.openjarvis.intelligence.AIApps
import com.openjarvis.intelligence.AppAnalyzer
import com.openjarvis.intelligence.TaskRouter
import com.openjarvis.intelligence.TaskWorkingMemory
import com.openjarvis.llm.UniversalAdapter
import com.openjarvis.vision.VisionModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AgentCore(private val context: Context) {

    private val graphifyRepo = GraphifyRepository(context)
    private val analysisEngine = AnalysisEngine(context)
    private val universalAdapter = UniversalAdapter(context)
    private val screenReader = ScreenReader()
    private val visionModule = VisionModule.getInstance(context)
    private val taskRouter = TaskRouter(context)
    private val appAnalyzer = AppAnalyzer(context)
    private val aiAppInteractor = AIAppInteractor(context)
    private var workingMemory = TaskWorkingMemory()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val taskMutex = Mutex()

    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state

    private val systemPrompt = """
You are Open Jarvis — an Android device control AI agent.
The user gives you a cleanCommand in natural language.
You must respond with ONLY a valid JSON array of actions. No explanation. No markdown fences. No preamble. Pure JSON array only.

AVAILABLE ACTIONS:
open_app     → {"action":"open_app","package":"com.package","label":"AppName"}
tap          → {"action":"tap","text":"Button text on screen"}
tap_coords   → {"action":"tap_coords","x":540,"y":960}
long_press   → {"action":"long_press","text":"Element text"}
type         → {"action":"type","value":"text to type"}
clear_type   → {"action":"clear_type","value":"clears field then types"}
swipe        → {"action":"swipe","direction":"up|down|left|right","distance":"short|medium|long"}
scroll       → {"action":"scroll","direction":"up|down"}
press_back   → {"action":"press_back"}
press_home   → {"action":"press_home"}
press_recents → {"action":"press_recents"}
wait_for     → {"action":"wait_for","text":"expected text","timeout_ms":3000}
screenshot   → {"action":"screenshot"}
read_screen  → {"action":"read_screen"}
ai_prompt    → {"action":"ai_prompt","package":"com.openai.chatgpt","prompt":"{prompt}","outputKey":"result"}
extract_text → {"action":"extract_text","outputKey":"page_text"}

CURRENT SCREEN CONTENT: {SCREEN_OCR}

APP SELECTION REASONING: {APP_REASONING}

INSTALLED AI APPS: {AI_APPS}

RECENT MEMORY CONTEXT: {GRAPHIFY_CONTEXT}

RULES:
- Always start complex tasks with open_app
- Add wait_for after open_app to confirm app loaded
- If screen content is empty or unclear, add read_screen as first action
- Never assume UI state — always verify with wait_for
- Keep action arrays short: 2-8 steps per task
- Use ai_prompt to delegate complex reasoning to installed AI apps
- If a task is impossible to do safely, return: [{"action":"error","message":"reason"}]
""".trimIndent()

fun executeTask(cleanCommand: String) {
        workingMemory = TaskWorkingMemory()
        
        scope.launch {
            taskMutex.withLock {
                try {
                    val sanitized = PromptSanitizer.sanitize(cleanCommand)
                    when (sanitized) {
                        is PromptSanitizer.SanitizeResult.Rejected -> {
                            _state.value = AgentState.Error(sanitized.reason)
                            return@withLock
                        }
                        is PromptSanitizer.SanitizeResult.Suspicious -> {
                            _state.value = AgentState.Running("analyzing...")
                        }
                        is PromptSanitizer.SanitizeResult.Clean -> { }
                    }
                    
                    val cleanCommand = when (sanitized) {
                        is PromptSanitizer.SanitizeResult.Suspicious -> sanitized.sanitized
                        is PromptSanitizer.SanitizeResult.Clean -> sanitized.text
                        else -> cleanCommand
                    }
                    
                    _state.value = AgentState.Running("analyzing task...")
                
                val plan = taskRouter.analyze(cleanCommand)
                
                _state.value = AgentState.Running("reading screen...")
                
                val screenText = withContext(Dispatchers.IO) {
                    screenReader.extractAllText()
                }
                
                _state.value = AgentState.Running("getting context...")
                
                val memoryContext = graphifyRepo.buildMemoryContext(cleanCommand)
                
                val fullSystem = systemPrompt
                    .replace("{SCREEN_OCR}", screenText.take(2000))
                    .replace("{APP_REASONING}", plan.reasoning)
                    .replace("{AI_APPS}", getInstalledAIApps())
                    .replace("{GRAPHIFY_CONTEXT}", if (memoryContext.isBlank()) "No recent tasks" else memoryContext)
                
                _state.value = AgentState.Running("thinking...")
                
                val startTime = System.currentTimeMillis()
                
                val result = universalAdapter.complete(fullSystem, cleanCommand)
                result.fold(
                    onSuccess = { rawJson ->
                        val latency = System.currentTimeMillis() - startTime
                        
                        val validation = LLMResponseValidator.validate(rawJson)
                        val rawForParsing = if (!validation.isValid && validation.errors.isNotEmpty()) {
                            _state.value = AgentState.Error("Invalid response: ${validation.errors.first()}")
                            graphifyRepo.logTask(cleanCommand, "failed: validation error", "", 0)
                            return@fold
                        } else {
                            rawJson
                        }
                        
                        val actions = ActionJsonParser.parse(rawForParsing)
                            ?: run {
                                val retry = universalAdapter.complete(
                                    fullSystem,
                                    "$cleanCommand\n\nRespond with JSON array ONLY. No other text."
                                )
                                retry.getOrNull()?.let { ActionJsonParser.parse(it) }
                            }
                        
                        if (actions == null) {
                            _state.value = AgentState.Error("Could not parse AI response")
                            graphifyRepo.logTask(cleanCommand, "failed: parse error", "", 0)
                            return@fold
                        }
                        
                        _state.value = AgentState.Running("executing ${actions.size} actions...")
                        
                        val executionError = executeActions(actions)
                        if (executionError != null) {
                            _state.value = AgentState.Error(executionError)
                            graphifyRepo.logTask(
                                command = cleanCommand,
                                result = "failed: $executionError",
                                provider = universalAdapter.getProviderName(),
                                latencyMs = latency
                            )
                            return@fold
                        }

                        graphifyRepo.logTask(
                            command = cleanCommand,
                            result = "success",
                            provider = universalAdapter.getProviderName(),
                            latencyMs = latency
                        )

                        analysisEngine.analyzeLastTask()

                        _state.value = AgentState.Done("done in ${latency}ms")
                    },
                    onFailure = { error ->
                        val msg = when {
                            error.message?.contains("401") == true -> "Invalid API key"
                            error.message?.contains("429") == true -> "Rate limited — wait a moment"
                            error.message?.contains("timeout") == true -> "Request timed out"
                            error.message?.contains("Unable to resolve") == true -> "Network error — check connection"
                            else -> error.message ?: "Unknown error"
                        }
                        _state.value = AgentState.Error(msg)
                        graphifyRepo.logTask(cleanCommand, "failed: $msg", "", 0)
                    }
                )
            } catch (e: Exception) {
                _state.value = AgentState.Error(e.message ?: "Unknown error")
                graphifyRepo.logTask(cleanCommand, "failed: ${e.message}", "", 0)
            }
        }
    }
    }

    suspend fun testConnection(): Result<Long> {
        return universalAdapter.testConnection()
    }
    
    fun getCurrentProviderName(): String {
        return universalAdapter.getProviderName()
    }
    
    fun getStateFlow(): StateFlow<AgentState> = state
    
    private fun getInstalledAIApps(): String {
        return AIApps.KNOWN_AI_APPS.keys.joinToString(", ")
    }
    
    suspend fun getAnalyzedAppCount(): Int = appAnalyzer.getAnalyzedCount()
    
    suspend fun getAIAppCount(): Int = appAnalyzer.getAICount()

    /**
     * Executes actions sequentially and returns null only when every action succeeds.
     * A non-null result is shown to the user and recorded as a failed task.
     */
    private suspend fun executeActions(actions: List<Action>): String? {
        if (actions.isEmpty()) return "The AI returned an empty action plan"

        for ((index, action) in actions.withIndex()) {
            _state.value = AgentState.Running("action ${index + 1}/${actions.size}")

            val service = JarvisAccessibilityService.instance
            val error = when (action.action) {
                Action.OPEN_APP -> {
                    val packageName = action.packageName ?: action.label?.let { findPackageByLabel(it) }
                    if (packageName == null) {
                        "App not found: ${action.label ?: "no package or label provided"}"
                    } else if (service == null) {
                        "Accessibility service is not connected"
                    } else if (context.packageManager.getLaunchIntentForPackage(packageName) == null) {
                        "No launchable app found for package: $packageName"
                    } else {
                        service.openAppByPackage(packageName)
                        graphifyRepo.logAppOpened(packageName, action.label ?: packageName)
                        null
                    }
                }
                Action.TAP -> {
                    val text = action.text
                    when {
                        text.isNullOrBlank() -> "Tap action has no target text"
                        service == null -> "Accessibility service is not connected"
                        service.tapByText(text) -> null
                        else -> "Could not tap: $text"
                    }
                }
                Action.TYPE -> {
                    val value = action.value
                    when {
                        value == null -> "Type action has no value"
                        service == null -> "Accessibility service is not connected"
                        service.typeText(value) -> null
                        else -> "Could not type text"
                    }
                }
                Action.PRESS_BACK -> if (service?.pressBack() == true) null else "Back action failed or accessibility service is disconnected"
                Action.PRESS_HOME -> if (service?.pressHome() == true) null else "Home action failed or accessibility service is disconnected"
                Action.PRESS_RECENTS -> if (service?.pressRecents() == true) null else "Recents action failed or accessibility service is disconnected"
                Action.AI_PROMPT -> {
                    val packageName = action.packageName
                    val meta = packageName?.let { AIApps.KNOWN_AI_APPS[it] }
                    if (meta == null) {
                        "Unsupported AI app: ${packageName ?: "package not provided"}"
                    } else {
                        try {
                            val prompt = workingMemory.interpolate(action.prompt ?: "")
                            aiAppInteractor.openAIApp(meta)
                            kotlinx.coroutines.delay(2000)
                            if (!aiAppInteractor.typePrompt(prompt)) {
                                "Could not enter prompt in AI app"
                            } else {
                                kotlinx.coroutines.delay(1000)
                                val response = aiAppInteractor.waitForResponse()
                                val outputKey = action.outputKey
                                if (outputKey != null && response.isBlank()) {
                                    "AI app returned no response"
                                } else {
                                    if (outputKey != null) workingMemory.set(outputKey, response)
                                    null
                                }
                            }
                        } catch (e: Exception) {
                            "AI app action failed: ${e.message ?: "unknown error"}"
                        }
                    }
                }
                Action.EXTRACT_TEXT -> {
                    try {
                        val outputKey = action.outputKey ?: "page_text"
                        val text = screenReader.extractAllText()
                        workingMemory.set(outputKey, text)
                        if (text.isBlank()) "Could not extract screen text" else null
                    } catch (e: Exception) {
                        "Screen text extraction failed: ${e.message ?: "unknown error"}"
                    }
                }
                Action.ERROR -> action.message ?: "The AI plan reported an error"
                else -> "Unsupported action: ${action.action}"
            }

            if (error != null) return "Action ${index + 1}/${actions.size} (${action.action}) failed: $error"
            kotlinx.coroutines.delay(500)
        }
        return null
    }

    private fun findPackageByLabel(label: String): String? {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        
        val apps: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
        
        val normalizedLabel = label.lowercase().trim()
        
        for (app in apps) {
            val appLabel = app.loadLabel(pm).toString().lowercase()
            if (appLabel == normalizedLabel || appLabel.contains(normalizedLabel) || normalizedLabel.contains(appLabel)) {
                return app.activityInfo.packageName
            }
        }
        
        return null
    }
}