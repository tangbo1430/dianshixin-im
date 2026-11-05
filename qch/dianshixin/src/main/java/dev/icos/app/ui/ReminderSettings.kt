package dev.icos.app.ui

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.icos.app.domain.PeriodResolver
import dev.icos.app.domain.ReportPeriod
import java.util.Calendar
import java.util.TimeZone
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONArray.objectsById(): Map<String, JSONObject> = buildMap {
    for (i in 0 until length()) {
        val row = getJSONObject(i)
        put(row.optString("id"), row)
    }
}

internal data class CreditReminderDates(val statementDate: LocalDate, val dueDate: LocalDate)

internal object CreditReminderPolicy {
    fun dates(today: LocalDate, statementDay: Int, includeStatementDay: Boolean, rule: String, value: Int): CreditReminderDates? {
        if (statementDay !in 1..31 || value !in 1..366) return null
        fun nominal(month: LocalDate): LocalDate = month.withDayOfMonth(minOf(statementDay, month.lengthOfMonth()))
        var statement = nominal(today)
        val previousNominal = nominal(today.minusMonths(1))
        val cycleStart = if (includeStatementDay) previousNominal.plusDays(1) else previousNominal
        val cycleEnd = if (includeStatementDay) statement.plusDays(1) else statement
        if (today.isBefore(cycleStart) || !today.isBefore(cycleEnd)) statement = nominal(today.plusMonths(1))
        val end = if (includeStatementDay) statement.plusDays(1) else statement
        val due = when (rule) {
            "FIXED_MONTH_DAY" -> {
                val candidate = statement.withDayOfMonth(minOf(value, statement.lengthOfMonth()))
                if (!candidate.isBefore(end)) candidate else {
                    val next = statement.plusMonths(1)
                    next.withDayOfMonth(minOf(value, next.lengthOfMonth()))
                }
            }
            "DAYS_AFTER_STATEMENT" -> statement.plusDays(value.toLong())
            else -> return null
        }
        if (due.isBefore(end)) return null
        return CreditReminderDates(statement, due)
    }
}

internal data class ReminderPreferences(
    val dailyEnabled: Boolean = false,
    val hour: Int = 9,
    val minute: Int = 0,
    val weekStart: Int = Calendar.MONDAY,
    val monthStart: Int = 1,
    val yearStartMonth: Int = Calendar.JANUARY,
    val creditEnabled: Boolean = false,
    val creditLeadDays: Int = 3,
) {
    init {
        require(hour in 0..23 && minute in 0..59)
        require(weekStart in Calendar.SUNDAY..Calendar.SATURDAY)
        require(monthStart in 1..31)
        require(yearStartMonth in Calendar.JANUARY..Calendar.DECEMBER)
        require(creditLeadDays in setOf(3, 7))
    }

    companion object {
        fun load(context: Context, owner: String): ReminderPreferences {
            val p = context.getSharedPreferences("local_reminders_$owner", Context.MODE_PRIVATE)
            return runCatching {
                ReminderPreferences(p.getBoolean("daily", false), p.getInt("hour", 9), p.getInt("minute", 0),
                    p.getInt("weekStart", Calendar.MONDAY), p.getInt("monthStart", 1),
                    p.getInt("yearStartMonth", Calendar.JANUARY), p.getBoolean("credit", false), p.getInt("leadDays", 3))
            }.getOrDefault(ReminderPreferences())
        }

        fun save(context: Context, owner: String, value: ReminderPreferences) {
            context.getSharedPreferences("local_reminders_$owner", Context.MODE_PRIVATE).edit()
                .putBoolean("daily", value.dailyEnabled).putInt("hour", value.hour).putInt("minute", value.minute)
                .putInt("weekStart", value.weekStart).putInt("monthStart", value.monthStart)
                .putInt("yearStartMonth", value.yearStartMonth).putBoolean("credit", value.creditEnabled)
                .putInt("leadDays", value.creditLeadDays).commit()
        }
    }
}

internal object ReminderScheduler {
    private const val CHANNEL = "local_finance_reminders"
    const val ACTION_FIRE = "dev.icos.app.action.LOCAL_REMINDER"
    private const val ACTION_BOOT = "android.intent.action.BOOT_COMPLETED"
    private const val EXTRA_OWNER = "owner"
    private const val EXTRA_KEY = "key"

    fun preferences(context: Context, owner: String) = ReminderPreferences.load(context, owner)

    fun scheduleDaily(context: Context, owner: String, p: ReminderPreferences, now: Long = System.currentTimeMillis()) {
        cancel(context, owner, "daily")
        if (!p.dailyEnabled) return
        val zone = TimeZone.getDefault().id
        val calendar = Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, p.hour); set(Calendar.MINUTE, p.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_MONTH, 1)
        }
        schedule(context, owner, "daily", calendar.timeInMillis)
    }

    fun scheduleCredit(context: Context, owner: String, key: String, atMillis: Long) {
        cancel(context, owner, key)
        if (atMillis > System.currentTimeMillis()) schedule(context, owner, key, atMillis)
    }

    private fun schedule(context: Context, owner: String, key: String, atMillis: Long) {
        if (atMillis <= System.currentTimeMillis()) return
        context.getSharedPreferences("local_reminders_$owner", Context.MODE_PRIVATE).edit().putLong("alarm:$key", atMillis).commit()
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE)
            .putExtra(EXTRA_OWNER, owner).putExtra(EXTRA_KEY, key)
        val pi = PendingIntent.getBroadcast(context, requestCode(owner, key), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT >= 23) alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        else alarm.set(AlarmManager.RTC_WAKEUP, atMillis, pi)
    }

    fun cancelOwner(context: Context, owner: String) {
        val prefs = context.getSharedPreferences("local_reminders_$owner", Context.MODE_PRIVATE)
        prefs.all.keys.filter { it.startsWith("alarm:") }.map { it.removePrefix("alarm:") }.forEach { cancel(context, owner, it) }
        cancel(context, owner, "daily")
    }

    fun restore(context: Context) {
        val p = context.getSharedPreferences("local_reminder_owners", Context.MODE_PRIVATE)
        val active = p.getString("active", null) ?: return
        val settings = ReminderPreferences.load(context, active)
        scheduleDaily(context, active, settings)
        if (settings.creditEnabled) context.getSharedPreferences("local_reminders_$active", Context.MODE_PRIVATE).all.keys
            .filter { it.startsWith("alarm:credit") }.forEach { key ->
                val alarmKey = key.removePrefix("alarm:")
                val at = context.getSharedPreferences("local_reminders_$active", Context.MODE_PRIVATE).getLong(key, 0L)
                if (at > System.currentTimeMillis()) schedule(context, active, alarmKey, at)
            }
        else cancelCredit(context, active)
    }

    fun cancelCredit(context: Context, owner: String) {
        context.getSharedPreferences("local_reminders_$owner", Context.MODE_PRIVATE).all.keys
            .filter { it.startsWith("alarm:credit") }.map { it.removePrefix("alarm:") }.forEach { cancel(context, owner, it) }
    }

    private fun cancel(context: Context, owner: String, key: String) {
        context.getSharedPreferences("local_reminders_$owner", Context.MODE_PRIVATE).edit().remove("alarm:$key").commit()
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_OWNER, owner).putExtra(EXTRA_KEY, key)
        PendingIntent.getBroadcast(context, requestCode(owner, key), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            context.getSystemService(AlarmManager::class.java)?.cancel(it); it.cancel()
        }
    }

    fun rememberOwner(context: Context, owner: String) {
        val registry = context.getSharedPreferences("local_reminder_owners", Context.MODE_PRIVATE)
        val owners = registry.getStringSet("owners", emptySet()).orEmpty() + owner
        registry.edit().putStringSet("owners", owners).apply()
    }

    private fun requestCode(owner: String, key: String): Int =
        java.nio.ByteBuffer.wrap(java.security.MessageDigest.getInstance("SHA-256").digest("$owner\u0000$key".toByteArray())).int and Int.MAX_VALUE
    private fun notify(context: Context, owner: String, key: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "本地提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val title = when {
            key.startsWith("credit-statement") -> "信用账单已出账"
            key.startsWith("credit-lead") -> "信用账单还款提醒"
            key.startsWith("credit-due") -> "今日还款提醒"
            else -> "今日统计提醒"
        }
        val text = if (key.startsWith("credit")) "打开 ICOS 查看信用账期。" else "打开 ICOS 查看本地统计。"
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text)
            .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
        runCatching { manager.notify(requestCode(owner, key), notification) }
    }

    internal fun fire(context: Context, owner: String, key: String) {
        val activeOwner = context.getSharedPreferences("local_reminder_owners", Context.MODE_PRIVATE).getString("active", null)
        if (activeOwner != owner) return
        if (key == "daily" && !ReminderPreferences.load(context, owner).dailyEnabled) return
        if (key.startsWith("credit") && !ReminderPreferences.load(context, owner).creditEnabled) return
        notify(context, owner, key)
        if (key == "daily") scheduleDaily(context, owner, ReminderPreferences.load(context, owner))
    }
}

internal class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ReminderScheduler.ACTION_FIRE -> {
                val owner = intent.getStringExtra("owner") ?: return
                val key = intent.getStringExtra("key") ?: return
                ReminderScheduler.fire(context, owner, key)
            }
            "android.intent.action.BOOT_COMPLETED", Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> ReminderScheduler.restore(context)
        }
    }
}
