package com.remotedev.app.feature.editor

import android.content.res.AssetManager
import android.graphics.Typeface
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EventReceiver
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.launch
import org.eclipse.tm4e.core.registry.IThemeSource
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun EditorScreen(
    path: String? = null,
    onOpenFiles: () -> Unit = {},
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(path) {
        if (path != null) viewModel.load(path)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 頂部列:檔案路徑 + 儲存按鈕
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = uiState.path ?: "未開啟檔案",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = uiState.path != null && !uiState.loading,
                onClick = {
                    scope.launch {
                        viewModel.save()
                        val st = viewModel.uiState.value
                        Toast.makeText(
                            context,
                            st.error ?: "已儲存",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            ) {
                Text("儲存")
            }
        }

        uiState.error?.let { err ->
            Text(
                text = err,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        if (path == null) {
            // 空白狀態:說明 + 前往 Files 的入口
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "RemoteDev 程式碼編輯器",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "支援語法高亮(Java / Kotlin / Python / JS / HTML / Markdown 等)。\n\n" +
                        "請從 Files 頁瀏覽伺服器檔案,點選檔案即可在此開啟編輯,編輯後按「儲存」寫回伺服器。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                OutlinedButton(onClick = onOpenFiles) {
                    Text("前往 Files 選擇檔案")
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                EditorView(viewModel = viewModel, uiState = uiState)
                if (uiState.loading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

/** TextMate 主題與語法只需初始化一次(process 層級的 singleton registry)。 */
private val textMateReady = AtomicBoolean(false)

private fun ensureTextMateSetup(assets: AssetManager) {
    if (textMateReady.get()) return
    synchronized(textMateReady) {
        if (textMateReady.get()) return
        try {
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))
            // 主題
            val themeRegistry = ThemeRegistry.getInstance()
            listOf("darcula", "quietlight", "solarized_dark", "ayu-dark").forEach { name ->
                val path = "textmate/$name.json"
                try {
                    themeRegistry.loadTheme(
                        ThemeModel(
                            IThemeSource.fromInputStream(
                                FileProviderRegistry.getInstance().tryGetInputStream(path),
                                path,
                                null,
                            ),
                            name,
                        ).apply { if (name != "quietlight") isDark = true },
                    )
                } catch (_: Throwable) {
                    // 單一主題失敗不影響其他
                }
            }
            try {
                themeRegistry.setTheme("darcula")
            } catch (_: Throwable) {
            }
            // 語法
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
            textMateReady.set(true)
        } catch (_: Throwable) {
            // 初始化失敗:保持純文字模式
        }
    }
}

private fun applyTextMate(editor: CodeEditor, scope: String) {
    try {
        if (editor.colorScheme !is TextMateColorScheme) {
            editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
        }
        editor.setEditorLanguage(TextMateLanguage.create(scope, true))
    } catch (_: Throwable) {
        // fallback:純文字
    }
}

@Composable
private fun EditorView(viewModel: EditorViewModel, uiState: EditorViewModel.EditorUiState) {
    val editorRef = remember { arrayOfNulls<CodeEditor>(1) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            ensureTextMateSetup(ctx.applicationContext.assets)
            CodeEditor(ctx).apply {
                typefaceText = Typeface.MONOSPACE
                setTextSize(14f)
                uiState.path?.let { p ->
                    viewModel.languageScopeFor(p)?.let { scope -> applyTextMate(this, scope) }
                }
                editorRef[0] = this
            }
        },
        update = { editor ->
            val vmContent = uiState.content
            if (editor.text.toString() != vmContent) {
                editor.setText(vmContent)
            }
        },
    )

    // 內容變更同步回 ViewModel
    DisposableEffect(Unit) {
        val receiver = EventReceiver<ContentChangeEvent> { event, _ ->
            viewModel.onContentChange(event.editor.text.toString())
        }
        editorRef[0]?.subscribeEvent(ContentChangeEvent::class.java, receiver)
        onDispose {
            // 編輯器隨 View 銷毀,直接 release 即可,無需逐一退訂
            editorRef[0]?.release()
        }
    }
}
