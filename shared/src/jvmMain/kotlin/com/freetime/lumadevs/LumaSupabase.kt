package com.freetime.lumadevs

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.MemoryCodeVerifierCache

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.ktor.client.engine.cio.CIO

internal object LumaSupabase {
    val client by lazy {
        createSupabaseClient(
            supabaseUrl = "https://ndlaevedujqxhygbyxfh.supabase.co",
                supabaseKey = "sb_publishable_HlppI4ILiXV7DZkpyrDEhQ_ytb2vV6g"
        ) {
            httpEngine = CIO.create()
            install(Auth) {
                flowType = FlowType.PKCE
                autoLoadFromStorage = true
                autoSaveToStorage = true
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
