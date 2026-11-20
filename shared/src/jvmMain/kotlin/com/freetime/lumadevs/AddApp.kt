package com.freetime.lumadevs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.net.URI

@Composable
fun AddAppButton(onAdded: (JsonObject) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Button(onClick = { open = true }) { Text("Add app") }
    if (open) {
        AddAppDialog(onClose = { open = false }, onAdded = onAdded)
    }
}

private val addAppFields = linkedMapOf(
    "name" to "App name",
    "repo_url" to "Public source repository URL",
    "download_url" to "Hosted Android APK URL",
    "short_description" to "Short description",
    "description" to "Full description",
)

@Composable
private fun AddAppDialog(onClose: () -> Unit, onAdded: (JsonObject) -> Unit) {
    var fields by remember { mutableStateOf(emptyMap<String, String>()) }
    var options by remember { mutableStateOf<AddAppOptions?>(null) }
    var category by remember { mutableStateOf("") }
    var license by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val addedCallback by rememberUpdatedState(onAdded)
    val closeDialog by rememberUpdatedState(onClose)

    LaunchedEffect(loadAttempt) {
        error = null
        try {
            options = loadAddAppOptions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Could not load categories and licenses."
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) closeDialog() },
        title = { Text("Add app") },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Submit an open-source Android app for review. Use a public GitHub, GitLab, or Codeberg repository and a direct link to your hosted APK.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                addAppFields.forEach { (key, label) ->
                    OutlinedTextField(
                        value = fields[key].orEmpty(),
                        onValueChange = { fields = fields + (key to it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(label) },
                        enabled = !saving,
                        singleLine = key != "description",
                        maxLines = if (key == "description") 4 else 1,
                    )
                }
                val loaded = options
                if (loaded != null) {
                    AddAppPicker("Category", category, loaded.categories, !saving) { category = it }
                    AddAppPicker("Open-source license", license, loaded.licenses, !saving) { license = it }
                } else if (error == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    TextButton(onClick = { loadAttempt++ }) { Text("Retry loading categories and licenses") }
                }
            }
        },
        confirmButton = {
            Button(enabled = options != null && !saving, onClick = {
                if (saving) return@Button
                val submission = try {
                    buildAddAppSubmission(fields, category, license)
                } catch (e: IllegalArgumentException) {
                    error = e.message
                    return@Button
                }
                saving = true
                error = null
                scope.launch {
                    val saved = try {
                        DesktopData.saveSubmission(submission)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "The app could not be submitted."
                        return@launch
                    } finally {
                        saving = false
                    }
                    closeDialog()
                    addedCallback(saved)
                }
            }) { Text(if (saving) "Submitting…" else "Submit for review") }
        },
        dismissButton = {
            TextButton(onClick = closeDialog, enabled = !saving) { Text("Cancel") }
        },
    )
}

@Composable
private fun AddAppPicker(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text(options.firstOrNull { it.first == selected }?.second?.let { "$label: $it" } ?: "Select $label")
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { (value, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = {
                    expanded = false
                    onSelected(value)
                })
            }
        }
    }
}

private data class AddAppOptions(
    val categories: List<Pair<String, String>>,
    val licenses: List<Pair<String, String>>,
)

private suspend fun loadAddAppOptions(): AddAppOptions {
    val client = LumaSupabase.client
    val categories = client.from("store_categories").select(Columns.raw("name"))
        .decodeList<JsonObject>().mapNotNull { row ->
            row["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        }.distinct().sorted().map { it to it }
    val licenses = client.from("store_license_types").select(Columns.raw("name,display_name")) {
        filter { eq("open_source", true) }
    }.decodeList<JsonObject>().mapNotNull { row ->
        val name = row["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        if (name == "Proprietary") return@mapNotNull null
        val title = row["display_name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { name }
        name to title
    }.sortedBy { it.second }
    check(categories.isNotEmpty() && licenses.isNotEmpty()) { "No categories or open-source licenses are available." }
    return AddAppOptions(categories, licenses)
}

private fun buildAddAppSubmission(fields: Map<String, String>, category: String, license: String): JsonObject {
    fun field(key: String) = fields[key].orEmpty().trim()
    val repo = field("repo_url")
    val apk = field("download_url")
    val repoUri = parseAddAppUrl(repo)
    val apkUri = parseAddAppUrl(apk)
    require(field("name").isNotEmpty()) { "Enter the app name." }
    require(repoUri != null && repoUri.scheme.equals("https", ignoreCase = true) &&
        repoUri.host?.lowercase() in setOf("github.com", "gitlab.com", "codeberg.org") &&
        repoUri.rawPath.split('/').count(String::isNotEmpty) >= 2) {
        "Enter an HTTPS repository URL on GitHub, GitLab, or Codeberg."
    }
    require(apkUri?.host != null && apkUri.scheme?.lowercase() in setOf("http", "https")) {
        "Enter a valid HTTP or HTTPS URL for the hosted Android APK."
    }
    require(category.isNotBlank()) { "Select a category." }
    require(license.isNotBlank()) { "Select an open-source license." }

    // Source of truth: POST in /tmp/luma-submit-route.ts. The route canonicalizes the
    // repository, derives platform/download_url from platforms, and sets Pending itself.
    return buildJsonObject {
        put("name", field("name"))
        for (key in listOf("short_description", "description")) {
            field(key).takeIf(String::isNotEmpty)?.let { put(key, it) }
        }
        put("repo_url", repo)
        put("separate_platform_repos", false)
        put("closed_source", false)
        put("license_type", license)
        put("category", category)
        put("categories", JsonArray(listOf(JsonPrimitive(category))))
        put("platforms", JsonArray(listOf(buildJsonObject {
            put("platform", "Android")
            put("packageType", "apk")
            put("downloadUrl", apk)
        })))
    }
}

private fun parseAddAppUrl(value: String): URI? = try {
    URI(value)
} catch (_: java.net.URISyntaxException) {
    null
}
