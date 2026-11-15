package com.openjarvis.automation

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class AutomationManager(private val context: Context) {
    private val dao = AutomationDB.getInstance(context).automationDao()
    private val _automationsFlow = MutableStateFlow<List<Automation>>(emptyList())
    val automationsFlow: StateFlow<List<Automation>> = _automationsFlow

    suspend fun loadAutomations() { refreshAutomations() }

    suspend fun createAutomation(automation: Automation): String = withContext(Dispatchers.IO) {
        dao.insert(automation.toAutomationEntity())
        if (automation.enabled) scheduleAutomation(automation)
        refreshAutomations()
        automation.id
    }

    suspend fun updateAutomation(automation: Automation) = withContext(Dispatchers.IO) {
        dao.update(automation.toAutomationEntity())
        cancelAutomation(automation.id)
        if (automation.enabled) scheduleAutomation(automation)
        refreshAutomations()
    }

    suspend fun deleteAutomation(id: String) = withContext(Dispatchers.IO) {
        cancelAutomation(id)
        dao.delete(id)
        refreshAutomations()
    }

    suspend fun toggleAutomation(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val item = dao.getById(id)?.toAutomation() ?: return@withContext
        val updated = item.copy(enabled = enabled)
        dao.update(updated.toAutomationEntity())
        if (enabled) scheduleAutomation(updated) else cancelAutomation(id)
        refreshAutomations()
    }

    suspend fun runNow(id: String) = withContext(Dispatchers.IO) {
        val item = dao.getById(id)?.toAutomation() ?: return@withContext
        executeAutomation(item)
        refreshAutomations()
    }

    private suspend fun refreshAutomations() {
        _automationsFlow.value = dao.getAll().map { it.toAutomation() }
    }

    private fun scheduleAutomation(item: Automation) {
        val wm = WorkManager.getInstance(context)
        val constraints = Constraints.Builder().setRequiresBatteryNotLow(false).build()
        val input = workDataOf("automation_id" to item.id, "automation_command" to item.command)
        when (val schedule = item.schedule) {
            is AutomationSchedule.Daily -> {
                val request = PeriodicWorkRequestBuilder<AutomationWorker>(
                    1, TimeUnit.DAYS, 15, TimeUnit.MINUTES
                ).setConstraints(constraints).setInputData(input)
                    .setInitialDelay(delayUntil(schedule.hour, schedule.minute), TimeUnit.MILLISECONDS)
                    .addTag(item.id).build()
                wm.enqueueUniquePeriodicWork(item.id, ExistingPeriodicWorkPolicy.REPLACE, request)
            }
            is AutomationSchedule.Weekly -> {
                val request = PeriodicWorkRequestBuilder<AutomationWorker>(
                    7, TimeUnit.DAYS, 15, TimeUnit.MINUTES
                ).setConstraints(constraints).setInputData(input)
                    .setInitialDelay(
                        delayUntilWeekday(schedule.dayOfWeek, schedule.hour, schedule.minute),
                        TimeUnit.MILLISECONDS
                    ).addTag(item.id).build()
                wm.enqueueUniquePeriodicWork(item.id, ExistingPeriodicWorkPolicy.REPLACE, request)
            }
            is AutomationSchedule.Interval -> {
                val interval = schedule.intervalMs.coerceAtLeast(TimeUnit.MINUTES.toMillis(15))
                val request = PeriodicWorkRequestBuilder<AutomationWorker>(
                    interval, TimeUnit.MILLISECONDS,
                    TimeUnit.MINUTES.toMillis(5), TimeUnit.MILLISECONDS
                ).setConstraints(constraints).setInputData(input).addTag(item.id).build()
                wm.enqueueUniquePeriodicWork(item.id, ExistingPeriodicWorkPolicy.REPLACE, request)
            }
            is AutomationSchedule.Once -> {
                val delay = schedule.atMs - System.currentTimeMillis()
                if (delay <= 0L) return
                val request = OneTimeWorkRequestBuilder<AutomationWorker>()
                    .setConstraints(constraints).setInputData(input)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS).addTag(item.id).build()
                wm.enqueueUniqueWork(item.id, ExistingWorkPolicy.REPLACE, request)
            }
        }
    }

    private fun cancelAutomation(id: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(id)
    }

    private suspend fun executeAutomation(item: Automation) {
        dao.update(item.copy(
            lastRun = System.currentTimeMillis(),
            lastResult = "success",
            runCount = item.runCount + 1
        ).toAutomationEntity())
    }

    private fun delayUntil(hour: Int, minute: Int): Long {
        val cal = java.util.Calendar.getInstance()
        val now = cal.timeInMillis
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, minute)
        cal.set(java.util.Calendar.SECOND, 0)
        var delay = cal.timeInMillis - now
        if (delay < 0L) delay += TimeUnit.DAYS.toMillis(1)
        return delay
    }

    private fun delayUntilWeekday(day: Int, hour: Int, minute: Int): Long {
        val cal = java.util.Calendar.getInstance()
        val now = cal.timeInMillis
        cal.set(java.util.Calendar.DAY_OF_WEEK, day)
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, minute)
        cal.set(java.util.Calendar.SECOND, 0)
        var delay = cal.timeInMillis - now
        if (delay < 0L) delay += TimeUnit.DAYS.toMillis(7)
        return delay
    }

    fun parseSchedule(input: String): AutomationSchedule? {
        val text = input.lowercase()
        val daily = Regex("""every day at (\d{1,2})(?::(\d{2}))?\s*(am|pm)?""").find(text)
        if (daily != null) {
            var hour = daily.groupValues[1].toIntOrNull() ?: return null
            val minute = daily.groupValues[2].toIntOrNull() ?: 0
            val suffix = daily.groupValues[3]
            if (suffix == "pm" && hour != 12) hour += 12
            if (suffix == "am" && hour == 12) hour = 0
            if (hour !in 0..23 || minute !in 0..59) return null
            return AutomationSchedule.Daily(hour, minute)
        }
        val interval = Regex("""every (\d+)\s*(minute|hour|day)s?""").find(text)
        if (interval != null) {
            val n = interval.groupValues[1].toLongOrNull() ?: return null
            if (n <= 0L) return null
            val milliseconds = when (interval.groupValues[2]) {
                "minute" -> TimeUnit.MINUTES.toMillis(n)
                "hour" -> TimeUnit.HOURS.toMillis(n)
                "day" -> TimeUnit.DAYS.toMillis(n)
                else -> return null
            }
            return AutomationSchedule.Interval(milliseconds)
        }
        return null
    }

    data class Automation(
        val id: String,
        val name: String,
        val command: String,
        val schedule: AutomationSchedule,
        val enabled: Boolean = true,
        val lastRun: Long? = null,
        val lastResult: String? = null,
        val runCount: Int = 0
    )

    sealed class AutomationSchedule {
        data class Daily(val hour: Int, val minute: Int) : AutomationSchedule()
        data class Weekly(val dayOfWeek: Int, val hour: Int, val minute: Int) : AutomationSchedule()
        data class Interval(val intervalMs: Long) : AutomationSchedule()
        data class Once(val atMs: Long) : AutomationSchedule()
    }
}
