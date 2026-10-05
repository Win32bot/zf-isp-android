package kz.win32bot.zfisp

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // отладка интерфейса без программатора: adb shell am start ... --es demo 1E9306 --es fuses EC8F--FF
        if (BuildConfig.DEBUG) intent.getStringExtra("demo")?.let { vm.demo(it, intent.getStringExtra("fuses") ?: "") }
        setContent {
            AppTheme {
                LaunchedEffect(vm.busy) {
                    if (vm.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                Surface(color = MaterialTheme.colorScheme.background) { MainScreen(vm) }
            }
        }
    }
}

private val BrandLight = lightColorScheme(
    primary = Color(0xFF1E5AA8), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF), onPrimaryContainer = Color(0xFF001B3E),
    secondaryContainer = Color(0xFFDCE2F9), onSecondaryContainer = Color(0xFF151B2C),
)
private val BrandDark = darkColorScheme(
    primary = Color(0xFFAAC7FF), onPrimary = Color(0xFF002F65),
    primaryContainer = Color(0xFF1E4D8C), onPrimaryContainer = Color(0xFFD6E3FF),
    secondaryContainer = Color(0xFF3E4759), onSecondaryContainer = Color(0xFFDCE2F9),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> BrandDark
        else -> BrandLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private val Mono = FontFamily.Monospace

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val pickFlash = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { vm.flashUri = uri; vm.flashName = vm.displayName(uri) }
    }
    val pickEep = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { vm.eepUri = uri; vm.eepName = vm.displayName(uri) }
    }
    val saveFlash = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.readFlashTo(uri)
    }
    val saveEep = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.readEepromTo(uri)
    }
    val enabled = !vm.busy

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ZF-ISP", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
        bottomBar = { BottomStatus(vm) },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ConnectCard(vm, enabled) }
            item { FusesCard(vm, enabled) }
            item {
                Section("Flash", vm.chip?.let { "${it.flash.size / 1024} КБ" }) {
                    FileRow(vm.flashName, enabled) { pickFlash.launch(arrayOf("*/*")) }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OptChip("Стереть", vm.optErase, enabled) { vm.optErase = it }
                        OptChip("Записать", vm.optWrite, enabled) { vm.optWrite = it }
                        OptChip("Проверить", vm.optVerify, enabled) { vm.optVerify = it }
                    }
                    Button(onClick = vm::flash, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Прошить") }
                    Buttons {
                        OutlinedButton(onClick = vm::verifyOnly, enabled = enabled) { Text("Проверить") }
                        OutlinedButton(onClick = { saveFlash.launch("flash.hex") }, enabled = enabled) { Text("Прочитать в файл") }
                        OutlinedButton(
                            onClick = vm::erase, enabled = enabled,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("Стереть чип") }
                    }
                }
            }
            item {
                val ee = vm.chip?.let { c -> c.eeprom?.let { "${it.size} байт" } ?: "нет" }
                Section("EEPROM", ee) {
                    FileRow(vm.eepName, enabled) { pickEep.launch(arrayOf("*/*")) }
                    Button(onClick = vm::writeEeprom, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Записать EEPROM") }
                    Buttons {
                        OutlinedButton(onClick = vm::verifyEeprom, enabled = enabled) { Text("Проверить") }
                        OutlinedButton(onClick = { saveEep.launch("eeprom.eep") }, enabled = enabled) { Text("Прочитать в файл") }
                    }
                }
            }
            item {
                Section("Журнал") {
                    Text(
                        vm.log.takeLast(60).joinToString("\n"),
                        fontFamily = Mono, fontSize = 11.sp, lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    vm.confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { vm.confirm = null },
            title = { Text(c.title) },
            text = { Text(c.text) },
            confirmButton = { TextButton(onClick = { vm.confirm = null; c.onYes() }) { Text("Да") } },
            dismissButton = { TextButton(onClick = { vm.confirm = null }) { Text("Отмена") } },
        )
    }
    vm.error?.let { e ->
        AlertDialog(
            onDismissRequest = { vm.error = null },
            title = { Text("ZF-ISP") },
            text = { Text(e) },
            confirmButton = { TextButton(onClick = { vm.error = null }) { Text("OK") } },
        )
    }
}

@Composable
fun BottomStatus(vm: MainViewModel) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (vm.busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
                        Text(vm.progressText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    FilledTonalButton(onClick = vm::stop) { Text("Стоп") }
                }
            }
            Text(vm.status, fontSize = 13.sp, maxLines = 2)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectCard(vm: MainViewModel, enabled: Boolean) {
    Section("Программатор и чип") {
        Text("Скорость ISP", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            for (s in 1..5) {
                SegmentedButton(
                    selected = vm.speed == s, onClick = { vm.speed = s }, enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(s - 1, 5),
                    icon = {},
                ) { Text("$s") }
            }
        }
        Text(
            "1 — быстро · 5 — медленно (для нового чипа на 1 МГц)",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = vm::connect, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Подключить / прочитать") }
        val c = vm.chip
        Surface(
            color = if (c != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (c == null) {
                    Text("Чип не определён", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "сигнатура ${c.sigText} · flash ${c.flash.size / 1024} КБ" +
                            (c.eeprom?.let { " · EEPROM ${it.size} Б" } ?: ""),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
fun FusesCard(vm: MainViewModel, enabled: Boolean) {
    val c = vm.chip
    val vals = listOf("low", "high", "ext").associateWith { vm.parseHex(vm.fuseText[it]) }
    val (errs, warns) = fuseDanger(c, vals)
    Section("Фьюзы и Lock", "выделено = 0 (запрограммирован)") {
        if (c == null) {
            Text("Нажмите «Подключить / прочитать»", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Section
        }
        for (k in FUSE_KEYS) if (k in c.fuses) FuseEditor(vm, c, k, enabled)
        if (errs.isNotEmpty() || warns.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (errs + warns).forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::writeFuses, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Записать фьюзы") }
            FilledTonalButton(onClick = vm::writeLock, enabled = enabled && "lock" in c.fuses, modifier = Modifier.weight(1f)) { Text("Записать Lock") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FuseEditor(vm: MainViewModel, c: Chip, k: String, enabled: Boolean) {
    val names = c.bitNames(k)
    val mask = c.fuses.getValue(k).mask
    val v = vm.parseHex(vm.fuseText[k])
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.title(k), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(binary(v, mask), fontFamily = Mono, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HexField(vm.fuseText[k] ?: "", enabled) { vm.fuseText[k] = it }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                for (i in 0 until 8) {
                    val bit = 7 - i
                    if ((mask shr bit) and 1 == 0) continue
                    val name = names[i].ifEmpty { "бит $bit" }
                    val programmed = v != null && (v shr bit) and 1 == 0
                    FilterChip(
                        selected = programmed,
                        onClick = { vm.fuseText[k] = "%02X".format((v ?: 0xFF) xor (1 shl bit)) },
                        enabled = enabled,
                        label = { Text(name, fontSize = 12.sp, fontFamily = Mono, fontWeight = if (programmed) FontWeight.Bold else FontWeight.Normal) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
        }
    }
}

/** "1110 1100" с прочерками для неиспользуемых битов */
fun binary(v: Int?, mask: Int): String {
    if (v == null) return "–––– ––––"
    return (7 downTo 0).joinToString("") { b ->
        (if ((mask shr b) and 1 == 0) "·" else ((v shr b) and 1).toString()) + if (b == 4) " " else ""
    }
}

@Composable
fun HexField(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(Modifier.width(76.dp).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            BasicTextField(
                value = value,
                onValueChange = { s -> onChange(s.uppercase().filter { it in "0123456789ABCDEF" }.take(2)) },
                enabled = enabled, singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = Mono, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun Section(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(title) }
                    if (subtitle != null) withStyle(SpanStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        append("   $subtitle")
                    }
                },
                style = MaterialTheme.typography.titleMedium,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Buttons(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
fun FileRow(name: String, enabled: Boolean, onPick: () -> Unit) {
    OutlinedButton(onClick = onPick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(if (name.isEmpty()) "Выбрать HEX-файл…" else "📄 $name", maxLines = 1)
    }
}

@Composable
fun OptChip(text: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = checked, onClick = { onChange(!checked) }, enabled = enabled, label = { Text(text) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    )
}
