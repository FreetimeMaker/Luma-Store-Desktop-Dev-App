package com.freetime.lumadevs

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Github
import io.github.jan.supabase.auth.providers.Gitlab
import io.github.jan.supabase.auth.providers.OAuthProvider
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

internal class DesktopSignInException(message: String) : IllegalStateException(message)

internal object DesktopAuth {
    private val auth get() = LumaSupabase.client.auth
    private val operation = Mutex()
    private val codeberg = object : OAuthProvider() {
        override val name = "custom:codeberg"
    }

    val sessionStatus: StateFlow<SessionStatus> get() = auth.sessionStatus

    fun currentSessionOrNull(): UserSession? = auth.currentSessionOrNull()

    suspend fun signIn(provider: String) {
        val (oauthProvider, requestedScopes) = when (provider.trim().lowercase()) {
            "github" -> Github to "read:user public_repo"
            "gitlab" -> Gitlab to "read_user read_api"
            "codeberg", "custom:codeberg" -> codeberg to "openid profile email read:user read:repository write:repository"
            else -> throw IllegalArgumentException("Unsupported desktop sign-in provider.")
        }

        check(operation.tryLock()) { "Another desktop auth operation is in progress." }
        try {
            val sdk = auth
            sdk.awaitInitialization()
            try {
                val code = awaitAuthorizationCode { redirect ->
                    sdk.getOAuthUrl(oauthProvider, redirect) {
                        scopes.addAll(requestedScopes.split(' '))
                    }
                }
                try {
                    sdk.exchangeCodeForSession(code)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw DesktopSignInException("The browser returned to the app, but the Supabase session could not be created. Check your connection and the Supabase Auth logs.")
                }
            } finally {
                withContext(NonCancellable) { sdk.codeVerifierCache.deleteCodeVerifier() }
            }
        } finally {
            operation.unlock()
        }
    }

    suspend fun signOut() {
        operation.withLock {
            val sdk = auth
            sdk.awaitInitialization()
            sdk.signOut()
        }
    }

    // The SDK 3.9 desktop listener does not explicitly bind to loopback and also accepts implicit tokens.
    // Keep the listener code-only; the SDK still generates the PKCE challenge and exchanges the code.
    private suspend fun awaitAuthorizationCode(authorizeUrl: (String) -> String): String =
        withContext(Dispatchers.IO) {

            val path = "/oauth/callback/${UUID.randomUUID()}"
            val result = CompletableDeferred<String>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            try {
                server.createContext(path) { exchange ->
                    exchange.use { handleCallback(it, path, result) }
                }
                server.start()
                val redirect = "http://127.0.0.1:${server.address.port}$path"
                openBrowser(URI(authorizeUrl(redirect)))
                withTimeoutOrNull(3.minutes) { result.await() }
                    ?: throw DesktopSignInException("No sign-in response reached the app. Check the browser and the Supabase loopback redirect allowlist.")
            } finally {
                server.stop(0)
            }
        }

    private fun openBrowser(uri: URI) {
        if (runCatching {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) false
            else { Desktop.getDesktop().browse(uri); true }
        }.getOrDefault(false)) return

        if (System.getProperty("os.name").startsWith("Linux", ignoreCase = true)) {
            if (runCatching {
                ProcessBuilder("xdg-open", uri.toASCIIString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }.isSuccess) return
        }
        throw DesktopSignInException("Could not open the default browser. Configure a system browser and try again.")
    }

    private fun handleCallback(exchange: HttpExchange, path: String, result: CompletableDeferred<String>) {
        if (exchange.requestMethod != "GET" || exchange.requestURI.rawPath != path) {
            exchange.sendResponseHeaders(404, -1)
            return
        }
        val params = exchange.requestURI.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
        val code = params.filter { it.first() == "code" }.singleOrNull()?.getOrNull(1)?.let {
            try {
                URLDecoder.decode(it, Charsets.UTF_8).takeIf(String::isNotBlank)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        val failed = params.any { it.first() == "error" || it.first() == "error_code" }
        val message = when {
            failed -> "Sign-in was declined or failed. Return to Luma Store."
            code != null -> "Return to Luma Store to finish signing in."
            else -> "No authorization code was received."
        }
        val body = message.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/plain; charset=UTF-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        try {
            exchange.sendResponseHeaders(if (failed || code == null) 400 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        } finally {
            when {
                failed -> result.completeExceptionally(DesktopSignInException("The provider declined sign-in or Supabase rejected it. Check the browser or the Supabase Auth logs."))
                code != null -> result.complete(code)
            }
        }
    }
}
