package com.remotedev.app.feature.ssh

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 *
 * 連線狀態 / shell reader / scrollback 皆由本 Singleton 持有,
 * 所有頁面(Terminal / Files / Editor)共享同一份真實狀態,
 * 避免各頁 ViewModel 各自維護副本造成的邏輯不一致。
 */
@Singleton
class SshConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()

    /** Manager 自己的 coroutine scope:shell reader / 背景工作不依附任何頁面 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var ssh: SSHClient? = null

    // ---- 連線狀態(單一真實來源) ----

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _shellActive = MutableStateFlow(false)
    val shellActive: StateFlow<Boolean> = _shellActive.asStateFlow()

    fun isConnected(): Boolean = _connected.value

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
                _connected.value = true
                // 前景服務:讓連線在 App 退到背景時仍保持
                SshService.start(context, "$user@$host")
            }
        }
    }

    fun disconnect() {
        scope.launch {
            mutex.withLock { disconnectLocked() }
        }
        SshService.stop(context)
    }

    private fun disconnectLocked() {
        closeShellLocked()
        runCatching { ssh?.disconnect() }
        ssh = null
        _connected.value = false
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

    /** Shell 原始輸出(base64 編碼的 UTF-8 bytes),供 xterm.js WebView 渲染 */
    private val _shellOutput = MutableSharedFlow<String>(extraBufferCapacity = 512)
    val shellOutput: SharedFlow<String> = _shellOutput.asSharedFlow()

    /** 捲動緩衝:WebView 就緒前/重建後重放用 */
    private val scrollback = ArrayDeque<String>()
    private val maxScrollbackChunks = 500

    private fun emitShellBytes(bytes: ByteArray, length: Int) {
        val b64 = Base64.encodeToString(bytes, 0, length, Base64.NO_WRAP)
        synchronized(scrollback) {
            scrollback.addLast(b64)
            while (scrollback.size > maxScrollbackChunks) scrollback.removeFirst()
        }
        _shellOutput.tryEmit(b64)
    }

    fun emitShellText(text: String) {
        val b = text.toByteArray(Charsets.UTF_8)
        emitShellBytes(b, b.size)
    }

    fun getScrollback(): List<String> = synchronized(scrollback) { scrollback.toList() }

    fun getShell(): ShellSession? = shellSession

    /** 開啟帶 PTY 的互動式 shell(會載入使用者的 .bashrc 等環境),並由 manager 持有 reader loop */
    suspend fun openShell(cols: Int = 120, rows: Int = 40): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val client = requireClient()
            shellSession?.close()
            val sess = client.startSession()
            sess.allocatePTY(
                "xterm-256color", cols, rows, 0, 0,
                java.util.Collections.emptyMap(),
            )
            val shell = sess.startShell()
            shellSession = ShellSession(sess, shell)
            _shellActive.value = true
            startReaderLocked(shellSession!!)
        }
    }

    /** reader loop 由 Singleton 持有:頁面切換/重建不影響讀取,斷線時統一更新狀態 */
    private fun startReaderLocked(session: ShellSession) {
        scope.launch {
            val buf = ByteArray(8192)
            while (isActive) {
                val n = try {
                    session.output.read(buf)
                } catch (e: Exception) {
                    -1
                }
                if (n <= 0) break
                emitShellBytes(buf, n)
            }
            // shell 結束 = 連線已斷
            _shellActive.value = false
            _connected.value = false
            emitShellText("\n[連線已中斷,請點「重新連線」]\n")
            SshService.stop(context)
        }
    }

    /** 傳送原始按鍵/文字到互動式 shell(非 suspend,UI 直接呼叫) */
    fun sendToShell(text: String) {
        val s = shellSession ?: return
        scope.launch {
            try {
                s.input.write(text.toByteArray(Charsets.UTF_8))
                s.input.flush()
            } catch (_: Exception) {
            }
        }
    }

    /** PTY 視窗大小改變(terminal resize) */
    fun resizeShell(cols: Int, rows: Int) {
        val s = shellSession ?: return
        scope.launch {
            runCatching { s.session.changeWindowDimensions(cols, rows, 0, 0) }
        }
    }

    fun closeShell() {
        scope.launch {
            mutex.withLock { closeShellLocked() }
        }
    }

    private fun closeShellLocked() {
        shellSession?.close()
        shellSession = null
        _shellActive.value = false
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

    // ---- 資料夾下載(SFTP 遞迴 → 本機 SAF 目錄) ----

    /**
     * 遞迴下載遠端資料夾到使用者挑選的本機目錄(SAF tree URI)。
     * 兩階段:先掃描取得檔案總數(供確定進度條),再逐一傳輸。
     * 使用獨立的 SFTP channel,不佔用全域 mutex,下載期間 terminal 仍可使用。
     * onScan(已掃描檔案數) 於掃描階段呼叫;onProgress(檔名, 已完成, 總數) 於傳輸階段呼叫。
     * @return 下載的檔案總數
     */
    suspend fun downloadFolder(
        remotePath: String,
        treeUri: android.net.Uri,
        onScan: (Int) -> Unit,
        onProgress: (String, Int, Int) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val client = requireClient()
        client.newSFTPClient().use { sftp ->
            // 階段一:掃描檔案清單
            val entries = mutableListOf<Pair<String, String>>() // (遠端路徑, 相對路徑)
            fun scan(remote: String, rel: String) {
                for (info in sftp.ls(remote)) {
                    if (info.name == "." || info.name == "..") continue
                    val childRemote = remote.trimEnd('/') + "/" + info.name
                    val childRel = if (rel.isEmpty()) info.name else "$rel/${info.name}"
                    if (info.attributes.type == FileMode.Type.DIRECTORY) {
                        scan(childRemote, childRel)
                    } else {
                        entries.add(childRemote to childRel)
                        onScan(entries.size)
                    }
                }
            }
            scan(remotePath, "")
            val total = entries.size

            // 階段二:傳輸
            val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IllegalStateException("無法存取所選的本機資料夾")
            val rootName = remotePath.trimEnd('/').substringAfterLast('/').ifBlank { "download" }
            val destRoot = rootDoc.createDirectory(rootName)
                ?: throw IllegalStateException("無法在所選位置建立資料夾「$rootName」")

            var done = 0
            val dirCache = HashMap<String, androidx.documentfile.provider.DocumentFile>()
            fun dirFor(rel: String): androidx.documentfile.provider.DocumentFile? {
                if (rel.isEmpty()) return destRoot
                dirCache[rel]?.let { return it }
                val parentRel = rel.substringBeforeLast('/', "")
                val parent = dirFor(parentRel) ?: return null
                val name = rel.substringAfterLast('/')
                val d = parent.createDirectory(name) ?: parent.findFile(name)
                if (d != null) dirCache[rel] = d
                return d
            }

            for ((remote, rel) in entries) {
                val dir = dirFor(rel.substringBeforeLast('/', "")) ?: continue
                val name = rel.substringAfterLast('/')
                val mime = java.net.URLConnection
                    .guessContentTypeFromName(name)
                    ?: "application/octet-stream"
                val outFile = dir.createFile(mime, name) ?: continue
                context.contentResolver.openOutputStream(outFile.uri)?.use { os ->
                    val rf = sftp.open(remote)
                    try {
                        rf.RemoteFileInputStream().use { it.copyTo(os) }
                    } finally {
                        rf.close()
                    }
                }
                done++
                onProgress(name, done, total)
            }
            done
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
