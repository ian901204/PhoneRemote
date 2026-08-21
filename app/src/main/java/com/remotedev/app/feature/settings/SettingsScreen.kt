package com.remotedev.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class SshAuthMethod { PASSWORD, PRIVATE_KEY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var sshHost by rememberSaveable { mutableStateOf("") }
    var sshPort by rememberSaveable { mutableStateOf("22") }
    var sshUser by rememberSaveable { mutableStateOf("") }
    var sshPassword by rememberSaveable { mutableStateOf("") }
    var sshKey by rememberSaveable { mutableStateOf("") }
    var sshPassphrase by rememberSaveable { mutableStateOf("") }
    var authMethod by rememberSaveable { mutableStateOf(SshAuthMethod.PASSWORD) }
    var aiBaseUrl by rememberSaveable { mutableStateOf("") }
    var aiApiKey by rememberSaveable { mutableStateOf("") }
    var aiModel by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val current = viewModel.settings.first()
        sshHost = current.sshHost
        sshPort = current.sshPort.toString()
        sshUser = current.sshUser
        sshPassword = current.sshPassword
        sshKey = current.sshKey
        sshPassphrase = current.sshPassphrase
        authMethod = if (current.sshKey.isNotBlank()) SshAuthMethod.PRIVATE_KEY else SshAuthMethod.PASSWORD
        aiBaseUrl = current.aiBaseUrl
        aiApiKey = current.aiApiKey
        aiModel = current.aiModel
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("SSH 連線", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = sshHost,
                onValueChange = { sshHost = it },
                label = { Text("Host") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = sshPort,
                onValueChange = { sshPort = it.filter(Char::isDigit) },
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = sshUser,
                onValueChange = { sshUser = it },
                label = { Text("User") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Column(Modifier.selectableGroup()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = authMethod == SshAuthMethod.PASSWORD,
                        onClick = { authMethod = SshAuthMethod.PASSWORD },
                    )
                    Text("密碼")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = authMethod == SshAuthMethod.PRIVATE_KEY,
                        onClick = { authMethod = SshAuthMethod.PRIVATE_KEY },
                    )
                    Text("Private Key")
                }
            }

            when (authMethod) {
                SshAuthMethod.PASSWORD -> OutlinedTextField(
                    value = sshPassword,
                    onValueChange = { sshPassword = it },
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                SshAuthMethod.PRIVATE_KEY -> OutlinedTextField(
                    value = sshKey,
                    onValueChange = { sshKey = it },
                    label = { Text("Private Key") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }

            OutlinedTextField(
                value = sshPassphrase,
                onValueChange = { sshPassphrase = it },
                label = { Text("Passphrase（選填）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )

            Text("AI 設定", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = aiBaseUrl,
                onValueChange = { aiBaseUrl = it },
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = aiApiKey,
                onValueChange = { aiApiKey = it },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            OutlinedTextField(
                value = aiModel,
                onValueChange = { aiModel = it },
                label = { Text("Model") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Button(
                onClick = {
                    viewModel.save(
                        sshHost = sshHost.trim(),
                        sshPort = sshPort.toIntOrNull() ?: 22,
                        sshUser = sshUser.trim(),
                        sshPassword = if (authMethod == SshAuthMethod.PASSWORD) sshPassword else "",
                        sshKey = if (authMethod == SshAuthMethod.PRIVATE_KEY) sshKey else "",
                        sshPassphrase = sshPassphrase,
                        aiBaseUrl = aiBaseUrl.trim(),
                        aiApiKey = aiApiKey.trim(),
                        aiModel = aiModel.trim(),
                    )
                    scope.launch { snackbarHostState.showSnackbar("已儲存") }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("儲存")
            }
        }
    }
}
