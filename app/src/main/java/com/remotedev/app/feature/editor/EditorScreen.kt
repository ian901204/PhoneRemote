package com.remotedev.app.feature.editor

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EventReceiver
import io.github.rosemoe.sora.lang.Language
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(
    path: String? = null,
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
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("從 Files 頁選擇檔案開啟")
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

@Composable
private fun EditorView(viewModel: EditorViewModel, uiState: EditorViewModel.EditorUiState) {
    val editorRef = remember { arrayOfNulls<CodeEditor>(1) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            CodeEditor(ctx).apply {
                typefaceText = Typeface.MONOSPACE
                setTextSize(14f)
                // 語法高亮:嘗試 TextMate,失敗則退回純文字(不 crash)
                uiState.path?.let { trySetTextMateLanguage(this, viewModel.languageScopeFor(it)) }
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

/**
 * 嘗試以 TextMate 設定語法高亮;任何初始化失敗(grammar 資產缺失、tm4e 例外等)
 * 都退回預設純文字模式,確保 editor 仍可正常運作。
 *
 * TextMate 強化點:完整實作需先載入 grammar 資產,例如
 * FileProviderRegistry 註冊資產 + GrammarRegistry.loadGrammars("textmate/languages.json")
 * + TextMateColorScheme,之後再建立 TextMateLanguage。
 * 目前以反射保守嘗試,資產未就緒時自動維持純文字。
 */
private fun trySetTextMateLanguage(editor: CodeEditor, scope: String) {
    try {
        val cls = Class.forName("io.github.rosemoe.sora.langs.textmate.TextMateLanguage")
        val create = cls.getMethod("create", String::class.java, Boolean::class.javaPrimitiveType)
        val lang = create.invoke(null, scope, false)
        if (lang is Language) {
            editor.setEditorLanguage(lang)
        }
    } catch (t: Throwable) {
        // fallback:純文字(預設語言即純文字,無需額外動作)
    }
}
