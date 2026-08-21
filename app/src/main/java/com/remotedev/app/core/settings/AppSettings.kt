package com.remotedev.app.core.settings

data class AppSettings(
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUser: String = "",
    val sshPassword: String = "",
    val sshKey: String = "",
    val sshPassphrase: String = "",
    val aiBaseUrl: String = "https://api.openai.com",
    val aiApiKey: String = "",
    val aiModel: String = "gpt-4o-mini",
)
