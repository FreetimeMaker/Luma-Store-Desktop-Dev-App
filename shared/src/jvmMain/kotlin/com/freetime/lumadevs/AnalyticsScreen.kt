package com.freetime.lumadevs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.time.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnalyticsScreen(data: JsonObject) {
    val analytics = remember(data) { AnalyticsSnapshot(data) }
    var days by remember { mutableStateOf(30) }
    var appFilter by remember { mutableStateOf<String?>(null) }
    var platformFilter by remember { mutableStateOf<String?>(null) }
    val selectedApp = analytics.apps.find { it.id == appFilter }
    val appId = selectedApp?.id
    val platform = platformFilter?.takeIf { it in analytics.platforms }
    val today = LocalDate.now()
    val dates = remember(today, days) { List(days) { today.minusDays((days - 1 - it).toLong()).toString() } }
    val timelines = remember(analytics, dates, appId, platform) {
        val rows = analytics.daily.filter { (appId == null || it.appId == appId) && (platform == null || it.platform == platform) }
        val byApp = rows.groupBy { it.appId }
        val byPlatform = rows.groupBy { it.platform }
        AnalyticsTimelines(
            analyticsSeries("", dates, rows),
            analytics.apps.filter { appId == null || it.id == appId }.mapNotNull { app ->
                analyticsSeries(app.label, dates, byApp[app.id].orEmpty()).takeIf { it.total > 0 || appId == app.id }
            },
            analytics.platforms.filter { platform == null || it == platform }.mapNotNull { name ->
                analyticsSeries(name, dates, byPlatform[name].orEmpty()).takeIf { it.total > 0 || platform == name }
            },
        )
    }
    // Like the website, the all-time platform breakdown only follows the app filter.
    val platformTotals = remember(analytics, appId) {
        val scoped = analytics.appPlatforms.filter { appId == null || it.appId == appId }.groupBy { it.platform }
        analytics.platforms.map { it to scoped[it].orEmpty().sumOf { row -> row.downloads } }
    }
    val format = remember { NumberFormat.getIntegerInstance() }
    val period = "Last $days days"
    val topApp = analytics.apps.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AnalyticsPanel("Analytics", "Private developer analytics") {
                AnalyticsMuted("Download activity for your published apps and clicks on your funding links. Only your developer account can access these statistics.")
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalyticsFilter("Period", days, listOf(7, 30, 90).map { it to "Last $it days" }) { days = it }
                AnalyticsFilter("App", appId, listOf(null to "All apps") + analytics.apps.map { it.id to it.label }) { appFilter = it }
                AnalyticsFilter("Platform", platform, listOf(null to "All platforms") + analytics.platforms.map { it to it }) { platformFilter = it }
            }
        }
        item {
            AnalyticsMetrics(listOf(
                "All-time downloads" to format.format(analytics.totalDownloads),
                period to format.format(timelines.overall.total),
                "Top app" to (topApp?.let { "${it.label} · ${format.format(it.downloads)}" } ?: "—"),
            ))
        }
        item {
            AnalyticsPanel(period, "Downloads") {
                AnalyticsMuted("${format.format(timelines.overall.total)} total")
                AnalyticsBars(dates, timelines.overall, timelines.overall.peak, format)
            }
        }
        analyticsTimeline("App timeline", "Downloads per app · last $days days", dates, timelines.apps, format)
        analyticsTimeline("Platform timeline", "Downloads per platform · last $days days", dates, timelines.platforms, format)
        item {
            AnalyticsPanel(selectedApp?.let { "${it.label} · downloads by platform" } ?: "Downloads by platform", "Platform analytics") {
                AnalyticsMuted(if (selectedApp == null) "All-time totals across all of your published apps." else "All-time platform totals for the selected app.")
                if (platformTotals.isEmpty()) AnalyticsMuted("No platform downloads yet.")
                else AnalyticsMetrics(platformTotals.map { (name, count) -> name to format.format(count) })
            }
        }
        item {
            AnalyticsTable("Downloads by app", analytics.apps.map { it.label to it.downloads }, "No published apps yet.", format)
        }
        item {
            AnalyticsTable("Downloads by platform", platformTotals, "No platform downloads yet.", format)
        }
        item {
            AnalyticsTable("Downloads by app & platform", analytics.appPlatforms.map { "${it.label} · ${it.platform}" to it.downloads }, "No per-platform app downloads yet.", format)
        }
        item {
            AnalyticsTable("Funding link clicks", analytics.funding, "No funding link clicks yet.", format)
        }
    }
}

@Composable
private fun <T> AnalyticsFilter(label: String, selected: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        AnalyticsMuted(label)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.widthIn(max = 240.dp)) {
                Text(options.firstOrNull { it.first == selected }?.second.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(" ▾")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                options.forEach { (value, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        onSelect(value)
                        expanded = false
                    })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnalyticsMetrics(metrics: List<Pair<String, String>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
        metrics.forEach { (label, value) ->
            FilledTonalAnalyticsMetric(label, value, Modifier.widthIn(min = 155.dp).weight(1f))
        }
    }
}

@Composable
private fun FilledTonalAnalyticsMetric(label: String, value: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun AnalyticsPanel(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (subtitle != null) AnalyticsMuted(subtitle)
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun AnalyticsMuted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AnalyticsTable(title: String, rows: List<Pair<String, Long>>, empty: String, format: NumberFormat) {
    AnalyticsPanel(title) {
        if (rows.isEmpty()) AnalyticsMuted(empty)
        rows.forEachIndexed { index, (label, value) ->
            if (index != 0) HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(format.format(value), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun LazyListScope.analyticsTimeline(subtitle: String, title: String, dates: List<String>, series: List<AnalyticsSeries>, format: NumberFormat) {
    val peak = series.maxOfOrNull { it.peak } ?: 1L
    item {
        AnalyticsPanel(title, subtitle) {
            if (series.isEmpty()) AnalyticsMuted("No downloads in this period.")
        }
    }
    items(series) { item ->
        AnalyticsPanel(item.label) {
            AnalyticsMuted("${format.format(item.total)} downloads")
            AnalyticsBars(dates, item, peak, format)
        }
    }
}

@Composable
private fun AnalyticsBars(dates: List<String>, series: AnalyticsSeries, peak: Long, format: NumberFormat) {
    var hovered by remember(dates, series) { mutableStateOf<Int?>(null) }
    val colors = MaterialTheme.colorScheme
    Text(
        hovered?.let { index ->
            val count = series.values[index]
            "${dates[index]} · ${format.format(count)} ${if (count == 1L) "download" else "downloads"}"
        } ?: "Hover over a day for details",
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceVariant,
    )
    Canvas(Modifier.fillMaxWidth().height(148.dp).pointerInput(dates, series) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                when (event.type) {
                    PointerEventType.Exit -> hovered = null
                    PointerEventType.Enter, PointerEventType.Move -> {
                        val x = event.changes.firstOrNull()?.position?.x
                        if (x != null && size.width > 0) {
                            hovered = (x / size.width * dates.size).toInt().coerceIn(dates.indices)
                        }
                    }
                }
            }
        }
    }) {
        val slot = size.width / dates.size
        val gap = minOf(3.dp.toPx(), slot * 0.25f)
        series.values.forEachIndexed { index, count ->
            val fraction = if (count == 0L) 0.02f else (count.toFloat() / peak).coerceIn(0.05f, 1f)
            val height = size.height * fraction
            drawRect(
                color = when {
                    index == hovered -> colors.tertiary
                    count == 0L -> colors.outlineVariant
                    else -> colors.primary
                },
                topLeft = Offset(index * slot + gap / 2, size.height - height),
                size = Size(slot - gap, height),
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        listOf(0, dates.lastIndex / 2, dates.lastIndex).forEach { index ->
            Text(dates[index].drop(5), fontSize = 10.sp, color = colors.onSurfaceVariant)
        }
    }
}

private data class AnalyticsApp(val id: String, val label: String, val downloads: Long)
private data class AnalyticsAppPlatform(val appId: String, val label: String, val platform: String, val downloads: Long)
private data class AnalyticsDay(val day: String, val appId: String, val platform: String, val downloads: Long)
private data class AnalyticsSeries(val label: String, val values: List<Long>) {
    val total = values.sum()
    val peak = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
}
private data class AnalyticsTimelines(val overall: AnalyticsSeries, val apps: List<AnalyticsSeries>, val platforms: List<AnalyticsSeries>)

private fun analyticsSeries(label: String, dates: List<String>, rows: List<AnalyticsDay>): AnalyticsSeries {
    val totals = mutableMapOf<String, Long>()
    rows.forEach { totals[it.day] = (totals[it.day] ?: 0L) + it.downloads }
    return AnalyticsSeries(label, dates.map { totals[it] ?: 0L })
}

// Wire names and ordering follow the website Analytics types in luma-analytics.tsx
// for luma_my_developer_analytics. In particular, apps[0] is the website's top app.
private class AnalyticsSnapshot(data: JsonObject) {
    val totalDownloads = data.analyticsCount("total_downloads")
    val apps = data.analyticsRows("apps").mapNotNull { row ->
        row.analyticsText("id")?.let { AnalyticsApp(it, row.analyticsAppLabel(), row.analyticsCount("downloads")) }
    }
    val appPlatforms = data.analyticsRows("app_platforms").mapNotNull { row ->
        val appId = row.analyticsText("app_id") ?: return@mapNotNull null
        val platform = row.analyticsText("platform") ?: return@mapNotNull null
        AnalyticsAppPlatform(appId, row.analyticsAppLabel(), platform, row.analyticsCount("downloads"))
    }
    val daily = data.analyticsRows("daily").mapNotNull { row ->
        val day = row.analyticsText("download_day") ?: return@mapNotNull null
        val appId = row.analyticsText("app_id") ?: return@mapNotNull null
        val platform = row.analyticsText("platform") ?: return@mapNotNull null
        AnalyticsDay(day.take(10), appId, platform, row.analyticsCount("downloads"))
    }
    val platforms = (data.analyticsRows("platforms").mapNotNull { it.analyticsText("platform") } + daily.map { it.platform }).distinct().sorted()
    val funding = data.analyticsRows("funding").mapNotNull { row ->
        row.analyticsText("provider")?.let { it to row.analyticsCount("clicks") }
    }
}

private fun JsonObject.analyticsRows(key: String) = (get(key) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
private fun JsonObject.analyticsText(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.analyticsCount(key: String) = (get(key) as? JsonPrimitive)?.longOrNull ?: 0L
private fun JsonObject.analyticsAppLabel() = analyticsText("name")?.takeIf { it.isNotEmpty() }
    ?: analyticsText("package_name")?.takeIf { it.isNotEmpty() } ?: "App"
