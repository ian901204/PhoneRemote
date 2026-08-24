package com.remotedev.app.feature.ssh

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.UserAuthException
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.security.Security
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton

data class RemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
)

/**
 * SSH 連線管理(sshj + BouncyCastle 實作)。
 * 對加密的 OpenSSH 格式私鑰(bcrypt + aes-ctr)有完整支援,Android 上比 JSch 可靠。
 * 所有方法皆執行緒安全(Mutex 保護),suspend 函數於 Dispatchers.IO 執行。
 */
@Singleton
class SshConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()

    @Volatile
    private var ssh: SSHClient? = null

    fun isConnected(): Boolean = ssh?.let { it.isConnected && it.isAuthenticated } == true

    /** Files 頁「在此開啟 Terminal」要切換到的目錄(一次性消費) */
    @Volatile
    var pendingTerminalPath: String? = null

    fun consumePendingTerminalPath(): String? {
        val p = pendingTerminalPath
        pendingTerminalPath = null
        return p
    }

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
                // Android 內建殘缺版 BC provider(缺 X25519 等),需先移除再註冊完整版
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                Security.insertProviderAt(BouncyCastleProvider(), 1)
                val client = SSHClient()
                client.addHostKeyVerifier(PromiscuousVerifier())
                client.connectTimeout = 15_000
                client.timeout = 15_000
                client.connect(host, port)

                val keyDiag = if (!privateKey.isNullOrBlank()) {
                    val firstLine = privateKey.trim().lineSequence().firstOrNull().orEmpty()
                    "金鑰標頭=$firstLine"
                } else ""

                try {
                    if (!privateKey.isNullOrBlank()) {
                        // 正規化:統一換行、去頭尾空白、補結尾換行
                        val normalizedKey = privateKey
                            .replace("\r\n", "\n")
                            .replace('\r', '\n')
                            .trim() + "\n"
                        // sshj 從檔案載入金鑰,先寫到私有暫存檔再刪除
                        val tmp = File.createTempFile("remotedev_key", ".tmp", context.cacheDir)
                        try {
                            tmp.writeText(normalizedKey)
                            tmp.setReadable(false, false)
                            tmp.setReadable(true, true)
                            val keyProvider = try {
                                if (passphrase.isNullOrEmpty()) {
                                    client.loadKeys(tmp.absolutePath)
                                } else {
                                    client.loadKeys(tmp.absolutePath, passphrase)
                                }
                            } catch (e: Exception) {
                                throw IllegalStateException(
                                    "私鑰解析/解密失敗:${e.message}($keyDiag)。" +
                                        "請確認是 OpenSSH 或 PEM 格式的「私鑰」" +
                                        "(BEGIN OPENSSH/RSA/EC PRIVATE KEY),不是 .pub 公鑰;" +
                                        "不支援 PuTTY .ppk;若私鑰有密碼請確認 Passphrase 正確",
                                    e,
                                )
                            }
                            client.authPublickey(user, keyProvider)
                        } finally {
                            tmp.delete()
                        }
                    } else if (!password.isNullOrEmpty()) {
                        client.authPassword(user, password)
                    } else {
                        throw IllegalStateException("請先在設定中填寫密碼或匯入私鑰")
                    }

                    if (!client.isAuthenticated) {
                        throw IllegalStateException("SSH 認證失敗:server 未接受認證($keyDiag)")
                    }
                } catch (e: UserAuthException) {
                    client.disconnect()
                    throw IllegalStateException(
                        "SSH 認證失敗:${e.message}($keyDiag)。" +
                            "請確認:1) 使用者名稱「$user」正確;" +
                            "2) 此私鑰對應的公鑰已加入 server 的 ~/.ssh/authorized_keys;" +
                            "3) 若 key 有密碼,Passphrase 是否正確",
                        e,
                    )
                } catch (e: IllegalStateException) {
                    client.disconnect()
                    throw e
                } catch (e: Exception) {
                    client.disconnect()
                    throw IllegalStateException("SSH 連線失敗:${e.message}", e)
                }

                ssh = client
            }
        }
    }

    fun disconnect() {
        runCatching { ssh?.disconnect() }
        ssh = null
    }

    private fun disconnectLocked() {
        closeShell()
        runCatching { ssh?.disconnect() }
        ssh = null
    }

    private fun requireClient(): SSHClient =
        ssh?.takeIf { it.isConnected && it.isAuthenticated }
            ?: throw IllegalStateException("尚未連線,請先到 Terminal 頁連線")

    // ---- 互動式 Shell(PTY) ----

    class ShellSession(
        val session: net.schmizz.sshj.connection.channel.direct.Session,
        val shell: net.schmizz.sshj.connection.channel.direct.Session.Shell,
    ) {
        val output: java.io.InputStream get() = shell.inputStream
        val input: java.io.OutputStream get() = shell.outputStream
        fun close() {
            runCatching { shell.close() }
            runCatching { session.close() }
        }
    }

    @Volatile
    private var shellSession: ShellSession? = null

    /** 開啟帶 PTY 的互動式 shell(會載入使用者的 .bashrc 等環境) */
    suspend fun openShell(cols: Int = 120, rows: Int = 40): ShellSession = withContext(Dispatchers.IO) {
        mutex.withLock {
            val client = requireClient()
            shellSession?.close()
            val sess = client.startSession()
            sess.allocatePTY(
                "xterm-256color", cols, rows, 0, 0,
                java.util.Collections.emptyMap(),
            )
            val shell = sess.startShell()
            ShellSession(sess, shell).also { shellSession = it }
        }
    }

    /** 傳送原始按鍵/文字到互動式 shell */
    suspend fun sendToShell(text: String) = withContext(Dispatchers.IO) {
        shellSession?.let {
            it.input.write(text.toByteArray(Charsets.UTF_8))
            it.input.flush()
        }
    }

    fun getShell(): ShellSession? = shellSession

    fun closeShell() {
        shellSession?.close()
        shellSession = null
    }

    suspend fun exec(command: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val client = requireClient()
            client.startSession().use { sess ->
                val cmd = sess.exec(command)
                val out = cmd.inputStream.readBytes().decodeToString()
                val err = cmd.errorStream.readBytes().decodeToString()
                cmd.join()
                val combined = buildString {
                    append(out)
                    if (err.isNotBlank()) {
                        if (isNotEmpty() && !endsWith("\n")) append("\n")
                        append(err)
                    }
                }
                combined.ifBlank { "(無輸出)" }
            }
        }
    }

    suspend fun listFiles(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val client = requireClient()
            client.newSFTPClient().use { sftp ->
                val base = if (path.isBlank()) "." else path
                sftp.ls(base)
                    .filter { it.name != "." && it.name != ".." }
                    .map { info ->
                        RemoteFile(
                            name = info.name,
                            path = base.trimEnd('/') + "/" + info.name,
                            isDirectory = info.attributes.type == FileMode.Type.DIRECTORY,
                            size = info.attributes.size,
                        )
                    }
                    .sortedWith(compareByDescending<RemoteFile> { it.isDirectory }.thenBy { it.name })
            }
        }
    }

    suspend fun readFile(path: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val client = requireClient()
            client.newSFTPClient().use { sftp ->
                val file = sftp.open(path)
                try {
                    file.RemoteFileInputStream().use { input ->
                        input.readBytes().decodeToString()
                    }
                } finally {
                    file.close()
                }
            }
        }
    }

    suspend fun writeFile(path: String, content: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val client = requireClient()
                client.newSFTPClient().use { sftp ->
                    val file = sftp.open(
                        path,
                        EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC),
                    )
                    try {
                        file.RemoteFileOutputStream().use { output ->
                            output.write(content.toByteArray(Charsets.UTF_8))
                        }
                    } finally {
                        file.close()
                    }
                }
            }
        }
    }
}
