package com.remotedev.app.feature.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.Properties
import java.util.Vector
import javax.inject.Inject
import javax.inject.Singleton

data class RemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
)

@Singleton
class SshConnectionManager @Inject constructor() {

    private val mutex = Mutex()
    private var session: Session? = null
    private var sftp: ChannelSftp? = null

    fun isConnected(): Boolean = session?.isConnected == true

    suspend fun connect(
        host: String,
        port: Int,
        user: String,
        password: String?,
        privateKey: String?,
        passphrase: String?,
    ) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                disconnectLocked()
                val jsch = JSch()
                var keyDiag = ""
                if (!privateKey.isNullOrBlank()) {
                    // 正規化:統一換行、去頭尾空白、補結尾換行(PEM/OpenSSH 解析需要)
                    val normalizedKey = privateKey
                        .replace("\r\n", "\n")
                        .replace('\r', '\n')
                        .trim() + "\n"
                    val keyBytes = normalizedKey.toByteArray(Charsets.UTF_8)
                    // 先本地解析驗證,取得金鑰類型/指紋/是否加密,便於診斷
                    val kp = try {
                        KeyPair.load(jsch, keyBytes, null)
                    } catch (e: JSchException) {
                        null
                    }
                    if (kp == null) {
                        throw IllegalStateException(
                            "私鑰解析失敗:內容不是有效的私鑰。" +
                                "請確認匯入的是「私鑰」檔(不是 .pub 公開金鑰)," +
                                "格式為 OpenSSH 或 PEM(BEGIN OPENSSH/RSA/EC PRIVATE KEY),不支援 PuTTY .ppk",
                        )
                    }
                    keyDiag = "金鑰類型=${kp.keyTypeName}, 指紋=${kp.fingerPrint}, 加密=${kp.isEncrypted}"
                    if (kp.isEncrypted && passphrase.isNullOrEmpty()) {
                        kp.dispose()
                        throw IllegalStateException("此私鑰已加密,請在設定中填寫 Passphrase($keyDiag)")
                    }
                    try {
                        if (passphrase.isNullOrEmpty()) {
                            jsch.addIdentity(user, keyBytes, null, null)
                        } else {
                            jsch.addIdentity(user, keyBytes, null, passphrase.toByteArray(Charsets.UTF_8))
                        }
                    } catch (e: JSchException) {
                        kp.dispose()
                        throw IllegalStateException(
                            "私鑰解密失敗:${e.message}($keyDiag)。若私鑰有密碼請確認 Passphrase 正確",
                            e,
                        )
                    }
                    kp.dispose()
                }
                val newSession = jsch.getSession(user, host, port)
                if (privateKey.isNullOrBlank() && !password.isNullOrEmpty()) {
                    newSession.setPassword(password)
                }
                val config = Properties()
                config["StrictHostKeyChecking"] = "no"
                // 相容舊版 SSH server:預設演算法清單中補上 ssh-rsa(SHA-1)
                // mwiede JSch 預設停用 ssh-rsa,舊 server 只支援它時會 Auth fail
                config["PubkeyAcceptedAlgorithms"] =
                    "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa"
                config["server_host_key"] =
                    "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa,ssh-dss"
                newSession.setConfig(config)
                try {
                    newSession.connect(15_000)
                } catch (e: JSchException) {
                    val msg = e.message ?: ""
                    if (msg.contains("Auth fail", ignoreCase = true)) {
                        throw IllegalStateException(
                            "SSH 認證失敗:$msg。$keyDiag。" +
                                "請確認:1) 使用者名稱「$user」正確;" +
                                "2) 此指紋對應的公鑰已加入 server 的 ~/.ssh/authorized_keys" +
                                "(可在電腦上執行 ssh-keygen -lf 私鑰檔 比對指紋);" +
                                "3) 若 key 有密碼,Passphrase 是否正確",
                            e,
                        )
                    }
                    throw e
                }

                val channel = newSession.openChannel("sftp") as ChannelSftp
                channel.connect(15_000)

                session = newSession
                sftp = channel
            }
        }
    }

    fun disconnect() {
        // Safe to call from non-suspend contexts.
        runCatching {
            sftp?.let { if (it.isConnected) it.disconnect() }
            session?.let { if (it.isConnected) it.disconnect() }
        }
        sftp = null
        session = null
    }

    private fun disconnectLocked() {
        disconnect()
    }

    suspend fun exec(command: String): String = withContext(Dispatchers.IO) {
        val currentSession = mutex.withLock {
            session?.takeIf { it.isConnected } ?: throw IllegalStateException("尚未連線")
        }
        val channel = currentSession.openChannel("exec") as ChannelExec
        try {
            channel.setCommand(command)
            val input = channel.inputStream
            val err = channel.errStream
            channel.connect(15_000)
            val sb = StringBuilder()
            val buffer = ByteArray(4096)
            while (true) {
                while (input.available() > 0) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    sb.append(String(buffer, 0, n, Charsets.UTF_8))
                }
                while (err.available() > 0) {
                    val n = err.read(buffer)
                    if (n < 0) break
                    sb.append(String(buffer, 0, n, Charsets.UTF_8))
                }
                if (channel.isClosed) {
                    // Drain any remaining bytes after the channel closes.
                    while (input.available() > 0) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        sb.append(String(buffer, 0, n, Charsets.UTF_8))
                    }
                    while (err.available() > 0) {
                        val n = err.read(buffer)
                        if (n < 0) break
                        sb.append(String(buffer, 0, n, Charsets.UTF_8))
                    }
                    break
                }
                Thread.sleep(100)
            }
            sb.toString()
        } finally {
            channel.disconnect()
        }
    }

    suspend fun listFiles(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val channel = mutex.withLock {
            sftp?.takeIf { it.isConnected } ?: throw IllegalStateException("尚未連線")
        }
        @Suppress("UNCHECKED_CAST")
        val entries = channel.ls(path) as Vector<ChannelSftp.LsEntry>
        entries.mapNotNull { entry ->
            val name = entry.filename
            if (name == "." || name == "..") return@mapNotNull null
            RemoteFile(
                name = name,
                path = if (path.endsWith("/")) path + name else "$path/$name",
                isDirectory = entry.attrs.isDir,
                size = entry.attrs.size,
            )
        }.sortedWith(compareByDescending<RemoteFile> { it.isDirectory }.thenBy { it.name })
    }

    suspend fun readFile(path: String): String = withContext(Dispatchers.IO) {
        val channel = mutex.withLock {
            sftp?.takeIf { it.isConnected } ?: throw IllegalStateException("尚未連線")
        }
        channel.get(path).use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        }
    }

    suspend fun writeFile(path: String, content: String) {
        withContext(Dispatchers.IO) {
            val channel = mutex.withLock {
                sftp?.takeIf { it.isConnected } ?: throw IllegalStateException("尚未連線")
            }
            ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)).use { input ->
                channel.put(input, path)
            }
        }
    }
}
