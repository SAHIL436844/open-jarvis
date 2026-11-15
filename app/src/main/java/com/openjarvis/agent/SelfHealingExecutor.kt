package com.openjarvis.agent

import android.content.Context
import com.openjarvis.accessibility.JarvisAccessibilityService
import com.openjarvis.accessibility.ScreenReader
import com.openjarvis.graphify.GraphifyRepository
import com.openjarvis.llm.UniversalAdapter
import kotlinx.coroutines.delay

class SelfHealingExecutor(private val context: Context) {
    private val screenReader = ScreenReader()
    private val graphifyRepo = GraphifyRepository(context)
    private val llm = UniversalAdapter(context)
    private val maxAttempts = 3
    private val baseDelayMs = 1000L

    suspend fun executeWithHealing(
        action: Action,
        context: ExecutionContext,
        attempt: Int = 1
    ): ActionResult {
        val result = tryExecuteAction(action, context)
        if (result is ActionResult.Success) return result
        if (attempt >= maxAttempts) {
            return ActionResult.Failed("Could not complete after $maxAttempts attempts")
        }

        val currentScreen = screenReader.extractAllText()
        val prompt = buildHealingPrompt(action, context, currentScreen, attempt)
        val alternativeResponse = try {
            llm.complete(prompt.first, prompt.second).getOrNull()
        } catch (_: Exception) {
            null
        }

        delay(baseDelayMs * attempt)
        if (alternativeResponse != null) {
            val alternativeAction = parseActionFromLLM(alternativeResponse)
            if (alternativeAction != null) {
                return executeWithHealing(alternativeAction, context, attempt + 1)
            }
        }
        return executeWithHealing(action, context, attempt + 1)
    }

    private fun tryExecuteAction(action: Action, context: ExecutionContext): ActionResult {
        val service = JarvisAccessibilityService.instance
            ?: return ActionResult.Failed("Accessibility service is not connected")
        return try {
            when (action.action) {
                Action.TAP -> {
                    val text = action.text.orEmpty()
                    if (service.tapByText(text)) ActionResult.Success("tapped $text")
                    else ActionResult.Failed("tap failed")
                }
                Action.TYPE -> {
                    val value = action.value.orEmpty()
                    if (service.typeText(value)) ActionResult.Success("typed text")
                    else ActionResult.Failed("type failed")
                }
                Action.OPEN_APP -> {
                    val packageName = action.packageName
                        ?: return ActionResult.Failed("missing package name")
                    service.openAppByPackage(packageName)
                    ActionResult.Success("opened $packageName")
                }
                Action.PRESS_BACK -> {
                    if (service.pressBack()) ActionResult.Success("pressed back")
                    else ActionResult.Failed("back action failed")
                }
                else -> ActionResult.Failed("unsupported action: ${action.action}")
            }
        } catch (e: Exception) {
            ActionResult.Failed(e.message ?: "Unknown error")
        }
    }

    private fun buildHealingPrompt(
        action: Action,
        context: ExecutionContext,
        currentScreen: String,
        attempt: Int
    ): Pair<String, String> {
        val system = """
Action failed: ${action.action}
Expected to see: ${context.expectedState ?: "task completion"}
Current screen shows: ${currentScreen.take(500)}
Attempt: $attempt/$maxAttempts

What went wrong and what alternative action should I try?
Respond with a single alternative Action JSON.
        """.trimIndent()
        return system to """
The action failed. Current screen: "$currentScreen"
Give me one alternative action that might work.
        """
    }

    private fun parseActionFromLLM(response: String): Action? = try {
        val json = org.json.JSONObject(response.trim())
        Action(
            action = json.getString("action"),
            text = json.optString("text").takeIf { it.isNotBlank() },
            value = json.optString("value").takeIf { it.isNotBlank() },
            packageName = json.optString("package").takeIf { it.isNotBlank() }
        )
    } catch (_: Exception) {
        null
    }

    data class ExecutionContext(
        val expectedState: String?,
        val phaseId: String,
        val previousPhaseOutcome: String?,
        val screenBefore: String
    )

    sealed class ActionResult {
        data class Success(val message: String) : ActionResult()
        data class Failed(val reason: String) : ActionResult()
    }
}
