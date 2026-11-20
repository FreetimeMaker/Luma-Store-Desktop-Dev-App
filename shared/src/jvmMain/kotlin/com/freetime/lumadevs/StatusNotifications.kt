@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.freetime.lumadevs

import com.freetime.notifications.DesktopNotificationPoster
import com.freetime.notifications.NotificationCategory
import com.freetime.notifications.NotificationConfiguration
import com.freetime.notifications.NotificationEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.minutes

// Freetime 4.0.1 has no public direct-delivery or custom polling-source API.
// Keep its internal JVM poster behind this adapter so the rest of the app is independent of it.
internal suspend fun watchAppStatuses(account: String) {
    val poster = DesktopNotificationPoster("Luma Store Developer Console")
    val configuration = NotificationConfiguration()
    var previous = emptyMap<String, String>()
    try {
        while (coroutineContext.isActive) {
            try {
                val current = mutableMapOf<String, String>()
                val rows = DesktopData.currentAppStatuses()
                coroutineContext.ensureActive()
                if (DesktopAuth.currentSessionOrNull()?.user?.id != account) return
                for (row in rows.filterIsInstance<JsonObject>()) {
                    val id = row.field("id") ?: continue
                    val status = row.field("status") ?: continue
                    current[id] = status
                    val old = previous[id] ?: continue
                    if (old != status) poster.post(
                        configuration, NotificationCategory.DEVELOPER_MESSAGES, "luma-status",
                        NotificationEntry(id, row.field("name") ?: "Luma Store app", "$old → $status"),
                    )
                }
                previous = current
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Retain the last successful baseline and try again on the next poll.
            }
            delay(1.minutes)
        }
    } finally {
        poster.close()
    }
}

private fun JsonObject.field(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull
