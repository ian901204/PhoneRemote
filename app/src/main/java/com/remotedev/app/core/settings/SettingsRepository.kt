package com.remotedev.app.core.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "remotedev_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val SSH_HOST = stringPreferencesKey("ssh_host")
        val SSH_PORT = intPreferencesKey("ssh_port")
        val SSH_USER = stringPreferencesKey("ssh_user")
        val SSH_PASSWORD = stringPreferencesKey("ssh_password")
        val SSH_KEY = stringPreferencesKey("ssh_key")
        val SSH_PASSPHRASE = stringPreferencesKey("ssh_passphrase")
        val AI_BASE_URL = stringPreferencesKey("ai_base_url")
        val AI_API_KEY = stringPreferencesKey("ai_api_key")
        val AI_MODEL = stringPreferencesKey("ai_model")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            sshHost = p[Keys.SSH_HOST] ?: "",
            sshPort = p[Keys.SSH_PORT] ?: 22,
            sshUser = p[Keys.SSH_USER] ?: "",
            sshPassword = p[Keys.SSH_PASSWORD] ?: "",
            sshKey = p[Keys.SSH_KEY] ?: "",
            sshPassphrase = p[Keys.SSH_PASSPHRASE] ?: "",
            aiBaseUrl = p[Keys.AI_BASE_URL] ?: "https://api.openai.com",
            aiApiKey = p[Keys.AI_API_KEY] ?: "",
            aiModel = p[Keys.AI_MODEL] ?: "gpt-4o-mini",
        )
    }

    suspend fun updateSsh(
        host: String, port: Int, user: String,
        password: String, privateKey: String, passphrase: String,
    ) {
        context.dataStore.edit { p ->
            p[Keys.SSH_HOST] = host
            p[Keys.SSH_PORT] = port
            p[Keys.SSH_USER] = user
            p[Keys.SSH_PASSWORD] = password
            p[Keys.SSH_KEY] = privateKey
            p[Keys.SSH_PASSPHRASE] = passphrase
        }
    }

    suspend fun updateAi(baseUrl: String, apiKey: String, model: String) {
        context.dataStore.edit { p ->
            p[Keys.AI_BASE_URL] = baseUrl.trimEnd('/')
            p[Keys.AI_API_KEY] = apiKey
            p[Keys.AI_MODEL] = model
        }
    }
}
