package com.cobaltloom.loraviewer.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cobaltloom.loraviewer.data.settings.ApiSettings
import com.cobaltloom.loraviewer.data.settings.ApiSettingsRepository
import kotlinx.coroutines.launch

/** サーバー接続・更新間隔など、通常は変更不要な設定をまとめたサブ画面。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    apiSettingsRepository: ApiSettingsRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val apiSettings by apiSettingsRepository.settings.collectAsState(
        initial = ApiSettings(ApiSettings.DEFAULT_BASE_URL, "", ApiSettings.DEFAULT_REFRESH_INTERVAL_SECONDS, isBaseUrlCustomized = false),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("高度な設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "戻る") }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).padding(horizontal = 16.dp)) {
            item {
                SettingsSectionHeader("サーバー")
                OutlinedTextField(
                    value = apiSettings.baseUrl,
                    onValueChange = { scope.launch { apiSettingsRepository.setBaseUrl(it) } },
                    label = { Text("ベースURL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (apiSettings.isBaseUrlCustomized) {
                    TextButton(
                        onClick = { scope.launch { apiSettingsRepository.resetBaseUrlToServerDefault() } },
                    ) {
                        Text("既定のURLに戻す")
                    }
                }
                OutlinedTextField(
                    value = apiSettings.secretKey,
                    onValueChange = { scope.launch { apiSettingsRepository.setSecretKey(it) } },
                    label = { Text("シークレットキー (任意)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    "ベースURLは通常は変更不要です。運営側でURLが変更された場合は自動的に反映されます。自分のアカウント用に別のURLを使う場合のみ入力してください(手動で入力すると自動反映は止まります)。シークレットキーは通常は空欄のままで問題ありません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                SettingsSectionHeader("更新間隔")
                NumberStepper(
                    label = "${apiSettings.refreshIntervalSeconds.toInt()} 秒ごとに更新",
                    onDecrement = {
                        scope.launch {
                            apiSettingsRepository.setRefreshIntervalSeconds((apiSettings.refreshIntervalSeconds - 1).coerceAtLeast(3.0))
                        }
                    },
                    onIncrement = {
                        scope.launch {
                            apiSettingsRepository.setRefreshIntervalSeconds((apiSettings.refreshIntervalSeconds + 1).coerceAtMost(60.0))
                        }
                    },
                )
                Text(
                    "接続先のサーバーに負荷をかけるため、短くしすぎないでください。機体側の送信間隔もこれより速くはならないため、短くしても位置情報が特に速く更新されるわけではありません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                )
            }
        }
    }
}
