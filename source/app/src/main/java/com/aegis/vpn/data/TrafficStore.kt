package com.aegis.vpn.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Накопительная статистика трафика.
 *
 * Хранит израсходованные байты по дням (ключ — "yyyy-MM-dd"), поэтому
 * день/неделя/месяц считаются суммированием. Данные лежат в SharedPreferences
 * и переживают перезапуск приложения и телефона.
 */
class TrafficStore(context: Context) {

    private val prefs = context.getSharedPreferences("aegis_traffic", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Накопленное за каждый день: ключ "yyyy-MM-dd" -> байты. */
    private val _daily = MutableStateFlow<Map<String, TrafficDay>>(loadDaily())
    val daily: StateFlow<Map<String, TrafficDay>> = _daily.asStateFlow()

    data class TrafficDay(val rx: Long = 0L, val tx: Long = 0L) {
        val total: Long get() = rx + tx
    }

    /** Один день истории для графика. */
    data class DayStat(val key: String, val rx: Long, val tx: Long) {
        val total: Long get() = rx + tx
    }

    /** Итог за сегодня, неделю и месяц (rx/tx раздельно и суммарно). */
    data class PeriodTotals(val rx: Long, val tx: Long) {
        val total: Long get() = rx + tx
    }

    data class Summary(
        val today: PeriodTotals,
        val week: PeriodTotals,
        val month: PeriodTotals,
        val allTime: PeriodTotals
    )

    val summary: Summary
        get() = buildSummary(_daily.value)

    /** Добавляет израсходованные байты к сегодняшнему дню и сохраняет. */
    @Synchronized
    fun add(rx: Long, tx: Long) {
        if (rx <= 0 && tx <= 0) return
        val today = dayFormat.format(Date())
        val current = _daily.value
        val old = current[today] ?: TrafficDay()
        val updated = current.toMutableMap().apply {
            this[today] = TrafficDay(
                rx = old.rx + rx.coerceAtLeast(0),
                tx = old.tx + tx.coerceAtLeast(0)
            )
        }
        _daily.value = updated
        persist(updated)
    }

    /** Последние [count] дней в хронологическом порядке; пустые дни — с нулями. */
    @Synchronized
    fun lastDays(count: Int = 7): List<DayStat> {
        if (count <= 0) return emptyList()
        val days = _daily.value
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -(count - 1))
        val result = ArrayList<DayStat>(count)
        repeat(count) {
            val key = dayFormat.format(calendar.time)
            val value = days[key]
            result += DayStat(key, value?.rx ?: 0L, value?.tx ?: 0L)
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return result
    }

    /** Полная очистка истории. */
    @Synchronized
    fun reset() {
        _daily.value = emptyMap()
        prefs.edit().remove(KEY_DAILY).apply()
    }

    private fun buildSummary(days: Map<String, TrafficDay>): Summary {
        if (days.isEmpty()) {
            val zero = PeriodTotals(0, 0)
            return Summary(zero, zero, zero, zero)
        }

        val todayKey = dayFormat.format(Date())
        val calendar = Calendar.getInstance()

        // Начало недели (понедельник 00:00).
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        // Понедельник = 0, воскресенье = 6.
        val daysFromMonday = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
        calendar.add(Calendar.DAY_OF_YEAR, -daysFromMonday)
        val weekStart = calendar.timeInMillis

        // Начало месяца.
        calendar.timeInMillis = System.currentTimeMillis()
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val monthStart = calendar.timeInMillis

        var todayRx = 0L
        var todayTx = 0L
        var weekRx = 0L
        var weekTx = 0L
        var monthRx = 0L
        var monthTx = 0L
        var allRx = 0L
        var allTx = 0L

        for ((key, value) in days) {
            val time = runCatching { dayFormat.parse(key)?.time }.getOrNull() ?: continue
            allRx += value.rx
            allTx += value.tx
            if (key == todayKey) {
                todayRx += value.rx
                todayTx += value.tx
            }
            if (time >= weekStart) {
                weekRx += value.rx
                weekTx += value.tx
            }
            if (time >= monthStart) {
                monthRx += value.rx
                monthTx += value.tx
            }
        }

        return Summary(
            today = PeriodTotals(todayRx, todayTx),
            week = PeriodTotals(weekRx, weekTx),
            month = PeriodTotals(monthRx, monthTx),
            allTime = PeriodTotals(allRx, allTx)
        )
    }

    private fun loadDaily(): Map<String, TrafficDay> {
        val raw = prefs.getString(KEY_DAILY, null) ?: return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, TrafficDay>>() {}.type
            gson.fromJson<Map<String, TrafficDay>>(raw, type) ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    private fun persist(days: Map<String, TrafficDay>) {
        // Держим не больше 400 дней, чтобы файл не разрастался бесконечно.
        val trimmed = if (days.size > MAX_DAYS) {
            days.entries
                .sortedByDescending { it.key }
                .take(MAX_DAYS)
                .associate { it.key to it.value }
        } else days
        prefs.edit().putString(KEY_DAILY, gson.toJson(trimmed)).apply()
    }

    private companion object {
        const val KEY_DAILY = "traffic_daily_v1"
        const val MAX_DAYS = 400
    }
}
