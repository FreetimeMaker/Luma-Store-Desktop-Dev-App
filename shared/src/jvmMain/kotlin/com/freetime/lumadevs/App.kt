package com.freetime.lumadevs

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.freetime.design.templates.expressive.FreetimeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

private enum class Page(val label: String) {
    Apps("My apps"), Analytics("Analytics"), Status("Status"),
    Profile("Profile"), Funding("Funding"),
}

@Composable
fun App() {
    val systemDark = isSystemInDarkTheme()
    var dark by rememberSaveable { mutableStateOf(systemDark) }
    var page by rememberSaveable { mutableStateOf(Page.Apps) }
    var selected by remember { mutableStateOf<String?>(null) }
    var account by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        reportFailure({ error = it }) {
            DesktopAuth.sessionStatus.collect { account = DesktopAuth.currentSessionOrNull()?.user?.id }
        }
    }
    LaunchedEffect(account) {
        account?.let { watchAppStatuses(it) }
    }
    FreetimeTheme(darkTheme = dark) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Luma Store Developer Console", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                    OutlinedButton(onClick = { dark = !dark }) { Text(if (dark) "Light theme" else "Dark theme") }
                    if (account != null) OutlinedButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try { reportFailure({ error = it }) { DesktopAuth.signOut() } }
                            finally { busy = false }
                        }
                    }) { Text("Sign out") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (account == null) {
                    Text("Sign in to manage your apps", style = MaterialTheme.typography.headlineSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("GitHub", "GitLab", "Codeberg").forEach { provider ->
                            Button(enabled = !busy, onClick = {
                                busy = true
                                error = null
                                scope.launch {
                                    try { reportFailure({ error = it }) { DesktopAuth.signIn(provider.lowercase()) } }
                                    finally { busy = false }
                                }
                            }) { Text(provider) }
                        }
                    }
                    if (busy) Text("Complete sign-in in your browser.")
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Page.entries.forEach { destination ->
                            FilterChip(page == destination, { page = destination; selected = null }, { Text(destination.label) })
                        }
                    }
                    key(page, selected, account) {
                        LivePage(page, selected, onSelect = { selected = it })
                    }
                }
            }
        }
    }
}

@Composable
private fun LivePage(page: Page, selected: String?, onSelect: (String?) -> Unit) {
    var data by remember { mutableStateOf<JsonElement?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var latestSubmissions by remember { mutableStateOf<Map<String, JsonObject>>(emptyMap()) }
    var downloads by remember { mutableStateOf<JsonObject?>(null) }
    var metadataNotice by remember { mutableStateOf<String?>(null) }
    var submissionNotice by remember { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(refresh) {
        failure = null
        reportFailure({ failure = it }) {
            data = when (page) {
                Page.Apps -> if (selected == null) DesktopData.apps() else DesktopData.app(selected)
                Page.Status -> if (selected == null) DesktopData.currentAppStatuses() else DesktopData.submissionStatusHistory(selected)
                Page.Analytics -> DesktopData.analytics()
                Page.Profile -> DesktopData.profile()
                Page.Funding -> DesktopData.funding()
            } ?: JsonNull
        }
    }
    LaunchedEffect(data, refresh) {
        if (page != Page.Apps) return@LaunchedEffect
        val apps = when (val result = data) {
            is JsonArray -> result.filterIsInstance<JsonObject>()
            is JsonObject -> listOf(result)
            else -> return@LaunchedEffect
        }
        metadataNotice = null
        if (apps.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.coroutineScope {
            launch {
                reportFailure({ metadataNotice = "Latest review status could not be loaded." }) {
                    latestSubmissions = DesktopData.latestSubmissionsByApp(apps)
                }
            }
            launch {
                reportFailure({ downloads = null }) {
                    downloads = DesktopData.downloadStats()["stats"] as? JsonObject
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selected != null) OutlinedButton(onClick = { onSelect(null) }) { Text("Back") }
        OutlinedButton(onClick = { refresh++ }) { Text("Refresh") }
        if (page == Page.Apps && selected == null) {
            AddAppButton { submission ->
                val name = submission.text("name") ?: "App"
                submissionNotice = "$name was submitted for review. Track it under Status."
                refresh++
            }
        }
    }
    metadataNotice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    submissionNotice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if ((page == Page.Profile || page == Page.Funding) && data != null) {
        DeveloperSettings(page == Page.Funding, data as? JsonObject) { refresh++ }
        return
    }
    when (val result = data) {
        null -> if (failure == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        JsonNull -> Text("No record found.")
        is JsonArray -> {
            if (selected == null) OutlinedTextField(search, { search = it }, label = { Text("Search ${page.label.lowercase()}") }, singleLine = true)
            val entries = result.filterIsInstance<JsonObject>().filter { row ->
                listOf("name", "developer_name", "package_name", "status").any { row.text(it).orEmpty().contains(search, ignoreCase = true) }
            }
            if (entries.isEmpty()) Text(if (search.isBlank()) "No results." else "No matches.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries) { row ->
                    val title = row.text("name") ?: row.text("status") ?: "Update"
                    DataCard(title, row.text("short_description") ?: row.text("review_message") ?: row.text("status")) {
                        if (page == Page.Apps) AppListingMetadata(row, latestSubmissions[row.text("id")], downloads)
                        if (selected == null) row.text("id")?.let { id -> TextButton(onClick = { onSelect(id) }) { Text("Details") } }
                        else row.text("created_at")?.let { Text(it) }
                    }
                }
            }
        }
        is JsonObject -> if (page == Page.Analytics) {
            AnalyticsScreen(result)
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (page) {
                Page.Apps -> item {
                    DataCard(result.text("name") ?: "App", result.text("description") ?: result.text("short_description")) {
                        result.text("package_name")?.let { Text(it) }
                        AppListingMetadata(result, latestSubmissions[result.text("id")], downloads)
                        result.text("license_type")?.let { Text("License: $it") }
                        (result.text("repo_url") ?: result.text("source_code_url"))?.let { Text("Repository: $it") }
                        AppMetadataEditor(result) { refresh++ }
                    }
                }
                else -> items(result.entries.toList()) { (label, value) ->
                    if (value != JsonNull) DataCard(label.replace('_', ' '), (value as? JsonPrimitive)?.contentOrNull ?: value.toString()) {}
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun AppListingMetadata(app: JsonObject, submission: JsonObject?, downloads: JsonObject?) {
    val archived = !app.text("archived_at").isNullOrBlank() || app.text("status").equals("Archived", true)
    val status = if (archived) "Archived" else "Published"
    val review = submission?.text("status")
    val version = app.text("version")?.takeIf { it.isNotBlank() } ?: "No version"
    val category = app.text("category") ?: submission?.text("category") ?: app.text("subcategory")
    val platform = app.text("platform") ?: submission?.text("platform")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Badge { Text(status) }
        if (!archived && review != null && review !in setOf("Approved", "Archived")) {
            val label = if (review == "Pending" || review == "In Review") "In review" else review
            val submittedVersion = submission.text("version")?.takeIf { it.isNotBlank() }
            Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                Text(listOfNotNull(label, submittedVersion).joinToString(" · "))
            }
        }
    }
    Text(listOfNotNull(category, platform, version).joinToString(" · "))
    app.text("version_code")?.let { Text("Version code: $it") }
    (downloads?.get(app.text("id")) as? JsonObject)?.text("total")?.let { Text("All-time downloads: $it") }
}

@Composable
private fun DataCard(title: String, description: String?, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            description?.let { Text(it) }
            content()
        }
    }
}

private fun JsonObject.text(field: String): String? = (get(field) as? JsonPrimitive)?.contentOrNull

private suspend fun reportFailure(onError: (String) -> Unit, block: suspend () -> Unit) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        onError(when (failure) {
            is DesktopSignInException -> failure.message.orEmpty()
            is DesktopData.WebsiteException -> if (failure.deploymentDisabled) "The website deployment is currently unavailable." else "Website request failed (HTTP ${failure.status})."
            is IllegalStateException -> failure.message?.takeIf { it.startsWith("Supabase is not configured") } ?: "The operation could not be completed."
            else -> "The operation could not be completed. Check your connection and try again."
        })
    }
}
