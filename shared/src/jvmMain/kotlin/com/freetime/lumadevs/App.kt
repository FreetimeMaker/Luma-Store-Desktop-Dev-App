package com.freetime.lumadevs

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.freetime.design.templates.expressive.FreetimeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

private enum class Page(val label: String) {
    Discover("Discover"), Apps("My apps"), Analytics("Analytics"), Status("Status"),
    Profile("Profile"), Funding("Funding"),
}

@Composable
fun App() {
    val systemDark = isSystemInDarkTheme()
    var dark by rememberSaveable { mutableStateOf(systemDark) }
    var page by rememberSaveable { mutableStateOf(Page.Discover) }
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
    FreetimeTheme(darkTheme = dark) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Luma Store", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                    OutlinedButton(onClick = { dark = !dark }) { Text(if (dark) "Light theme" else "Dark theme") }
                    if (account != null) OutlinedButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try { reportFailure({ error = it }) { DesktopAuth.signOut() } }
                            finally { busy = false }
                        }
                    }) { Text("Sign out") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Page.entries.forEach { destination ->
                        FilterChip(page == destination, { page = destination; selected = null }, { Text(destination.label) })
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (page != Page.Discover && account == null) {
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
    var search by rememberSaveable { mutableStateOf("") }
    val open = LocalUriHandler.current
    LaunchedEffect(refresh) {
        failure = null
        reportFailure({ failure = it }) {
            data = when (page) {
                Page.Discover -> if (selected == null) DesktopData.catalog().getValue("apps") else DesktopData.catalogApp(selected)
                Page.Apps -> if (selected == null) DesktopData.submissions() else DesktopData.submission(selected)
                Page.Status -> if (selected == null) DesktopData.submissions() else DesktopData.submissionStatusHistory(selected)
                Page.Analytics -> DesktopData.downloadStats()
                Page.Profile -> DesktopData.profile()
                Page.Funding -> DesktopData.funding()
            } ?: JsonNull
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selected != null) OutlinedButton(onClick = { onSelect(null) }) { Text("Back") }
        OutlinedButton(onClick = { refresh++ }) { Text("Refresh") }
        if (page == Page.Apps && selected == null) {
            SubmissionActions(null, onSaved = { refresh++ })
        }
    }
    failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
                        if (selected == null) row.text("id")?.let { id -> TextButton(onClick = { onSelect(id) }) { Text("Details") } }
                        else row.text("created_at")?.let { Text(it) }
                    }
                }
            }
        }
        is JsonObject -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (page) {
                Page.Discover -> {
                    val app = result["app"] as? JsonObject ?: return@LazyColumn
                    item { DataCard(app.text("name") ?: "App", app.text("description")) { app.text("developer_name")?.let { Text(it) } } }
                    items((result["platforms"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()) { platform ->
                        DataCard(platform.text("platform") ?: "Release", platform.text("package_type")) {
                            platform.text("download_url")?.takeIf { it.startsWith("https://") }?.let { url ->
                                TextButton(onClick = { try { open.openUri(url) } catch (_: Exception) { failure = "Could not open the download link." } }) { Text("Open download") }
                            }
                        }
                    }
                }
                Page.Analytics -> {
                    val stats = (result["stats"] as? JsonObject).orEmpty().values.filterIsInstance<JsonObject>()
                    item { DataCard("Lifetime downloads", stats.sumOf { it.text("total")?.toLongOrNull() ?: 0 }.toString()) {} }
                }
                Page.Apps -> item {
                    DataCard(result.text("name") ?: "Submission", result.text("description")) {
                        result.text("status")?.let { Text(it) }
                        SubmissionActions(result, onSaved = { refresh++ }, onDeleted = { onSelect(null) })
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
private fun SubmissionActions(row: JsonObject?, onSaved: () -> Unit, onDeleted: () -> Unit = {}) {
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val id = row?.text("id")
    val draft = row == null || row.text("status") == "draft"
    if (draft) Button(onClick = { editing = true; error = null }) { Text(if (id == null) "New draft" else "Edit draft") }
    if (id != null) OutlinedButton(onClick = { deleting = true; error = null }) { Text("Delete or archive") }
    if (editing) {
        var name by remember { mutableStateOf(row?.text("name").orEmpty()) }
        var description by remember { mutableStateOf(row?.text("description").orEmpty()) }
        var repository by remember { mutableStateOf(row?.text("repo_url").orEmpty()) }
        AlertDialog(
            onDismissRequest = { if (!busy) editing = false },
            title = { Text("Submission draft") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("App name") })
                OutlinedTextField(description, { description = it }, label = { Text("Description") })
                OutlinedTextField(repository, { repository = it }, label = { Text("Repository URL") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { Button(enabled = !busy && name.isNotBlank(), onClick = {
                busy = true
                error = null
                scope.launch {
                    try { reportFailure({ error = it }) {
                        DesktopData.saveSubmission(buildJsonObject {
                            row?.forEach { (key, value) -> put(key, value) }
                            put("name", name.trim()); put("description", description.trim()); put("repo_url", repository.trim())
                        }, editingId = id, draft = true, draftStep = row?.text("draft_step")?.toIntOrNull() ?: 1)
                        editing = false
                        onSaved()
                    } } finally { busy = false }
                }
            }) { Text("Save draft") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { editing = false }) { Text("Cancel") } },
        )
    }
    if (deleting && id != null) AlertDialog(
        onDismissRequest = { if (!busy) deleting = false },
        title = { Text("Delete or archive submission?") },
        text = { Text(error ?: "The server decides whether this submission can be deleted or must be archived.") },
        confirmButton = { Button(enabled = !busy, onClick = {
            busy = true
            error = null
            scope.launch {
                try { reportFailure({ error = it }) { DesktopData.deleteSubmission(id); deleting = false; onDeleted() } }
                finally { busy = false }
            }
        }) { Text("Confirm") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { deleting = false }) { Text("Cancel") } },
    )
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
            is DesktopData.WebsiteException -> if (failure.deploymentDisabled) "The website deployment is currently unavailable." else "Website request failed (HTTP ${failure.status})."
            is IllegalStateException -> failure.message?.takeIf { it.startsWith("Supabase is not configured") } ?: "The operation could not be completed."
            else -> "The operation could not be completed. Check your connection and try again."
        })
    }
}
