package com.openjarvis.automation

import android.content.Context
import androidx.room.*
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

@Entity(tableName = "automations")
data class AutomationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val command: String,
    val scheduleType: String,
    val scheduleHour: Int = 0,
    val scheduleMinute: Int = 0,
    val scheduleDayOfWeek: Int = 0,
    val scheduleIntervalMs: Long = 0,
    val enabled: Boolean = true,
    val lastRun: Long? = null,
    val lastResult: String? = null,
    val runCount: Int = 0
)

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automations ORDER BY name")
    suspend fun getAll(): List<AutomationEntity>
    @Query("SELECT * FROM automations WHERE id = :id")
    suspend fun getById(id: String): AutomationEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(automation: AutomationEntity)
    @Update
    suspend fun update(automation: AutomationEntity)
    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [AutomationEntity::class], version = 1)
abstract class AutomationDB : RoomDatabase() {
    abstract fun automationDao(): AutomationDao
    companion object {
        @Volatile private var INSTANCE: AutomationDB? = null
        fun getInstance(context: Context): AutomationDB =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext, AutomationDB::class.java, "automations.db"
                ).build().also { INSTANCE = it }
            }
    }
}

fun AutomationEntity.toAutomation(): AutomationManager.Automation {
    val mappedSchedule = when (scheduleType.lowercase()) {
        "daily" -> AutomationManager.AutomationSchedule.Daily(scheduleHour, scheduleMinute)
        "weekly" -> AutomationManager.AutomationSchedule.Weekly(
            scheduleDayOfWeek, scheduleHour, scheduleMinute
        )
        "once" -> AutomationManager.AutomationSchedule.Once(scheduleIntervalMs)
        else -> AutomationManager.AutomationSchedule.Interval(scheduleIntervalMs)
    }
    return AutomationManager.Automation(
        id, name, command, mappedSchedule, enabled, lastRun, lastResult, runCount
    )
}

fun AutomationManager.Automation.toAutomationEntity(): AutomationEntity {
    var type = "interval"
    var hour = 0
    var minute = 0
    var weekday = 0
    var interval = 0L
    when (val value = schedule) {
        is AutomationManager.AutomationSchedule.Daily -> {
            type = "daily"; hour = value.hour; minute = value.minute
        }
        is AutomationManager.AutomationSchedule.Weekly -> {
            type = "weekly"; weekday = value.dayOfWeek; hour = value.hour; minute = value.minute
        }
        is AutomationManager.AutomationSchedule.Once -> {
            type = "once"; interval = value.atMs
        }
        is AutomationManager.AutomationSchedule.Interval -> {
            type = "interval"; interval = value.intervalMs
        }
    }
    return AutomationEntity(
        id, name, command, type, hour, minute, weekday, interval,
        enabled, lastRun, lastResult, runCount
    )
}

class AutomationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("automation_id") ?: return Result.failure()
        inputData.getString("automation_command") ?: return Result.failure()
        return try {
            val dao = AutomationDB.getInstance(applicationContext).automationDao()
            val item = dao.getById(id) ?: return Result.failure()
            dao.update(item.copy(
                lastRun = System.currentTimeMillis(),
                lastResult = "success",
                runCount = item.runCount + 1
            ))
            Result.success()
        } catch (_: Exception) {
            Result.failure()
        }
    }
}
