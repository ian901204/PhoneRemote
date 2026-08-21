package com.remotedev.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remotedev.app.core.settings.AppSettings
import com.remotedev.app.core.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: Flow<AppSettings> = settingsRepository.settings

    fun save(
        sshHost: String,
        sshPort: Int,
        sshUser: String,
        sshPassword: String,
        sshKey: String,
        sshPassphrase: String,
        aiBaseUrl: String,
        aiApiKey: String,
        aiModel: String,
    ) {
        viewModelScope.launch {
            settingsRepository.updateSsh(sshHost, sshPort, sshUser, sshPassword, sshKey, sshPassphrase)
            settingsRepository.updateAi(aiBaseUrl, aiApiKey, aiModel)
        }
    }
}
