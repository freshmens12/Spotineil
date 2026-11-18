package com.spotineil.core.data.auth

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.spotineil.core.network.auth.AuthTokenStore
import com.spotineil.core.network.auth.AuthTokens
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_secure")

/**
 * Stores OAuth tokens in DataStore, encrypted with Tink AES-256-GCM. The Tink keyset is
 * itself wrapped by a non-exportable Android Keystore master key.
 */
@Singleton
class EncryptedTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : AuthTokenStore {

    private val aead: Aead by lazy {
        AeadConfig.register()
        @Suppress("DEPRECATION")
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    private val state: MutableStateFlow<AuthTokens?> by lazy { MutableStateFlow(load()) }

    override val tokens: StateFlow<AuthTokens?> get() = state.asStateFlow()

    override fun get(): AuthTokens? = state.value

    override fun save(tokens: AuthTokens) {
        val plaintext = Json.encodeToString(AuthTokens.serializer(), tokens).encodeToByteArray()
        val cipher = aead.encrypt(plaintext, ASSOCIATED_DATA)
        val encoded = Base64.encodeToString(cipher, Base64.NO_WRAP)
        runBlocking(Dispatchers.IO) { context.authDataStore.edit { it[KEY] = encoded } }
        state.value = tokens
    }

    override fun clear() {
        runBlocking(Dispatchers.IO) { context.authDataStore.edit { it.remove(KEY) } }
        state.value = null
    }

    private fun load(): AuthTokens? = runBlocking(Dispatchers.IO) {
        val encoded = context.authDataStore.data.first()[KEY] ?: return@runBlocking null
        runCatching {
            val plain = aead.decrypt(Base64.decode(encoded, Base64.NO_WRAP), ASSOCIATED_DATA)
            Json.decodeFromString(AuthTokens.serializer(), plain.decodeToString())
        }.getOrElse {
            // Keystore key lost (e.g. device restore): drop the unreadable blob; the user re-logs in.
            context.authDataStore.edit { prefs -> prefs.remove(KEY) }
            null
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("tokens")
        const val KEYSET_NAME = "spotineil_token_keyset"
        const val KEYSET_PREFS = "spotineil_token_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://spotineil_token_master_key"
        val ASSOCIATED_DATA = "spotineil-auth-tokens".encodeToByteArray()
    }
}
