package sschoi.docdog.viewer.data

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

@Serializable
data class HistoryItem(
    val fileName: String,
    val storagePath: String,
    val expireOption: ExpireOption,
    val createdAt: Long = System.currentTimeMillis()
)

enum class ExpireOption(val label: String, val duration: Duration) {
    ONE_HOUR("1시간", 1.hours),
    SEVEN_DAYS("7일", 7.days),
    THIRTY_DAYS("30일", 30.days)
}
