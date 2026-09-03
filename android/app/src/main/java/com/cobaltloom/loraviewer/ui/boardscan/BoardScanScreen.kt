package com.cobaltloom.loraviewer.ui.boardscan

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cobaltloom.loraviewer.data.ocr.BoardOcrAnalyzer
import com.cobaltloom.loraviewer.ui.map.GliderTrackerViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScanScreen(viewModel: GliderTrackerViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draftNames = remember { mutableStateMapOf<Int, String>() }
    var isRecognizing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isRecognizing = true
        scope.launch {
            try {
                val recognized = BoardOcrAnalyzer.recognizeNames(context, uri)
                recognized.forEach { (index, name) -> draftNames[index] = name }
            } catch (e: Exception) {
                errorMessage = "文字の読み取りに失敗しました: ${e.message}"
            } finally {
                isRecognizing = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("名簿から一括登録") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.Filled.ArrowBack, contentDescription = "キャンセル") }
                },
                actions = {
                    TextButton(onClick = {
                        applyDraftNames(viewModel, draftNames, onError = { errorMessage = it }, onDone = onDone)
                    }) { Text("保存") }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).padding(horizontal = 16.dp)) {
            item {
                Button(
                    onClick = { pickImageLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                    Text(" ボードの写真を選ぶ", modifier = Modifier.padding(start = 4.dp))
                }
                if (isRecognizing) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        Text("文字を読み取っています…")
                    }
                }
                Text(
                    "ホワイトボードの写真を選ぶと、番号ごとの名前を自動で読み取って下の欄に入力します。手書き文字は読み間違えることが多いため、保存前に必ず内容を確認・修正してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Text("番号ごとの名前", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
            }
            items((1..15).toList()) { index ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text("$index.", modifier = Modifier.width(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = draftNames[index] ?: "",
                        onValueChange = { draftNames[index] = it },
                        label = { Text("名前") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("OK") } },
            title = { Text("エラー") },
            text = { Text(message) },
        )
    }
}

private fun applyDraftNames(
    viewModel: GliderTrackerViewModel,
    draftNames: Map<Int, String>,
    onError: (String) -> Unit,
    onDone: () -> Unit,
) {
    val imeiMaster = viewModel.uiState.value.config?.imeiMaster
    if (imeiMaster == null) {
        onError("メンバー情報がまだ読み込まれていません。少し待ってから再度お試しください。")
        return
    }
    for ((index, name) in draftNames) {
        val trimmed = name.trim()
        val imei = imeiMaster[index.toString()] ?: continue
        if (trimmed.isNotEmpty()) viewModel.setNickname(imei, trimmed)
    }
    onDone()
}
