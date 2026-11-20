package com.freetime.lumadevs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.net.URI

@Composable
internal fun AppMetadataEditor(app: JsonObject, onSaved: () -> Unit) {
    val appId = app.metadataText("id")
    var open by remember(appId) { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = appId.isNotBlank()) { Text("Edit metadata") }
    if (open) key(appId) {
        MetadataDialog(app, onClose = { open = false }, onSaved = onSaved)
    }
}

@Composable
private fun MetadataDialog(app: JsonObject, onClose: () -> Unit, onSaved: () -> Unit) {
    var target by remember { mutableStateOf<MetadataTarget?>(null) }
    var form by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val savedCallback by rememberUpdatedState(onSaved)

    LaunchedEffect(app) {
        try {
            val loaded = loadMetadataTarget(app)
            form = initialMetadataFields(app, loaded.submission)
            categories = initialCategories(app, loaded.submission)
            target = loaded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Metadata could not be loaded."
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        title = { Text("Edit app metadata") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saving sends the edit back to Pending review. The published app stays live until approval.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val loaded = target
                if (loaded == null) {
                    if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    Text("Android listing text and images are refreshed from GitHub Fastlane when you save.")
                    metadataFields.forEach { (field, label) ->
                        OutlinedTextField(form[field].orEmpty(), { form = form + (field to it) },
                            modifier = Modifier.fillMaxWidth(), label = { Text(label) },
                            enabled = !saving, singleLine = true)
                    }
                    Text("Open-source license")
                    loaded.licenses.forEach { (name, label) ->
                        FilterChip(selected = form["license_type"] == name,
                            onClick = { form = form + ("license_type" to name) },
                            label = { Text(label) }, enabled = !saving)
                    }
                    Text("Categories (the first selected is primary)")
                    (loaded.categories + categories).distinct().forEach { category ->
                        FilterChip(selected = category in categories,
                            onClick = { categories = if (category in categories) categories - category else categories + category },
                            label = { Text(category) }, enabled = !saving)
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = target != null && !saving, onClick = {
                val loaded = target ?: return@Button
                val fields = form
                val selected = categories
                saving = true
                error = null
                scope.launch {
                    try {
                        saveMetadata(loaded, fields, selected)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "App metadata could not be saved."
                        return@launch
                    } finally {
                        saving = false
                    }
                    onClose()
                    savedCallback()
                }
            }) { Text(if (saving) "Saving…" else "Save for review") }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !saving) { Text("Cancel") } },
    )
}

private val metadataFields = linkedMapOf(
    "version" to "Version", "icon_url" to "Icon URL", "repo_url" to "GitHub source repository",
    "download_url" to "Android APK URL", "package_name" to "Android package name",
    "version_code" to "Android versionCode", "author_name" to "Author name",
    "author_email" to "Author email", "author_website" to "Author website",
    "website_url" to "App website", "issue_tracker_url" to "Issue tracker URL",
    "translation_url" to "Translation URL", "changelog_url" to "Changelog URL",
)
private val optionalMetadataFields = listOf("author_name", "author_email", "author_website", "website_url",
    "issue_tracker_url", "translation_url", "changelog_url")
private val editableMetadataStatuses = setOf("Draft", "Rejected", "Approved", "Changes Requested")

private data class MetadataTarget(
    val owner: String,
    val submission: JsonObject,
    val categories: List<String>,
    val licenses: List<Pair<String, String>>,
)

private fun metadataSession(): UserSession = checkNotNull(LumaSupabase.client.auth.currentSessionOrNull()) {
    "Sign in to edit app metadata."
}

private suspend fun loadMetadataTarget(app: JsonObject): MetadataTarget {
    val owner = checkNotNull(metadataSession().user?.id) { "Sign in to edit app metadata." }
    val id = app.metadataText("id")
    check(id.isNotBlank() && app.metadataText("developer_id") == owner) { "You do not own this published app." }
    val client = LumaSupabase.client
    val submission = checkNotNull(DesktopData.latestLinkedSubmission(id, app.metadataText("luma_submission_id").ifBlank { null })) {
        "No owned submission is linked to this published app."
    }
    val status = submission.metadataText("status")
    check(status in editableMetadataStatuses) { "This submission cannot be edited in its current state ($status)." }
    val categories = client.from("store_categories").select(Columns.raw("name"))
        .decodeList<JsonObject>().map { it.metadataText("name").trim() }.filter(String::isNotEmpty).distinct().sorted()
    val licenses = client.from("store_license_types").select(Columns.raw("name,display_name")) {
        filter { eq("open_source", true) }
    }.decodeList<JsonObject>().mapNotNull {
        val name = it.metadataText("name").ifBlank { return@mapNotNull null }
        name to it.metadataText("display_name").ifBlank { name }
    }.sortedBy { it.second }
    return MetadataTarget(owner, submission, categories, licenses)
}

private fun initialMetadataFields(app: JsonObject, submission: JsonObject): Map<String, String> {
    fun value(key: String) = (submission[key] ?: app[key]).metadataString()
    val artifact = ((submission["platforms"] ?: app["platforms"]) as? JsonArray)
        ?.filterIsInstance<JsonObject>()?.firstOrNull {
            it.metadataText("platform") == "Android" &&
                (it.metadataText("packageType").ifBlank { it.metadataText("package_type") }.ifBlank { "apk" }) == "apk"
        }
    val sharedRepo = value("source_code_url").ifBlank { value("repo_url") }.ifBlank { value("link") }
    val repo = if (value("separate_platform_repos") == "true") {
        artifact?.metadataText("repoUrl").orEmpty().ifBlank { artifact?.metadataText("repo_url").orEmpty() }
            .ifBlank { sharedRepo }
    } else sharedRepo
    return (metadataFields.keys + "license_type").associateWith(::value) + mapOf(
        "repo_url" to repo,
        "download_url" to artifact?.metadataText("downloadUrl").orEmpty()
            .ifBlank { artifact?.metadataText("download_url").orEmpty() }.ifBlank { value("download_url") },
    )
}

private fun initialCategories(app: JsonObject, submission: JsonObject): List<String> =
    ((submission["categories"] ?: app["categories"]) as? JsonArray).orEmpty()
        .map { it.metadataString().trim() }.filter(String::isNotEmpty).distinct().ifEmpty {
            listOfNotNull((submission["category"] ?: app["category"]).metadataString().trim().takeIf(String::isNotEmpty))
        }

private val metadataHttp by lazy {
    HttpClient(CIO) {
        // Never forward the developer's Supabase or forge token to a redirect destination.
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 20_000 }
    }
}

/* Wire source: FreetimeMaker/Luma-Store-Website app/dashboard/apps/[id]/metadata/page.tsx, save().
 * Keep the endpoint, envelope, field casing and nulls aligned with that implementation. */
private suspend fun saveMetadata(target: MetadataTarget, fields: Map<String, String>, categories: List<String>) {
    fun field(key: String) = fields[key].orEmpty().trim()
    val id = target.submission.metadataText("id")
    val status = target.submission.metadataText("status")
    check(id.isNotBlank() && status in editableMetadataStatuses) { "No editable submission was found." }
    check(metadataSession().user?.id == target.owner) { "Your login changed. Reopen the editor." }
    val icon = requireMetadataUrl(field("icon_url"), "App icon")
    val apk = requireMetadataUrl(field("download_url"), "Android APK")
    val repo = requireMetadataUrl(field("repo_url"), "Source repository")
    val version = field("version").also { require(it.isNotEmpty()) { "Version is required." } }
    val license = field("license_type").also { value ->
        require(target.licenses.any { it.first == value }) { "Select an open-source license." }
    }
    require(categories.isNotEmpty()) { "Select at least one category." }
    val packageName = field("package_name")
    require(Regex("([A-Za-z][A-Za-z0-9_]*\\.)+[A-Za-z][A-Za-z0-9_]*").matches(packageName)) {
        "Android requires a valid package name."
    }
    val versionCode = field("version_code").takeIf { it.matches(Regex("[0-9]+")) }?.toLongOrNull()
    require(versionCode != null && versionCode in 1..9_007_199_254_740_991L) { "Android requires a positive versionCode." }
    val optional = optionalMetadataFields.associateWith { key ->
        field(key).takeIf(String::isNotEmpty)?.also { value ->
            if (key !in setOf("author_name", "author_email")) requireMetadataUrl(value, metadataFields.getValue(key))
        }
    }
    val listing = withTimeoutOrNull(90_000) { fetchMetadataFastlane(repo, versionCode) }
        ?: error("Fastlane lookup timed out. Try again.")
    val separateRepos = target.submission["separate_platform_repos"] == JsonPrimitive(true)
    val submission = buildJsonObject {
        put("name", listing.getValue("title"))
        put("short_description", listing.getValue("shortDescription"))
        put("description", listing.getValue("fullDescription"))
        put("icon_url", icon)
        put("category", categories.first())
        put("categories", JsonArray(categories.map(::JsonPrimitive)))
        put("subcategory", JsonNull)
        put("license_type", license)
        put("version", version)
        put("package_name", packageName)
        put("version_code", versionCode)
        put("changelog", listing.getValue("changelog"))
        put("screenshots", listing.getValue("screenshots"))
        putJsonArray("localized_metadata") {
            add(JsonObject(listing + ("locale" to JsonPrimitive("en-US"))))
            (target.submission["localized_metadata"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                .filter { it.metadataText("locale").lowercase().let { locale -> locale != "en" && !locale.startsWith("en-") } }
                .forEach { add(it) }
        }
        put("platform", "Android")
        putJsonArray("platforms") {
            add(buildJsonObject {
                put("platform", "Android")
                put("packageType", "apk")
                put("downloadUrl", apk)
                if (separateRepos) put("repoUrl", repo)
                put("metadata", listing)
            })
        }
        put("separate_platform_repos", separateRepos)
        put("linux_package_base", JsonNull)
        put("download_url", apk)
        put("link", repo)
        put("repo_url", repo)
        put("source_code_url", repo)
        put("closed_source", false)
        optional.forEach { (key, value) -> put(key, value?.let(::JsonPrimitive) ?: JsonNull) }
        put("review_message", JsonNull)
    }
    val session = metadataSession()
    check(session.user?.id == target.owner && session.accessToken.isNotBlank()) { "Your login changed. Reopen the editor." }
    val response = metadataHttp.post("https://luma.free-time.me/api/luma/submissions") {
        bearerAuth(session.accessToken)
        contentType(ContentType.Application.Json)
        session.providerToken?.takeIf(String::isNotBlank)?.let { token ->
            val providerHeader = when (session.user?.appMetadata?.metadataText("provider")) {
                "gitlab" -> "X-GitLab-Token"
                "custom:codeberg" -> "X-Codeberg-Token"
                else -> "X-GitHub-Token"
            }
            header(providerHeader, token)
        }
        setBody(buildJsonObject {
            put("submission", submission)
            put("editingId", id)
            put("editingStatus", status)
        }.toString())
    }
    val result = parseMetadataJson(response.bodyAsText()) as? JsonObject
    check(response.status.isSuccess()) {
        result?.metadataText("error")?.takeIf(String::isNotBlank)
            ?: "Luma Store website returned HTTP ${response.status.value}."
    }
    val saved = result?.get("submission") as? JsonObject
    check(saved?.metadataText("id") == id && saved.metadataText("status") == "Pending") {
        "The website did not confirm that this submission returned to review."
    }
}

private fun requireMetadataUrl(value: String, label: String): String {
    val uri = try { URI(value) } catch (_: IllegalArgumentException) { null }
        catch (_: java.net.URISyntaxException) { null }
    require(uri?.scheme?.lowercase() in setOf("http", "https") && !uri?.host.isNullOrBlank()) {
        "$label requires a valid HTTP or HTTPS URL."
    }
    return value
}

// Website counterpart: lib/luma/fastlane.ts, fetchFastlaneMetadata(). These requests are public.
private suspend fun fetchMetadataFastlane(projectUrl: String, versionCode: Long): JsonObject {
    val uri = URI(projectUrl)
    require(uri.host.equals("github.com", ignoreCase = true)) { "Fastlane metadata requires a github.com repository URL." }
    val path = uri.rawPath.split('/').filter(String::isNotEmpty)
    val slug = Regex("[A-Za-z0-9_.-]+")
    val owner = path.getOrNull(0).orEmpty()
    val repo = path.getOrNull(1).orEmpty().replace(Regex("\\.git$", RegexOption.IGNORE_CASE), "")
    require(listOf(owner, repo).all { slug.matches(it) && it != "." && it != ".." }) { "Enter a GitHub repository URL." }
    val explicitBranch = path.getOrNull(3)?.takeIf { path.getOrNull(2) == "tree" }?.decodeURLPart()
    val branches = listOfNotNull(explicitBranch, "main", "master").distinct()
    for (branch in branches) {
        val refPath = branch.split('/').joinToString("/") { it.encodeURLPathPart() }
        for (locale in listOf("en-US", "en-GB", "de-DE", "en", "de")) {
            val folder = "fastlane/metadata/android/$locale"
            val raw = "https://raw.githubusercontent.com/$owner/$repo/$refPath/$folder"
            val title = metadataTextFile("$raw/title.txt") ?: continue
            val short = metadataTextFile("$raw/short_description.txt") ?: continue
            val full = metadataTextFile("$raw/full_description.txt") ?: continue
            val changelog = metadataTextFile("$raw/changelogs/$versionCode.txt")
                ?: metadataTextFile("$raw/changelogs/default.txt") ?: continue
            val response = metadataHttp.get("https://api.github.com/repos/$owner/$repo/contents/$folder/images/phoneScreenshots") {
                parameter("ref", branch)
                header(HttpHeaders.Accept, "application/vnd.github+json")
            }
            check(response.status.value != 403 && response.status.value != 429) {
                "GitHub refused the Fastlane screenshot lookup. Try again later."
            }
            if (!response.status.isSuccess()) continue
            val images = (parseMetadataJson(response.bodyAsText()) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                .filter { it.metadataText("type") == "file" && Regex(".*\\.(png|jpe?g)", RegexOption.IGNORE_CASE).matches(it.metadataText("name")) }
                .sortedBy { it.metadataText("name").lowercase() }
                .map { it.metadataText("download_url") }.filter(String::isNotBlank)
            if (images.isEmpty()) continue
            var graphic: String? = null
            for (suffix in listOf("png", "webp", "jpg", "jpeg")) {
                val url = "$raw/images/featureGraphic.$suffix"
                if (metadataHttp.head(url).status.isSuccess()) {
                    graphic = url
                    break
                }
            }
            return buildJsonObject {
                put("title", title)
                put("shortDescription", short)
                put("fullDescription", full)
                put("changelog", changelog)
                put("screenshots", JsonArray(images.map(::JsonPrimitive)))
                put("featureGraphic", graphic?.let(::JsonPrimitive) ?: JsonNull)
            }
        }
    }
    error("Fastlane requires title.txt, short_description.txt, full_description.txt, a changelog, and at least one phone screenshot.")
}

private suspend fun metadataTextFile(url: String): String? {
    val response = metadataHttp.get(url)
    return if (response.status.isSuccess()) response.bodyAsText().trim().takeIf(String::isNotEmpty) else null
}

private fun parseMetadataJson(body: String): JsonElement? = try { Json.parseToJsonElement(body) }
    catch (_: IllegalArgumentException) { null }

private fun JsonElement?.metadataString(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.metadataText(key: String): String = get(key).metadataString()
