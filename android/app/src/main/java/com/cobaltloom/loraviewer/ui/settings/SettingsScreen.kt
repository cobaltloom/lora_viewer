package com.cobaltloom.loraviewer.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cobaltloom.loraviewer.data.alert.AlertReferenceField
import com.cobaltloom.loraviewer.data.alert.AltitudeCalculationMode
import com.cobaltloom.loraviewer.data.alert.AltitudeStep
import com.cobaltloom.loraviewer.data.alert.CompetitionTaskCourseData
import com.cobaltloom.loraviewer.data.alert.UpperAltitudeGuideline
import com.cobaltloom.loraviewer.data.alert.UpperCeilingMode
import com.cobaltloom.loraviewer.data.settings.ApiSettings
import com.cobaltloom.loraviewer.data.settings.ApiSettingsRepository
import com.cobaltloom.loraviewer.ui.map.GliderTrackerViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: GliderTrackerViewModel,
    apiSettingsRepository: ApiSettingsRepository,
    onBack: () -> Unit,
    onRequireSubscription: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val apiSettings by apiSettingsRepository.settings.collectAsState(
        initial = ApiSettings(ApiSettings.DEFAULT_BASE_URL, "", ApiSettings.DEFAULT_REFRESH_INTERVAL_SECONDS),
    )
    var showDeleteAllStepsConfirmation by remember { mutableStateOf(false) }

    val alertSettings = uiState.alertSettings
    val upperAltitudeSettings = uiState.upperAltitudeSettings
    val competitionGuidelineSettings = uiState.competitionGuidelineSettings

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "戻る") }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).padding(horizontal = 16.dp)) {
            item {
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
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                SettingsSectionHeader("地図表示")
                SwitchRow(
                    label = "軌跡を表示",
                    checked = uiState.showGliderTrails,
                    onCheckedChange = { viewModel.setShowGliderTrails(it) },
                )
                Text(
                    "機体の飛行軌跡をアプリ起動中の地図に表示します。着陸すると軌跡は消去されます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                SettingsSectionHeader("地上判定(共通)")
                Text(
                    "この高度以下は駐機中・着陸後とみなし、距離に関わらずアラートを出しません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NumberStepper(
                    label = "高度 ${alertSettings.minimumFlyingAltitudeM.toInt()} m 以下は地上とみなす",
                    onDecrement = {
                        viewModel.updateAlertSettings(
                            alertSettings.copy(minimumFlyingAltitudeM = (alertSettings.minimumFlyingAltitudeM - 10).coerceAtLeast(0.0)),
                        )
                    },
                    onIncrement = {
                        viewModel.updateAlertSettings(
                            alertSettings.copy(minimumFlyingAltitudeM = (alertSettings.minimumFlyingAltitudeM + 10).coerceAtMost(500.0)),
                        )
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                ProSectionHeader("地上目標地点", uiState.isSubscribed, onRequireSubscription)
                SwitchRow(
                    label = "表示する",
                    checked = uiState.showDistanceReferencePoints,
                    onCheckedChange = {
                        if (uiState.isSubscribed) viewModel.setShowDistanceReferencePoints(it) else onRequireSubscription()
                    },
                )
                Text(
                    "日本学生航空連盟(JSAL)妻沼滑空場の公式資料(図3、Ver.2026-01-26)掲載の19地点(妻沼滑空場中心点から3/5/7/9kmの目安目標)と、それに加えたローカルの目印を、実在の場所の座標で地図上に表示します。上限高度アラートとは独立した機能で、あくまで目安であり、実際の判断の根拠にはしないでください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                ProSectionHeader("高度不足アラート(カスタム設定)", uiState.isSubscribed, onRequireSubscription)
                SwitchRow(
                    label = "有効にする",
                    checked = alertSettings.isEnabled,
                    onCheckedChange = {
                        if (uiState.isSubscribed) viewModel.updateAlertSettings(alertSettings.copy(isEnabled = it)) else onRequireSubscription()
                    },
                )
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    FilterChip(
                        selected = alertSettings.referenceField == AlertReferenceField.FIELD_1,
                        onClick = { viewModel.updateAlertSettings(alertSettings.copy(referenceField = AlertReferenceField.FIELD_1)) },
                        label = { Text("第1滑空場基準") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = alertSettings.referenceField == AlertReferenceField.FIELD_2,
                        onClick = { viewModel.updateAlertSettings(alertSettings.copy(referenceField = AlertReferenceField.FIELD_2)) },
                        label = { Text("第2滑空場基準") },
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                }
                Text(
                    "%.5f, %.5f".format(alertSettings.referenceField.coordinate.latitude, alertSettings.referenceField.coordinate.longitude),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    FilterChip(
                        selected = alertSettings.mode == AltitudeCalculationMode.STEPS,
                        onClick = { viewModel.updateAlertSettings(alertSettings.copy(mode = AltitudeCalculationMode.STEPS)) },
                        label = { Text("距離ごとの段階") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = alertSettings.mode == AltitudeCalculationMode.GLIDE_RATIO,
                        onClick = { viewModel.updateAlertSettings(alertSettings.copy(mode = AltitudeCalculationMode.GLIDE_RATIO)) },
                        label = { Text("帰投高度とL/D") },
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                }
            }

            if (alertSettings.mode == AltitudeCalculationMode.STEPS) {
                items(alertSettings.steps.withIndex().toList(), key = { it.index }) { (index, step) ->
                    AltitudeStepRow(
                        step = step,
                        onChange = { newStep ->
                            val newSteps = alertSettings.steps.toMutableList().also { it[index] = newStep }
                            viewModel.updateAlertSettings(alertSettings.copy(steps = newSteps))
                        },
                        onDelete = {
                            val newSteps = alertSettings.steps.toMutableList().also { it.removeAt(index) }
                            viewModel.updateAlertSettings(alertSettings.copy(steps = newSteps))
                        },
                    )
                }
                item {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        TextButton(onClick = { viewModel.updateAlertSettings(alertSettings.withAddedStep()) }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text("段階を追加")
                        }
                        if (alertSettings.steps.isNotEmpty()) {
                            TextButton(onClick = { showDeleteAllStepsConfirmation = true }) {
                                Icon(Icons.Filled.Delete, contentDescription = null)
                                Text("すべて削除")
                            }
                        }
                    }
                }
            } else {
                item {
                    NumberStepper(
                        label = "基準地点での必要高度 ${alertSettings.arrivalAltitudeM.toInt()} m",
                        onDecrement = {
                            viewModel.updateAlertSettings(alertSettings.copy(arrivalAltitudeM = (alertSettings.arrivalAltitudeM - 10).coerceAtLeast(50.0)))
                        },
                        onIncrement = {
                            viewModel.updateAlertSettings(alertSettings.copy(arrivalAltitudeM = (alertSettings.arrivalAltitudeM + 10).coerceAtMost(3000.0)))
                        },
                    )
                    NumberStepper(
                        label = "警告の滑空比(L/D) ${alertSettings.warningGlideRatio.toInt()}",
                        onDecrement = {
                            viewModel.updateAlertSettings(alertSettings.copy(warningGlideRatio = (alertSettings.warningGlideRatio - 1).coerceAtLeast(5.0)))
                        },
                        onIncrement = {
                            viewModel.updateAlertSettings(alertSettings.copy(warningGlideRatio = (alertSettings.warningGlideRatio + 1).coerceAtMost(60.0)))
                        },
                    )
                    NumberStepper(
                        label = "注意の滑空比(L/D) ${alertSettings.cautionGlideRatio.toInt()}",
                        onDecrement = {
                            viewModel.updateAlertSettings(alertSettings.copy(cautionGlideRatio = (alertSettings.cautionGlideRatio - 1).coerceAtLeast(5.0)))
                        },
                        onIncrement = {
                            viewModel.updateAlertSettings(alertSettings.copy(cautionGlideRatio = (alertSettings.cautionGlideRatio + 1).coerceAtMost(60.0)))
                        },
                    )
                }
            }

            item {
                Text(
                    "基準地点は、競技会ガイドラインと同じ第1滑空場基準か、第2滑空場基準かを選べます。あくまで目安であり、実際の判断の根拠にはしないでください。高度は本サイトが提供する値(海抜高)をそのまま使っています。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                ProSectionHeader("競技会ガイドライン(妻沼滑空場)", uiState.isSubscribed, onRequireSubscription)
                SwitchRow(
                    label = "有効にする",
                    checked = competitionGuidelineSettings.isEnabled,
                    onCheckedChange = {
                        if (uiState.isSubscribed) {
                            viewModel.updateCompetitionGuidelineSettings(competitionGuidelineSettings.copy(isEnabled = it))
                        } else {
                            onRequireSubscription()
                        }
                    },
                )
                SwitchRow(
                    label = "旋回点・タスクコースを表示",
                    checked = competitionGuidelineSettings.showTaskCourse,
                    onCheckedChange = {
                        if (uiState.isSubscribed) {
                            viewModel.updateCompetitionGuidelineSettings(competitionGuidelineSettings.copy(showTaskCourse = it))
                        } else {
                            onRequireSubscription()
                        }
                    },
                )
                if (competitionGuidelineSettings.showTaskCourse) {
                    TaskCoursePicker(
                        selectedCourseIndex = competitionGuidelineSettings.selectedCourseIndex,
                        onSelect = { index ->
                            viewModel.updateCompetitionGuidelineSettings(competitionGuidelineSettings.copy(selectedCourseIndex = index))
                        },
                    )
                }
                Text(
                    "日本学生航空連盟(JSAL)妻沼滑空場の公式ガイドライン(Ver.2026-01-26)。滑空場中心から2.5km未満は制限なし、2.5〜3kmでMSL350m以上、以降1kmごとに70mずつ増加し、10km以上でMSL910m以上が必要です。公式資料に基づく固定値のため、数値はここでは変更できません。旋回点・タスクコースは同資料に掲載された固定の座標・コースを地図上に表示するもので、実際のタスクファイル(スタート/ゴールラインの向きなど)を再現するものではありません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                ProSectionHeader("上限高度アラート(妻沼滑空場)", uiState.isSubscribed, onRequireSubscription)
                SwitchRow(
                    label = "有効にする",
                    checked = upperAltitudeSettings.isEnabled,
                    onCheckedChange = {
                        if (uiState.isSubscribed) {
                            viewModel.updateUpperAltitudeSettings(upperAltitudeSettings.copy(isEnabled = it))
                        } else {
                            onRequireSubscription()
                        }
                    },
                )
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    FilterChip(
                        selected = upperAltitudeSettings.mode == UpperCeilingMode.AUTO,
                        onClick = { viewModel.updateUpperAltitudeSettings(upperAltitudeSettings.copy(mode = UpperCeilingMode.AUTO)) },
                        label = { Text("自動(平日/土日祝)") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = upperAltitudeSettings.mode == UpperCeilingMode.COMPETITION,
                        onClick = {
                            viewModel.updateUpperAltitudeSettings(upperAltitudeSettings.copy(mode = UpperCeilingMode.COMPETITION))
                        },
                        label = { Text("競技会中(手動指定)") },
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                }
                if (upperAltitudeSettings.mode == UpperCeilingMode.AUTO) {
                    SwitchRow(
                        label = "今日を祝日として扱う",
                        checked = upperAltitudeSettings.treatTodayAsHoliday,
                        onCheckedChange = { viewModel.updateUpperAltitudeSettings(upperAltitudeSettings.copy(treatTodayAsHoliday = it)) },
                    )
                } else {
                    NumberStepper(
                        label = "競技会中の上限 ${upperAltitudeSettings.competitionCeilingFt.toInt()} ft MSL",
                        onDecrement = {
                            viewModel.updateUpperAltitudeSettings(
                                upperAltitudeSettings.copy(
                                    competitionCeilingFt = (upperAltitudeSettings.competitionCeilingFt - 100).coerceAtLeast(500.0),
                                ),
                            )
                        },
                        onIncrement = {
                            viewModel.updateUpperAltitudeSettings(
                                upperAltitudeSettings.copy(
                                    competitionCeilingFt = (upperAltitudeSettings.competitionCeilingFt + 100).coerceAtMost(10000.0),
                                ),
                            )
                        },
                    )
                }
                Text(
                    "A区域の上限: ${UpperAltitudeGuideline.ZONE_A_CEILING_FT.toInt()} ft MSL",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "B区域の上限(本日): ${upperAltitudeSettings.bZoneCeilingFt.toInt()} ft MSL",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "公式資料のA区域・B区域の境界に基づき、区域内でその上限高度を超えるとアラートを出します。あくまで目安であり、実際の判断の根拠にはしないでください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                SettingsSectionHeader("サーバー")
                OutlinedTextField(
                    value = apiSettings.baseUrl,
                    onValueChange = { scope.launch { apiSettingsRepository.setBaseUrl(it) } },
                    label = { Text("ベースURL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = apiSettings.secretKey,
                    onValueChange = { scope.launch { apiSettingsRepository.setSecretKey(it) } },
                    label = { Text("シークレットキー (任意)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
                )
            }
        }
    }

    if (showDeleteAllStepsConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteAllStepsConfirmation = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateAlertSettings(alertSettings.copy(steps = emptyList()))
                    showDeleteAllStepsConfirmation = false
                }) { Text("すべて削除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteAllStepsConfirmation = false }) { Text("キャンセル") } },
            title = { Text("段階をすべて削除しますか?") },
            text = { Text("追加した段階がすべて削除されます。この操作は取り消せません。") },
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

/** A section header for a subscription-gated feature; shows a "会員限定" badge when unsubscribed. */
@Composable
private fun ProSectionHeader(title: String, isSubscribed: Boolean, onRequireSubscription: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (!isSubscribed) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small,
                onClick = onRequireSubscription,
            ) {
                Text(
                    "会員限定",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Picks which published turnpoint course (or none, just the turnpoints) to draw on the map. */
@Composable
private fun TaskCoursePicker(selectedCourseIndex: Int?, onSelect: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = selectedCourseIndex
        ?.let { CompetitionTaskCourseData.courses.getOrNull(it) }
        ?.let { "${it.name}(%.1fkm)".format(it.distanceKm) }
        ?: "旋回点のみ"

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "表示するコース",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { expanded = true }.padding(vertical = 8.dp),
            ) {
                Text(selectedLabel, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("旋回点のみ") },
                    onClick = { onSelect(null); expanded = false },
                )
                CompetitionTaskCourseData.courses.forEachIndexed { index, course ->
                    DropdownMenuItem(
                        text = { Text("${course.name}(%.1fkm)".format(course.distanceKm)) },
                        onClick = { onSelect(index); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun NumberStepper(label: String, onDecrement: () -> Unit, onIncrement: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = onDecrement) { Icon(Icons.Filled.Remove, contentDescription = "減らす") }
        IconButton(onClick = onIncrement) { Icon(Icons.Filled.Add, contentDescription = "増やす") }
    }
}

@Composable
private fun AltitudeStepRow(step: AltitudeStep, onChange: (AltitudeStep) -> Unit, onDelete: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        NumberStepper(
            label = "基準地点から %.1f km 以上".format(step.distanceKm),
            onDecrement = { onChange(step.copy(distanceKm = (step.distanceKm - 0.5).coerceAtLeast(0.5))) },
            onIncrement = { onChange(step.copy(distanceKm = (step.distanceKm + 0.5).coerceAtMost(50.0))) },
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "高度 ${step.minimumAltitudeM.toInt()} m 未満で警告",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(onClick = { onChange(step.copy(minimumAltitudeM = (step.minimumAltitudeM - 10).coerceAtLeast(50.0))) }) {
                Icon(Icons.Filled.Remove, contentDescription = "減らす")
            }
            IconButton(onClick = { onChange(step.copy(minimumAltitudeM = (step.minimumAltitudeM + 10).coerceAtMost(3000.0))) }) {
                Icon(Icons.Filled.Add, contentDescription = "増やす")
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "削除") }
        }
    }
    HorizontalDivider()
}
