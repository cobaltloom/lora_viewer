package com.cobaltloom.loraviewer.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cobaltloom.loraviewer.data.alert.AlertReferenceField
import com.cobaltloom.loraviewer.data.alert.AltitudeCalculationMode
import com.cobaltloom.loraviewer.data.alert.AltitudeStep
import com.cobaltloom.loraviewer.data.alert.CompetitionTaskCourseData
import com.cobaltloom.loraviewer.data.alert.UpperAltitudeGuideline
import com.cobaltloom.loraviewer.data.alert.UpperCeilingMode
import com.cobaltloom.loraviewer.data.remote.OgnApiClient
import com.cobaltloom.loraviewer.data.settings.DataSource
import com.cobaltloom.loraviewer.ui.map.GliderTrackerUiState
import com.cobaltloom.loraviewer.ui.map.GliderTrackerViewModel

/** The settings pages; null in [SettingsScreen]'s state is the index. */
private enum class SettingsPage(val title: String) {
    MAP("地図の表示"),
    TASK("タスクコース・周回時間"),
    GROUND("地上判定(共通)"),
    LOW_ALTITUDE("高度不足アラート"),
    GUIDELINE("競技会ガイドライン"),
    UPPER_ALTITUDE("上限高度アラート"),
    PROXIMITY("機体接近アラート"),
}

/**
 * The settings index - one row per category, each showing its current state so a setting left on
 * (or off) is visible without opening every page - and the pages themselves. Pages are local state
 * rather than nav routes; system back returns from a page to the index.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: GliderTrackerViewModel,
    onBack: () -> Unit,
    onRequireSubscription: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteAllStepsConfirmation by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    BackHandler(enabled = page != null) { page = null }

    val alertSettings = uiState.alertSettings
    val upperAltitudeSettings = uiState.upperAltitudeSettings
    val proximityAlertSettings = uiState.proximityAlertSettings
    val competitionGuidelineSettings = uiState.competitionGuidelineSettings

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page?.title ?: "設定") },
                navigationIcon = {
                    IconButton(onClick = { if (page != null) page = null else onBack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).padding(horizontal = 16.dp)) {
            when (page) {
                null -> {
            if (uiState.isOgnAvailable) {
                item {
                    SettingsSectionHeader("位置情報のデータ元")
                    Row(modifier = Modifier.fillMaxWidth()) {
                        DataSource.entries.forEachIndexed { i, source ->
                            FilterChip(
                                selected = uiState.dataSource == source,
                                onClick = { viewModel.setDataSource(source) },
                                label = { Text(source.displayName) },
                                modifier = Modifier.weight(1f).padding(start = if (i == 0) 0.dp else 8.dp),
                            )
                        }
                    }
                    Text(
                        "LoRa: クラブのLoRa発信機を積んだ機体を表示します。\nFLARM: FLARMやOGNトラッカーを積み、OGN(Open Glider Network)受信機の範囲内(妻沼から約150km)にいる機体を表示します(${OgnApiClient.ATTRIBUTION})。追跡拒否・匿名・ステルス設定の機体と旅客機は表示しません。機体名の非公開を希望している機体は「非公開機」として位置のみ表示します。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                }
            }
            item {
                SettingsSectionHeader("地図")
                SettingsNavRow("地図の表示", mapDisplaySummary(uiState)) { page = SettingsPage.MAP }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                SettingsSectionHeader("競技")
                SettingsNavRow("タスクコース・周回時間", proSummary(uiState, taskCourseSummary(uiState))) { page = SettingsPage.TASK }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                SettingsSectionHeader("アラート")
                SettingsNavRow("地上判定(共通)", "${alertSettings.minimumFlyingAltitudeM.toInt()}m以下") { page = SettingsPage.GROUND }
                SettingsNavRow(
                    "高度不足(カスタム)",
                    proSummary(
                        uiState,
                        when {
                            !alertSettings.isEnabled -> "オフ"
                            alertSettings.mode == AltitudeCalculationMode.STEPS -> "オン(距離段階)"
                            else -> "オン(L/D)"
                        },
                    ),
                ) { page = SettingsPage.LOW_ALTITUDE }
                SettingsNavRow("競技会ガイドライン", proSummary(uiState, onOff(competitionGuidelineSettings.isEnabled))) {
                    page = SettingsPage.GUIDELINE
                }
                SettingsNavRow(
                    "上限高度",
                    proSummary(
                        uiState,
                        when {
                            !upperAltitudeSettings.isEnabled -> "オフ"
                            upperAltitudeSettings.mode == UpperCeilingMode.AUTO -> "オン(自動)"
                            else -> "オン(競技会 ${upperAltitudeSettings.competitionCeilingFt.toInt()}ft)"
                        },
                    ),
                ) { page = SettingsPage.UPPER_ALTITUDE }
                SettingsNavRow("機体接近", proSummary(uiState, onOff(proximityAlertSettings.isEnabled))) { page = SettingsPage.PROXIMITY }
                if (!uiState.isSubscribed) {
                    Text(
                        "🔒 はプラス機能です。購読すると有効にできます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                SettingsSectionHeader("その他")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onOpenAdvancedSettings() }.padding(vertical = 12.dp),
                ) {
                    Text("高度な設定(更新間隔・サーバー)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
                }
                SettingsPage.MAP -> {
            item {
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
            }
            item {
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
            }
            item {
                ProSectionHeader("民間訓練試験空域(KK4-3)", uiState.isSubscribed, onRequireSubscription)
                SwitchRow(
                    label = "表示する",
                    checked = uiState.showKk43Area,
                    onCheckedChange = {
                        if (uiState.isSubscribed) viewModel.setShowKk43Area(it) else onRequireSubscription()
                    },
                )
                Text(
                    "国土交通省AIP(ENR 5.3-15)掲載の民間訓練試験空域KK4-3(地表〜2,000ft)を地図上に表示します。B区域の一部と重なる空域です。境界は新幹線・高速道路等の実際の経路をもとに再現していますが、一部区間は直線で近似した参考表示であり、実際の判断の根拠にはしないでください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }
                }
                SettingsPage.TASK -> {
            item {
                ProSectionHeader("タスクコース", uiState.isSubscribed, onRequireSubscription)
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
                    "その日のコースを選ぶと、旋回点の通過通知・通過履歴に加えて、各機体の周回時間(スタートライン通過〜全旋回点〜ゴールライン通過)を計算し、詳細カード・マーカー・通過履歴に表示します。ゴールしたときは通知します。関東学生グライダー競技会の競技規則(Ver.3-2026-09-28)・細則(Ver.2026-09-28)に基づく参考値で、公式の判定(各機のGPSロガー)とは数秒〜十数秒ずれることがあります。旋回点・コースは公式資料に掲載された固定の座標です。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
                }
                SettingsPage.GROUND -> {
            item {
                SettingsSectionHeader("地上判定(共通)")
                Text(
                    "この高度以下は駐機中・着陸後とみなし、距離に関わらず高度不足アラート・競技会ガイドラインのアラートを出しません。軌跡もこの高度以下で消去されます。",
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
            }
                }
                SettingsPage.LOW_ALTITUDE -> {
            item {
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
                    "妻沼滑空場中心から15km以内の機体だけが対象です。基準地点は、競技会ガイドラインと同じ第1滑空場基準か、第2滑空場基準かを選べます。あくまで目安であり、実際の判断の根拠にはしないでください。高度は本サイトが提供する値(海抜高)をそのまま使っています。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
                }
                SettingsPage.GUIDELINE -> {
            item {
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
                Text(
                    "妻沼滑空場中心から15km以内の機体だけが対象です。日本学生航空連盟(JSAL)妻沼滑空場の公式ガイドライン(Ver.2026-01-26)。滑空場中心から2.5km未満は制限なし、2.5〜3kmでMSL350m以上、以降1kmごとに70mずつ増加し、10km以上でMSL910m以上が必要です。公式資料に基づく固定値のため、数値はここでは変更できません。旋回点・タスクコースの表示は「タスクコース・周回時間」で設定します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
                }
                SettingsPage.UPPER_ALTITUDE -> {
            item {
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
                        label = { Text("自動(平日/土日)") },
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
                if (upperAltitudeSettings.mode == UpperCeilingMode.COMPETITION) {
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
            }
                }
                SettingsPage.PROXIMITY -> {
            item {
                ProSectionHeader("機体接近アラート", uiState.isSubscribed, onRequireSubscription)
                SwitchRow(
                    label = "有効にする",
                    checked = proximityAlertSettings.isEnabled,
                    onCheckedChange = {
                        if (uiState.isSubscribed) {
                            viewModel.updateProximityAlertSettings(proximityAlertSettings.copy(isEnabled = it))
                        } else {
                            onRequireSubscription()
                        }
                    },
                )
                NumberStepper(
                    label = "注意(地図表示のみ): 水平距離 ${proximityAlertSettings.cautionDistanceM.toInt()} m 以内",
                    onDecrement = {
                        viewModel.updateProximityAlertSettings(
                            proximityAlertSettings.copy(cautionDistanceM = (proximityAlertSettings.cautionDistanceM - 50).coerceAtLeast(50.0)),
                        )
                    },
                    onIncrement = {
                        viewModel.updateProximityAlertSettings(
                            proximityAlertSettings.copy(cautionDistanceM = (proximityAlertSettings.cautionDistanceM + 50).coerceAtMost(2000.0)),
                        )
                    },
                )
                NumberStepper(
                    label = "警告(通知): 水平距離 ${proximityAlertSettings.warningDistanceM.toInt()} m 以内",
                    onDecrement = {
                        viewModel.updateProximityAlertSettings(
                            proximityAlertSettings.copy(warningDistanceM = (proximityAlertSettings.warningDistanceM - 10).coerceAtLeast(30.0)),
                        )
                    },
                    onIncrement = {
                        viewModel.updateProximityAlertSettings(
                            proximityAlertSettings.copy(warningDistanceM = (proximityAlertSettings.warningDistanceM + 10).coerceAtMost(1000.0)),
                        )
                    },
                )
                NumberStepper(
                    label = "高度差 ${proximityAlertSettings.maxAltitudeDifferenceM.toInt()} m 以内のみ対象",
                    onDecrement = {
                        viewModel.updateProximityAlertSettings(
                            proximityAlertSettings.copy(
                                maxAltitudeDifferenceM = (proximityAlertSettings.maxAltitudeDifferenceM - 10).coerceAtLeast(10.0),
                            ),
                        )
                    },
                    onIncrement = {
                        viewModel.updateProximityAlertSettings(
                            proximityAlertSettings.copy(
                                maxAltitudeDifferenceM = (proximityAlertSettings.maxAltitudeDifferenceM + 10).coerceAtMost(500.0),
                            ),
                        )
                    },
                )
                NumberStepper(
                    label = "場周除外: 基準地点から %.1f km 以内".format(proximityAlertSettings.patternExclusionRadiusKm),
                    onDecrement = {
                        viewModel.updateProximityAlertSettings(
                            proximityAlertSettings.copy(
                                patternExclusionRadiusKm = (proximityAlertSettings.patternExclusionRadiusKm - 0.5).coerceAtLeast(0.5),
                            ),
                        )
                    },
                    onIncrement = {
                        viewModel.updateProximityAlertSettings(
                            proximityAlertSettings.copy(
                                patternExclusionRadiusKm = (proximityAlertSettings.patternExclusionRadiusKm + 0.5).coerceAtMost(5.0),
                            ),
                        )
                    },
                )
                NumberStepper(
                    label = "場周除外: 高度 ${proximityAlertSettings.patternExclusionCeilingM.toInt()} m 以下",
                    onDecrement = {
                        viewModel.updateProximityAlertSettings(
                            proximityAlertSettings.copy(
                                patternExclusionCeilingM = (proximityAlertSettings.patternExclusionCeilingM - 50).coerceAtLeast(50.0),
                            ),
                        )
                    },
                    onIncrement = {
                        viewModel.updateProximityAlertSettings(
                            proximityAlertSettings.copy(
                                patternExclusionCeilingM = (proximityAlertSettings.patternExclusionCeilingM + 50).coerceAtMost(3000.0),
                            ),
                        )
                    },
                )
                Text(
                    "水平距離が近く、高度差も小さい機体同士を検知します。地図上の色分け(注意)は距離だけで表示しますが、プッシュ通知(警告)は距離が縮まり続けている場合のみ送ります。サーマルで複数機が近接して旋回するのは通常のことなので、離れつつある/一定の距離を保っている場合は通知しません。「場周除外」は、基準地点付近かつ低高度(場周経路)にいる機体同士を注意表示のみにとどめ、通知を出さないようにする設定です。着陸のたびに追従・近接するのは正常な状態のため、そこを検知対象から外します。位置情報は数秒〜数十秒間隔のポーリングによるものであり、リアルタイムのGPSではないため、あくまで参考情報です。実際の見張り・衝突回避の代わりにはなりません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
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

private fun onOff(isOn: Boolean) = if (isOn) "オン" else "オフ"

/** Pro features read as locked without a subscription, whatever their stored setting says. */
private fun proSummary(uiState: GliderTrackerUiState, summary: String) = if (uiState.isSubscribed) summary else "🔒"

private fun mapDisplaySummary(uiState: GliderTrackerUiState): String {
    val items = buildList {
        if (uiState.showGliderTrails) add("軌跡")
        if (uiState.showDistanceReferencePoints && uiState.isSubscribed) add("目標地点")
        if (uiState.showKk43Area && uiState.isSubscribed) add("KK4-3")
    }
    return items.ifEmpty { listOf("なし") }.joinToString("・")
}

private fun taskCourseSummary(uiState: GliderTrackerUiState): String {
    val settings = uiState.competitionGuidelineSettings
    if (!settings.showTaskCourse) return "オフ"
    val course = settings.selectedCourseIndex?.let { CompetitionTaskCourseData.courses.getOrNull(it) } ?: return "旋回点のみ"
    return course.name.take(1)
}

/** One index row: title on the left, current state and a chevron on the right. */
@Composable
private fun SettingsNavRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null)
    }
}


@Composable
internal fun SettingsSectionHeader(title: String) {
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
internal fun NumberStepper(label: String, onDecrement: () -> Unit, onIncrement: () -> Unit) {
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
