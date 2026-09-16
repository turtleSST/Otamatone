@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.tadpole.instrument.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tadpole.instrument.model.*
import java.util.Locale

@Composable fun SettingsScreen(settings: InstrumentSettings,onChange: (InstrumentSettings) -> Unit,onDismiss: () -> Unit) {
    val sheet=rememberModalBottomSheetState(skipPartiallyExpanded=true)
    val focusManager=LocalFocusManager.current
    var resetGeneration by rememberSaveable { mutableIntStateOf(0) }
    var showCalibration by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=sheet,containerColor=Paper) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=32.dp).testTag("settingsSheet")) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                Text("设置",style=MaterialTheme.typography.titleLarge)
                TextButton(onClick={focusManager.clearFocus();onDismiss()},modifier=Modifier.testTag("settingsDone")) {Text("完成")}
            }
            Section("音量") {
                SettingSlider("主音量",settings.masterVolume,0f..1f,"${(settings.masterVolume*100).toInt()}%") {onChange(settings.copy(masterVolume=it))}
                Text("按住头部张嘴增大音量，向下拖动可减小；松手后闭合。",fontSize=12.sp,color=Muted)
            }
            Section("手机外放音色补偿") {
                SettingSlider("低频",settings.bassDb,-6f..9f,String.format(Locale.US,"%+.1f dB",settings.bassDb)) {onChange(settings.copy(bassDb=it))}
                SettingSlider("高频",settings.trebleDb,-6f..9f,String.format(Locale.US,"%+.1f dB",settings.trebleDb)) {onChange(settings.copy(trebleDb=it))}
                TextButton(onClick={onChange(settings.withTone(0f,0f))},modifier=Modifier.testTag("neutralTone")) {Text("补偿归零")}
                Text("默认归零，对应开嘴录音音色。可按手机外放效果微调；开合头部仅改变音量。",fontSize=11.sp,color=Muted)
            }
            Section("滑音响应") {
                Choices(Smoothing.entries,settings.smoothing,{when(it) {Smoothing.QUICK->"灵敏";Smoothing.BALANCED->"均衡";Smoothing.SOFT->"柔和"}}) {onChange(settings.copy(smoothing=it))}
            }
            Row(Modifier.fillMaxWidth().padding(top=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                Text("轻触反馈")
                Switch(checked=settings.haptic,onCheckedChange={onChange(settings.copy(haptic=it))})
            }
            TextButton(onClick={focusManager.clearFocus();showCalibration=!showCalibration},modifier=Modifier.testTag("toggleCalibration")) {
                Text(if(showCalibration) "收起音域校准" else "音域校准")
            }
            if(showCalibration) Section("当前档位的琴杆位置") {
                Choices(PitchRange.entries,settings.range,{it.name}) {focusManager.clearFocus();onChange(settings.withRange(it))}
                key(settings.range, resetGeneration) {
                    CalibrationFields(settings.calibration) { value ->
                        focusManager.clearFocus()
                        onChange(settings.withCalibration(value))
                    }
                }
                Text("上端是低音，下端是高音。默认使用三档录音的滑音曲线；手动填写实物三个位置的频率可覆盖当前档位。",fontSize=11.sp,color=Muted)
                TextButton(onClick={focusManager.clearFocus();resetGeneration++;onChange(settings.withCalibration(PitchCalibration.forRange(settings.range)))}) {Text("恢复当前档位实测音域")}
            }
            OutlinedButton(onClick={focusManager.clearFocus();resetGeneration++;onChange(InstrumentSettings())},modifier=Modifier.fillMaxWidth().height(48.dp).testTag("resetDefaults")) {Text("恢复默认设置")}
            Spacer(Modifier.height(18.dp))
            Text("音名以 A4 = 440 Hz 为准；右侧标记为自然音，左侧短刻度为升降半音，方便左手按弦时查看音名。标记随档位和音域校准更新。\n长按设置图标可查看音频调试信息。\n完全离线 · 无广告 · 无需任何权限",fontSize=11.sp,lineHeight=18.sp,color=Muted)
        }
    }
}

@Composable private fun CalibrationFields(current: PitchCalibration, onApply: (PitchCalibration) -> Unit) {
    var top by remember { mutableStateOf(frequencyText(current.topHz)) }
    var middle by remember { mutableStateOf(frequencyText(current.middleHz)) }
    var bottom by remember { mutableStateOf(frequencyText(current.bottomHz)) }
    var edited by remember { mutableStateOf(false) }
    val a=top.toFloatOrNull();val b=middle.toFloatOrNull();val c=bottom.toFloatOrNull()
    val valid=a!=null && b!=null && c!=null && a.isFinite() && b.isFinite() && c.isFinite() &&
        a in 20f..4000f && b>a+.01f && c>b+.01f && c<=8000f
    LaunchedEffect(current) {
        top=frequencyText(current.topHz)
        middle=frequencyText(current.middleHz)
        bottom=frequencyText(current.bottomHz)
        edited=false
    }
    val keyboard=KeyboardOptions(keyboardType=KeyboardType.Decimal)
    OutlinedTextField(value=top,onValueChange={top=it;edited=true},label={Text("上端 · 低音 / Hz")},
        singleLine=true,keyboardOptions=keyboard,modifier=Modifier.fillMaxWidth().testTag("calibrationTop"))
    OutlinedTextField(value=middle,onValueChange={middle=it;edited=true},label={Text("琴杆中点 / Hz")},
        singleLine=true,keyboardOptions=keyboard,modifier=Modifier.fillMaxWidth().testTag("calibrationMiddle"))
    OutlinedTextField(value=bottom,onValueChange={bottom=it;edited=true},label={Text("下端 · 高音 / Hz")},
        singleLine=true,keyboardOptions=keyboard,modifier=Modifier.fillMaxWidth().testTag("calibrationBottom"))
    if(edited && !valid) Text("需要满足：20 ≤ 上端 < 中点 < 下端 ≤ 8000 Hz；上端不超过 4000 Hz。",fontSize=11.sp,color=MaterialTheme.colorScheme.error)
    Button(onClick={onApply(PitchCalibration(a!!,b!!,c!!));edited=false},enabled=valid && edited,
        modifier=Modifier.fillMaxWidth().testTag("applyCalibration")) {Text("应用当前档位标定")}
}

private fun frequencyText(value: Float) = String.format(Locale.US,"%.2f",value).trimEnd('0').trimEnd('.')

@Composable private fun Section(title: String,content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top=23.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,fontSize=11.sp,color=Muted)
        content()
    }
}
@Composable private fun <T> Choices(values: List<T>,selected: T,label: (T) -> String,onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        values.forEach {value -> FilterChip(selected=selected==value,onClick={onSelect(value)},label={Text(label(value),fontSize=11.sp)})}
    }
}
@Composable private fun SettingSlider(label: String,value: Float,range: ClosedFloatingPointRange<Float>,readout: String,onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(label,fontSize=13.sp);Text(readout,fontSize=12.sp,color=Muted)}
        Slider(value=value,onValueChange=onChange,valueRange=range)
    }
}
