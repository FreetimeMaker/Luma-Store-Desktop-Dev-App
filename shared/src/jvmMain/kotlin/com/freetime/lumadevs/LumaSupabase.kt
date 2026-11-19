package com.freetime.lumadevs

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.ktor.client.engine.cio.CIO

internal object LumaSupabase {
    val client by lazy {
        createSupabaseClient(
            supabaseUrl = requiredEnv("SUPABASE_URL", "NEXT_PUBLIC_SUPABASE_URL"),
            supabaseKey = requiredEnv("SUPABASE_PUBLISHABLE_KEY", "NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY")
        ) {
            httpEngine = CIO.create()
            install(Auth) {
                flowType = FlowType.PKCE
                autoLoadFromStorage = false
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
            }
            install(Postgrest)
            install(Storage)
        }
    }

    private fun requiredEnv(name: String, websiteName: String): String =
        sequenceOf(name, websiteName)
            .mapNotNull { System.getenv(it)?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
            ?: error("Supabase is not configured. Set $name or $websiteName.")
}
