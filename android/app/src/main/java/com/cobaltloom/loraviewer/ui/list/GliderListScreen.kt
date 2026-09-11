package com.cobaltloom.loraviewer.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cobaltloom.loraviewer.data.model.GliderPosition
import com.cobaltloom.loraviewer.data.nickname.NicknameSyncMode
import com.cobaltloom.loraviewer.ui.map.GliderTrackerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GliderListScreen(
    viewModel: GliderTrackerViewModel,
    onBack: () -> Unit,
    onOpenBoardScan: () -> Unit,
    onRequireSubscription: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    var editingGlider by remember { mutableStateOf<GliderPosition?>(null) }

    val sortedPositions = remember(uiState.positions, uiState.favorites) {
        uiState.positions.sortedWith(
            compareByDescending<GliderPosition> { uiState.isFavorite(it.imei) }
                .thenBy { it.index.toIntOrNull() ?: Int.MAX_VALUE },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("メンバー一覧") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenBoardScan) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = "名簿から登録")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("ニックネームの同期", style = MaterialTheme.typography.titleSmall)
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        FilterChip(
                            selected = uiState.nicknameSyncMode == NicknameSyncMode.SYNCED,
                            onClick = { viewModel.setNicknameSyncMode(NicknameSyncMode.SYNCED) },
                            label = { Text("他の人と同期") },
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = uiState.nicknameSyncMode == NicknameSyncMode.MANUAL,
                            onClick = { viewModel.setNicknameSyncMode(NicknameSyncMode.MANUAL) },
                            label = { Text("手動入力") },
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                    }
                    Text(
                        text = if (uiState.nicknameSyncMode == NicknameSyncMode.SYNCED) {
                            "ニックネームは他の利用者と共有されます。誰でも編集できるため、いたずらで書き換えられた場合は「手動入力」に切り替えてください。"
                        } else {
                            "この端末だけのニックネームになります。他の利用者の変更は反映されず、この端末での変更も共有されません。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                HorizontalDivider()
            }
            item {
                Text(
                    "★でお気に入りに登録した機体が1機以上あると、高度不足アラート(カスタム設定)と競技会ガイドラインのアラートはお気に入りの機体だけが対象になります。お気に入りが0機のときは全機が対象です。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(sortedPositions, key = { it.imei }) { glider ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    IconButton(
                        onClick = {
                            if (uiState.isSubscribed) viewModel.toggleFavorite(glider.imei) else onRequireSubscription()
                        },
                    ) {
                        val isFavorite = uiState.isFavorite(glider.imei)
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                            contentDescription = "お気に入り",
                            tint = if (isFavorite) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                if (uiState.isSubscribed) editingGlider = glider else onRequireSubscription()
                            },
                    ) {
                        val baseName = uiState.baseNameFor(glider)
                        val nickname = if (uiState.isSubscribed) uiState.nicknames[glider.imei] else null
                        Text(
                            if (nickname != null) "$baseName $nickname" else baseName,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            glider.alt?.let { Text("高度 ${it.toInt()} m", style = MaterialTheme.typography.bodySmall) }
                            if (glider.isDisconnected) {
                                Text("切断", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text(
                                    glider.source.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }

    editingGlider?.let { glider ->
        var nicknameInput by remember(glider.imei) { mutableStateOf(uiState.nicknames[glider.imei] ?: "") }
        AlertDialog(
            onDismissRequest = { editingGlider = null },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setNickname(glider.imei, nicknameInput)
                    editingGlider = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingGlider = null }) { Text("キャンセル") } },
            title = { Text("ニックネームを編集") },
            text = {
                OutlinedTextField(
                    value = nicknameInput,
                    onValueChange = { nicknameInput = it },
                    label = { Text("${glider.index}番機のニックネーム") },
                    singleLine = true,
                )
            },
        )
    }
}
