package com.freetime.lumadevs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.time.Instant

private data class SettingsField(val key: String, val label: String)

private val profileFields = listOf(
    SettingsField("display_name", "Display name"),
    SettingsField("avatar_url", "Avatar URL"),
    SettingsField("website_url", "Website"),
    SettingsField("github_url", "GitHub"),
    SettingsField("gitlab_url", "GitLab"),
    SettingsField("bio", "Bio"),
)

private val fundingFields = listOf(
    SettingsField("donate_url", "Donation URL"),
    SettingsField("liberapay", "Liberapay URL"),
    SettingsField("opencollective", "OpenCollective URL"),
)

private data class WalletNetwork(val id: String, val label: String, val legacyNames: Set<String> = setOf(label)) {
    val key = "network::$id"
    val column = id.takeIf { it == "bitcoin" || it == "litecoin" }

    fun isLegacyKey(key: String) = key.substringAfter("::", "") in legacyNames
}

// Keep IDs and legacy names aligned with the website's lib/luma/crypto-funding.ts.
private val networks = listOf(
    WalletNetwork("bitcoin", "Bitcoin"),
    WalletNetwork("ethereum", "Ethereum", setOf("Ethereum", "Ethereum (ERC-20)")),
    WalletNetwork("tron", "TRON", setOf("TRON", "TRON (TRC-20)")),
    WalletNetwork("bnb-smart-chain", "BNB Smart Chain", setOf("BNB Smart Chain (BEP-20)")),
    WalletNetwork("solana", "Solana"),
    WalletNetwork("polygon", "Polygon"),
    WalletNetwork("avalanche-c", "Avalanche C-Chain"),
    WalletNetwork("arbitrum", "Arbitrum"),
    WalletNetwork("optimism", "Optimism"),
    WalletNetwork("base", "Base"),
    WalletNetwork("cardano", "Cardano"),
    WalletNetwork("dogecoin", "Dogecoin"),
    WalletNetwork("polkadot", "Polkadot"),
    WalletNetwork("avalanche-p", "Avalanche P-Chain"),
    WalletNetwork("litecoin", "Litecoin"),
    WalletNetwork("bitcoin-cash", "Bitcoin Cash"),
    WalletNetwork("stellar", "Stellar"),
    WalletNetwork("monero", "Monero"),
    WalletNetwork("ton", "TON"),
    WalletNetwork("shibarium", "Shibarium"),
)

private fun JsonElement?.settingText() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
private fun String.settingValue() = trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull

/** Pass only completed reads; null means the signed-in developer has no row yet. */
@Composable
internal fun DeveloperSettings(funding: Boolean, data: JsonObject?, onSaved: () -> Unit) {
    val owner = LumaSupabase.client.auth.currentSessionOrNull()?.user?.id
    if (owner == null) {
        Text("Sign in to edit developer settings.")
        return
    }

    key(owner, funding) {
        val fields = if (funding) fundingFields else profileFields
        val rawWallets = data?.get("crypto_addresses") as? JsonObject
        val initial = remember(data) {
            buildMap {
                fields.forEach { put(it.key, data?.get(it.key).settingText()) }
                if (funding) for (network in networks) {
                    val address = rawWallets?.get(network.key).settingText().trim()
                        .ifEmpty { network.column?.let { data?.get(it).settingText().trim() }.orEmpty() }
                        .ifEmpty {
                            rawWallets?.entries?.firstNotNullOfOrNull { (key, value) ->
                                value.settingText().trim().takeIf { it.isNotEmpty() && network.isLegacyKey(key) }
                            }.orEmpty()
                        }
                    put(network.key, address)
                }
            }
        }
        var draft by remember(initial) { mutableStateOf(initial) }
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var saved by remember(initial) { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val notifySaved by rememberUpdatedState(onSaved)
        val edit: (String, String) -> Unit = { field, value ->
            draft = draft + (field to value)
            saved = false
            error = null
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (funding) "Developer funding" else "Developer profile",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                Button(enabled = !saving && !saved, onClick = {
                    if (!saving) {
                        val values = draft
                        saving = true
                        error = null
                        scope.launch {
                            try {
                                saveDeveloperSettings(owner, funding, fields, values, rawWallets)
                                draft = values.mapValues { it.value.trim() }
                                saved = true
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "Could not save developer settings."
                            } finally {
                                saving = false
                            }
                            if (saved) notifySaved()
                        }
                    }
                }) {
                    Text(if (saving) "Saving…" else if (funding) "Save funding" else "Save profile")
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (saved) Text(if (funding) "Developer funding saved." else "Developer profile saved.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    Text(if (funding) "These support methods are shown for every app you publish."
                    else "Public information shown on your developer page. Verification is managed by Luma Store.")
                }
                items(fields, key = { it.key }) { field ->
                    OutlinedTextField(
                        value = draft.getValue(field.key),
                        onValueChange = { edit(field.key, it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(field.label) },
                        enabled = !saving,
                        singleLine = field.key != "bio",
                        minLines = if (field.key == "bio") 4 else 1,
                    )
                }
                if (funding) {
                    item {
                        Text("Wallet addresses", style = MaterialTheme.typography.titleMedium)
                        Text("Enter one public wallet address per network. It applies to every coin or token on that network.")
                    }
                    items(networks, key = { it.key }) { network ->
                        OutlinedTextField(
                            value = draft.getValue(network.key),
                            onValueChange = { edit(network.key, it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("${network.label} address") },
                            enabled = !saving,
                            singleLine = true,
                        )
                    }
                }
            }
        }
    }
}

private suspend fun saveDeveloperSettings(
    owner: String,
    funding: Boolean,
    fields: List<SettingsField>,
    values: Map<String, String>,
    rawWallets: JsonObject?,
) {
    val client = LumaSupabase.client
    check(client.auth.currentSessionOrNull()?.user?.id == owner) { "Your session changed. Reopen developer settings." }
    val payload = buildJsonObject {
        put("developer_id", owner)
        put("updated_at", Instant.now().toString())
        fields.forEach { put(it.key, values.getValue(it.key).settingValue()) }
        if (funding) {
            put("crypto_addresses", buildJsonObject {
                // Preserve unrecognized entries; remove known aliases so cleared wallets stay cleared.
                rawWallets?.forEach { (key, value) ->
                    if (networks.none { it.key == key || it.isLegacyKey(key) }) put(key, value)
                }
                networks.forEach { network ->
                    val value = values.getValue(network.key).settingValue()
                    if (value != JsonNull) put(network.key, value)
                }
            })
            networks.forEach { network ->
                network.column?.let { put(it, values.getValue(network.key).settingValue()) }
            }
        }
    }
    val table = if (funding) "luma_developer_funding" else "luma_developer_profiles"
    client.from(table).upsert(payload) { onConflict = "developer_id" }
}
