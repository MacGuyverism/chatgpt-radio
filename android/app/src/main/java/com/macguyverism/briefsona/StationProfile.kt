package com.macguyverism.briefsona

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

internal enum class StationMode(val label: String) {
    MANUAL("Manual"), SCHEDULED("Scheduled"), PAUSED("Scheduled (paused)")
}

internal data class StationSegment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val durationSeconds: Int? = null,
    val cue: String? = null,
    val enabled: Boolean = true,
    val instructions: String = "",
    val randomPosition: Boolean = false
) {
    fun json() = JSONObject().put("id", id).put("name", name).put("duration_seconds", durationSeconds ?: JSONObject.NULL)
        .put("random_position", randomPosition).put("cue", cue ?: JSONObject.NULL).put("enabled", enabled).put("instructions", instructions)

    companion object {
        fun from(json: JSONObject, legacyRandom: Boolean = false) = StationSegment(
            id = json.getString("id"), name = json.getString("name"),
            durationSeconds = if (json.isNull("duration_seconds")) null else json.getInt("duration_seconds"),
            cue = json.optString("cue").takeUnless { json.isNull("cue") },
            enabled = json.optBoolean("enabled", true), instructions = json.optString("instructions"),
            randomPosition = json.optBoolean("random_position", legacyRandom)
        )
    }
}

internal data class StationProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val minutes: Int = 30,
    val mode: StationMode = StationMode.MANUAL,
    val segments: List<StationSegment> = listOf(StationSegment(name = "Opening"), StationSegment(name = "Closing")),
    val icon: String = "◉",
    val instructions: String = ""
) {
    fun duplicate() = copy(
        id = UUID.randomUUID().toString(), name = "$name (copy)",
        segments = segments.map { it.copy(id = UUID.randomUUID().toString()) }
    )

    val canAddSegment: Boolean get() = segments.size - 2 < (minutes / 3).coerceAtMost(20)

    fun withSegment(segment: StationSegment): StationProfile {
        val updated = segments.toMutableList()
        val index = updated.indexOfFirst { it.id == segment.id }
        if (index < 0) updated.add(updated.lastIndex, segment) else updated[index] = segment
        return copy(segments = updated)
    }

    fun fixedSeconds(segment: StationSegment): Int? = segment.durationSeconds
        ?: if (segment.id == segments.first().id || segment.id == segments.last().id) 30 else null

    fun airtime(): Airtime {
        val enabled = segments.filter { it.enabled }
        val automatic = enabled.filter { fixedSeconds(it) == null }
        val remaining = minutes.coerceIn(0, 60) * 60 - enabled.sumOf { fixedSeconds(it) ?: 0 }
        val pool = remaining.coerceAtLeast(0)
        val estimates = enabled.associate { it.id to (fixedSeconds(it) ?: 0) }.toMutableMap()
        automatic.forEachIndexed { index, segment ->
            estimates[segment.id] = pool / automatic.size + if (index < pool % automatic.size) 1 else 0
        }
        return Airtime(remaining, automatic.size, estimates)
    }

    fun validationError(): String? = when {
        name.isBlank() -> "Enter a station name."
        minutes !in 5..60 -> "Enter a duration from 5 to 60 minutes."
        segments.size - 2 > minutes / 3 -> "This duration allows up to ${minutes / 3} custom segments."
        segments.size < 2 || !segments.first().enabled || !segments.last().enabled ->
            "Opening and Closing must stay enabled."
        segments.any { it.name.isBlank() } -> "Give every segment a name."
        segments.any { it.durationSeconds != null && it.durationSeconds !in 1..3600 } ->
            "Enter an approximate time between 0:01 and 60:00."
        airtime().let { it.remaining < it.automaticCount } ->
            "Reduce the assigned times to fit the station target and leave time for each enabled segment."
        else -> null
    }

    fun json() = JSONObject().put("id", id).put("name", name).put("minutes", minutes)
        .put("icon", icon).put("instructions", instructions)
        .put("mode", mode.name).put("segments", JSONArray().apply {
            segments.forEachIndexed { index, segment ->
                put(segment.json()
                    .put("role", when (index) {
                        0 -> "opening"
                        segments.lastIndex -> "closing"
                        else -> "custom"
                    }))
            }
        })

    companion object {
        val morningCommute = StationProfile(
            id = UUID.nameUUIDFromBytes("morning-commute".toByteArray()).toString(),
            name = "Morning Commute", mode = StationMode.SCHEDULED
        )

        fun from(json: JSONObject): StationProfile {
            val rows = json.getJSONArray("segments")
            val segments = MutableList(rows.length()) { index ->
                val custom = index in 1 until rows.length() - 1
                val segment = StationSegment.from(rows.getJSONObject(index), custom && json.optBoolean("random"))
                if (custom) segment else segment.copy(randomPosition = false)
            }
            // Equal legacy weights map to automatic sharing. Preserve unequal custom proportions
            // as editable time estimates after reserving the two endpoint defaults.
            val legacy = (1 until segments.lastIndex).filter { !rows.getJSONObject(it).has("duration_seconds") }
            val weights = legacy.associateWith { rows.getJSONObject(it).optDouble("weight", 1.0) }
            val active = legacy.filter { segments[it].enabled }
            if (weights.values.distinct().size > 1 && active.isNotEmpty()) {
                val pool = json.getInt("minutes").coerceIn(5, 60) * 60 - 60
                val largest = active.maxOf { weights.getValue(it) }
                val total = active.sumOf { weights.getValue(it) / largest }
                var used = 0
                var cumulative = 0.0
                for ((position, i) in active.withIndex()) {
                    cumulative += weights.getValue(i) / largest / total
                    val end = (pool * cumulative).roundToInt().coerceIn(used + 1, pool - active.lastIndex + position)
                    segments[i] = segments[i].copy(durationSeconds = end - used)
                    used = end
                }
                for (i in legacy.filterNot { segments[it].enabled }) {
                    val seconds = (pool * (weights.getValue(i) / largest / total)).coerceIn(1.0, 3600.0).roundToInt()
                    segments[i] = segments[i].copy(durationSeconds = seconds)
                }
            }
            return StationProfile(json.getString("id"), json.getString("name"), json.getInt("minutes"),
                StationMode.valueOf(json.getString("mode")),
                segments, json.optString("icon", "◉"), json.optString("instructions"))
        }
    }
}

internal data class Airtime(val remaining: Int, val automaticCount: Int, val estimates: Map<String, Int>)
