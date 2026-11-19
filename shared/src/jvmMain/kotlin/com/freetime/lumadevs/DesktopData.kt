package com.freetime.lumadevs

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.io.IOException
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext

/** JVM data access matching FreetimeMaker/Luma-Store-Website (1f5de01). */
object DesktopData {
    private const val SITE = "https://luma.free-time.me"
    private const val SUBMISSIONS = "/api/luma/submissions"
    private const val STATS = "/api/luma/download-stats"
    private const val FUNDING = "donate_url,liberapay,opencollective,bitcoin,litecoin,crypto_addresses"
    private val client get() = LumaSupabase.client
    private val website by lazy {
        HttpClient(CIO) {
            followRedirects = false // Do not forward session or forge credentials to another origin.
            install(HttpTimeout) { requestTimeoutMillis = 20_000 }
        }
    }

    class WebsiteException(val status: Int, message: String, val deploymentDisabled: Boolean = false) : IllegalStateException(message)

    /** Discover data from app/page.tsx. There is no website JSON catalog route. */
    suspend fun catalog(): JsonObject = coroutineScope {
        val apps = async {
            rows("store_apps", "id,name,short_description,description,developer_name,icon_url,version,package_name,repo_url,license_type,subcategory,categories,created_at,updated_at,latest_detected_version,latest_detected_at") {
                filter { exact("archived_at", null) }
                order("updated_at", Order.DESCENDING)
                order("id", Order.ASCENDING)
            }
        }
        val platforms = async {
            rows("store_app_platforms", "app_id,platform,listing_metadata") { order("id", Order.ASCENDING) }
        }
        val ratings = async {
            rows("store_app_ratings", "app_id,rating", stableOrder = "app_id,rating")
        }
        val metrics = async { rpc("luma_discover_metrics") }
        buildJsonObject {
            put("apps", apps.await())
            put("platforms", platforms.await())
            put("ratings", ratings.await())
            put("metrics", metrics.await())
        }
    }

    /** Public listing detail, looked up by UUID or Android package name. */
    suspend fun catalogApp(identifier: String): JsonObject? {
        require(identifier.isNotBlank())
        val key = if (UUID.matches(identifier)) "id" else "package_name"
        val app = client.from("store_apps").select(Columns.raw(
            "id,name,description,developer_name,developer_id,category_id,icon_url,version,created_at,updated_at,luma_submission_id,package_name,version_code,subcategory,license_type,short_description,screenshots,changelog,ant_features,author_name,author_email,author_website,website_url,source_code_url,repo_url,issue_tracker_url,translation_url,changelog_url,donate_url,liberapay,opencollective,bitcoin,litecoin,categories"
        )) {
            filter { exact("archived_at", null); eq(key, identifier) }
            order("updated_at", Order.DESCENDING)
            order("id", Order.ASCENDING)
            limit(1)
        }.decodeList<JsonObject>().firstOrNull() ?: return null
        val id = app.getValue("id").jsonPrimitive.content
        val target = buildJsonObject { put("target_app_id", id) }
        return coroutineScope {
            val platforms = async {
                rows("store_app_platforms", "id,app_id,platform,package_type,linux_package_base,download_url,file_size_mb,sha256,artifact_verified_at,artifact_size_bytes,permissions,repo_url,listing_metadata") {
                    filter { eq("app_id", id) }; order("platform", Order.ASCENDING); order("id", Order.ASCENDING)
                }
            }
            val downloads = async { rpc("luma_app_download_count", target) }
            val platformDownloads = async { rpc("luma_app_platform_download_counts", target) }
            val versions = async {
                app.text("package_name")?.let { name ->
                    rpc("luma_public_version_history", buildJsonObject { put("target_package_name", name) })
                } ?: JsonArray(emptyList())
            }
            val funding = async { app.text("developer_id")?.let { fundingFor(it) } }
            buildJsonObject {
                put("app", app)
                put("platforms", platforms.await())
                put("totalDownloads", downloads.await())
                put("platformDownloads", platformDownloads.await())
                put("versions", versions.await())
                put("funding", funding.await() ?: JsonNull)
            }
        }
    }

    /** Owner-scoped submission rows, newest first, including drafts and archived entries. */
    suspend fun submissions(): JsonArray = submissionRows(userId())

    suspend fun submission(id: String): JsonObject? = one("luma_submissions", "*") {
        filter { eq("user_id", userId()); eq("id", id) }
    }

    /** Mirrors the website POST envelope. Only developer-editable metadata is forwarded. */
    suspend fun saveSubmission(
        submission: JsonObject,
        editingId: String? = null,
        editingStatus: String? = null,
        draft: Boolean = false,
        draftStep: Int = 1,
    ): JsonObject {
        require(editingId == null || editingId.isNotBlank())
        require(draft || ((editingId == null) == (editingStatus == null))) {
            "A submission edit requires both the ID and the current status."
        }
        val session = session()
        val body = buildJsonObject {
            put("submission", JsonObject(submission.filterKeys { it in editableFields }))
            editingId?.let { put("editingId", it) }
            editingStatus?.let { put("editingStatus", it) }
            put("draft", draft)
            if (draft) put("draftStep", draftStep)
        }
        val providerHeader = when (session.user?.appMetadata?.text("provider")) {
            "github" -> "X-GitHub-Token"
            "gitlab" -> "X-GitLab-Token"
            "custom:codeberg", "codeberg" -> "X-Codeberg-Token"
            else -> null
        }
        // Never retry writes via PostgREST: the route validates ownership and performs transitions.
        val result = request(HttpMethod.Post, SUBMISSIONS, session.accessToken) {
            if (!draft && providerHeader != null) session.providerToken?.let { header(providerHeader, it) }
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        return result["submission"] as? JsonObject ?: error("Website did not return a saved submission.")
    }

    /** Returns the website's {deleted:true} or {archived:true} outcome. */
    suspend fun deleteSubmission(id: String): JsonObject {
        require(id.isNotBlank())
        val result = request(HttpMethod.Delete, SUBMISSIONS, session().accessToken) { parameter("id", id) }
        check(result["deleted"] == JsonPrimitive(true) || result["archived"] == JsonPrimitive(true)) {
            "Website did not confirm deletion or archival."
        }
        return result
    }

    /** Dashboard analytics RPC: actual totals, breakdowns, daily series, and funding clicks. */
    suspend fun analytics(): JsonObject {
        userId()
        return rpc("luma_my_developer_analytics").jsonObject
    }

    /**
     * API-first lifetime counts and submission-to-store mapping. Period fields are intentionally
     * omitted: download-stats/route.ts hardcodes them to zero. Use analytics() for time series.
     */
    suspend fun downloadStats(): JsonObject {
        val session = session()
        val owner = checkNotNull(session.user?.id) { "Authentication is required." }
        val payload = try {
            request(HttpMethod.Get, STATS, session.accessToken)
        } catch (failure: WebsiteException) {
            if (failure.status !in 500..599 && !failure.deploymentDisabled) throw failure
            return fallbackStats(owner, failure)
        } catch (failure: IOException) {
            if (failure is SSLException) throw failure
            return fallbackStats(owner, failure)
        }
        return statsPayload(payload.getValue("stats").jsonObject, payload.getValue("submissionStoreIds").jsonObject)
    }

    suspend fun profile(): JsonObject? = one(
        "luma_developer_profiles", "display_name,bio,website_url,github_url,gitlab_url,avatar_url"
    ) { filter { eq("developer_id", userId()) } }

    suspend fun funding(): JsonObject? = fundingFor(userId())

    /** Status events for a submission owned by the active user, oldest first. */
    suspend fun submissionStatusHistory(id: String): JsonArray {
        if (submission(id) == null) return JsonArray(emptyList())
        return rows("luma_submission_status_history", "id,submission_id,status,review_message,created_at") {
            filter { eq("submission_id", id) }
            order("created_at", Order.ASCENDING)
            order("id", Order.ASCENDING)
        }
    }

    private suspend fun fallbackStats(owner: String, original: Exception): JsonObject {
        coroutineContext.ensureActive()
        try {
            check(userId() == owner) { "The active account changed while loading download stats." }
            val apps = rows("store_apps", "id,luma_submission_id", stableOrder = "id") {
                filter { eq("developer_id", owner) }
            }
            // The website's app dashboard uses this authenticated aggregate, not raw event rows.
            val byApp = rpc("get_my_luma_download_stats").jsonArray.associateBy { it.jsonObject.text("app_id") }
            val mapping = buildJsonObject {
                for (app in apps) {
                    val row = app.jsonObject
                    row.text("luma_submission_id")?.let { put(it, row.getValue("id")) }
                }
            }
            val stats = buildJsonObject {
                for (app in apps) {
                    val id = app.jsonObject.getValue("id").jsonPrimitive.content
                    val total = byApp[id]?.jsonObject?.get("total") ?: rpc(
                        "luma_app_download_count", buildJsonObject { put("target_app_id", id) }
                    )
                    put(id, buildJsonObject { put("app_id", id); put("total", total) })
                }
            }
            check(userId() == owner) { "The active account changed while loading download stats." }
            return statsPayload(stats, mapping)
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: Exception) {
            failure.addSuppressed(original)
            throw failure
        }
    }

    private fun statsPayload(stats: JsonObject, mapping: JsonObject) = buildJsonObject {
        put("stats", buildJsonObject {
            for ((id, value) in stats) {
                val total = value.jsonObject.getValue("total").jsonPrimitive.long
                require(total >= 0) { "Invalid download count for $id." }
                put(id, buildJsonObject { put("app_id", id); put("total", total) })
            }
        })
        put("submissionStoreIds", mapping)
    }

    private suspend fun request(
        method: HttpMethod,
        path: String,
        token: String,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): JsonObject {
        val response = website.request(SITE + path) {
            this.method = method
            bearerAuth(token)
            accept(ContentType.Application.Json)
            header(HttpHeaders.CacheControl, "no-store")
            configure()
        }
        val status = response.status.value
        if (status !in 200..299) {
            val body = try { response.bodyAsText() } catch (_: IOException) { "" }
            val message = try { (Json.parseToJsonElement(body) as? JsonObject)?.text("error") }
            catch (_: IllegalArgumentException) { null }
            throw WebsiteException(
                            status, message ?: "Luma Store website returned HTTP $status.",
                            status == 402 && response.headers["x-vercel-error"] == "DEPLOYMENT_DISABLED",
                        )
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun session() = checkNotNull(client.auth.currentSessionOrNull()) { "Authentication is required." }
    private fun userId() = checkNotNull(session().user?.id) { "Authentication is required." }

    private suspend fun submissionRows(owner: String) = rows("luma_submissions", "*") {
        filter { eq("user_id", owner) }
        order("submitted_at", Order.DESCENDING)
        order("id", Order.ASCENDING)
    }

    private suspend fun fundingFor(owner: String) = one("luma_developer_funding", FUNDING) {
        filter { eq("developer_id", owner) }
    }

    private suspend fun one(table: String, columns: String, query: SelectRequestBuilder.() -> Unit): JsonObject? =
        client.from(table).select(Columns.raw(columns), query).decodeList<JsonObject>().let { result ->
            check(result.size <= 1) { "Expected at most one row from $table." }
            result.firstOrNull()
        }

    private suspend fun rows(
        table: String,
        columns: String,
        stableOrder: String = "",
        query: SelectRequestBuilder.() -> Unit = {},
    ): JsonArray {
        val result = mutableListOf<JsonObject>()
        while (true) {
            val page = client.from(table).select(Columns.raw(columns)) {
                query()
                if (stableOrder.isNotEmpty()) stableOrder.split(',').forEach { order(it, Order.ASCENDING) }
                range(result.size.toLong(), result.size.toLong() + 499)
            }.decodeList<JsonObject>()
            // An installation can cap responses below the requested range.
            if (page.isEmpty()) return JsonArray(result)
            result.addAll(page)
        }
    }

    private suspend fun rpc(name: String, params: JsonObject? = null): JsonElement =
        Json.parseToJsonElement(if (params == null) client.postgrest.rpc(name).data else client.postgrest.rpc(name, params).data)

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)
    private val editableFields = setOf(
        "name", "short_description", "description", "link", "repo_url", "source_code_url", "category",
        "categories", "subcategory", "license_type", "closed_source", "localized_metadata", "icon_url",
        "version", "platform", "platforms", "separate_platform_repos", "linux_package_base", "download_url",
        "changelog", "package_name", "version_code", "screenshots", "website_url", "issue_tracker_url",
        "translation_url", "author_name", "author_email", "author_website", "changelog_url",
    )
}
