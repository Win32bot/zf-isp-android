package kz.win32bot.zfisp

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Confirm(val title: String, val text: String, val onYes: () -> Unit)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    val chips: Map<List<Int>, Chip> = Chips.parse(app.assets.open("chips.json").bufferedReader().readText())

    var speed by mutableStateOf(1)
    var chip by mutableStateOf<Chip?>(null)
    var chipLabel by mutableStateOf("—")
    val fuseText = mutableStateMapOf<String, String>()

    var flashUri by mutableStateOf<Uri?>(null)
    var flashName by mutableStateOf("")
    var eepUri by mutableStateOf<Uri?>(null)
    var eepName by mutableStateOf("")
    var optErase by mutableStateOf(true)
    var optWrite by mutableStateOf(true)
    var optVerify by mutableStateOf(true)

    var busy by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    var progressText by mutableStateOf("")
    var status by mutableStateOf("Подключите программатор через OTG")
    val log = mutableStateListOf<String>()
    var confirm by mutableStateOf<Confirm?>(null)
    var error by mutableStateOf<String?>(null)

    private var job: Job? = null
    private var pendingAfterPermission: (() -> Unit)? = null
    private val ACTION_PERM = "kz.win32bot.zfisp.USB_PERMISSION"

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != ACTION_PERM) return
            val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val action = pendingAfterPermission
            pendingAfterPermission = null
            if (granted) action?.invoke() else status = "Нет разрешения на доступ к программатору"
        }
    }

    init {
        val f = IntentFilter(ACTION_PERM)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(permReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") ctx.registerReceiver(permReceiver, f)
        logMsg("ZF-ISP: ${chips.size} чипов AVR в таблице")
    }

    override fun onCleared() {
        try { ctx.unregisterReceiver(permReceiver) } catch (_: Exception) {}
    }

    fun logMsg(s: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        log.add("$t  $s")
        if (log.size > 300) log.removeRange(0, log.size - 300)
    }

    fun findDevice(): UsbDevice? = usb.deviceList.values.firstOrNull { it.vendorId == ZF_VID && it.productId == ZF_PID }

    fun parseHex(s: String?): Int? = s?.trim()?.uppercase()?.removePrefix("0X")?.toIntOrNull(16)?.takeIf { it in 0..255 }

    fun displayName(uri: Uri): String =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "файл"

    private fun readText(uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("Не удалось открыть файл")

    private fun writeText(uri: Uri, text: String) {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
            ?: throw IllegalStateException("Не удалось записать файл")
    }

    /** только для отладочной сборки: показать чип без программатора. sig "1E9306", fuses "EC8F--FF" (L H E Lock) */
    fun demo(sig: String, fuses: String) {
        val key = (0 until 3).map { sig.substring(2 * it, 2 * it + 2).toInt(16) }
        val c = chips[key] ?: return
        chip = c
        FUSE_KEYS.forEachIndexed { i, k -> fuseText[k] = fuses.drop(2 * i).take(2).takeIf { k in c.fuses && it != "--" } ?: "" }
        status = "Демо: ${c.name}"
    }

    // ---------------------------------------------------------------- запуск операций
    fun stop() { job?.cancel() }

    private fun op(title: String, block: (Ops) -> String?) {
        if (busy) return
        val dev = findDevice()
        if (dev == null) {
            error = "Программатор 03EB:C8B4 не найден. Подключите его через OTG-переходник."
            return
        }
        if (!usb.hasPermission(dev)) {
            pendingAfterPermission = { op(title, block) }
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_PERM).setPackage(ctx.packageName), flags)
            usb.requestPermission(dev, pi)
            return
        }
        busy = true
        progress = 0f; progressText = ""
        status = "$title…"
        val sp = speed
        job = viewModelScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            try {
                Programmer(usb, dev, sp).use { p ->
                    val synced = p.enter()
                    val sig = p.signature()
                    val sigs = sig.joinToString(" ") { "%02X".format(it) }
                    val c = chips[sig]
                    if (c == null || (!synced && c.pollIndex != 0)) {
                        if (!synced || sig == listOf(0xFF, 0xFF, 0xFF) || sig == listOf(0, 0, 0))
                            throw IllegalStateException("Чип не отвечает (сигнатура $sigs). Проверьте подключение и питание, для нового чипа выберите скорость 5")
                        throw IllegalStateException("Неизвестный чип, сигнатура $sigs")
                    }
                    if (chip !== c) {
                        chip = c
                        FUSE_KEYS.forEach { if (it !in c.fuses) fuseText[it] = "" }
                    }
                    val ee = c.eeprom
                    chipLabel = "${c.name}\n$sigs · flash ${c.flash.size / 1024} КБ" + (ee?.let { " · EEPROM ${it.size} Б" } ?: "")
                    val ops = Ops(p, c) { ensureActive() }
                    val msg = block(ops)
                    status = "${msg ?: title} — готово (${(System.currentTimeMillis() - t0) / 1000} с)"
                }
            } catch (e: CancellationException) {
                logMsg("Остановлено пользователем"); status = "$title — остановлено"
            } catch (e: Exception) {
                val m = e.message ?: e.toString()
                logMsg("ОШИБКА: $m"); status = "$title — ошибка"; error = m
            } finally {
                busy = false
            }
        }
    }

    private fun progressCb(verb: String): (Int, Int) -> Unit {
        val t0 = System.currentTimeMillis()
        var last = 0L
        return { done, total ->
            val now = System.currentTimeMillis()
            if (now - last > 150 || done >= total) {
                last = now
                val el = (now - t0) / 1000.0
                val eta = if (done > 0) el / done * (total - done) else 0.0
                progress = if (total > 0) done.toFloat() / total else 0f
                progressText = "$verb ${if (total > 0) 100 * done / total else 0}%  ~${eta.toInt()} с"
            }
        }
    }

    private fun showFuses(f: Map<String, Int?>) {
        f.forEach { (k, v) -> fuseText[k] = v?.let { "%02X".format(it) } ?: "" }
    }

    private fun fmt(f: Map<String, Int?>) =
        FUSE_KEYS.joinToString("  ") { "${FUSE_TITLE[it]}=" + (f[it]?.let { v -> "%02X".format(v) } ?: "--") }

    // ---------------------------------------------------------------- действия
    fun connect() = op("Подключение") { o ->
        val f = o.readAllFuses(); showFuses(f)
        logMsg("${o.c.name}: ${fmt(f)}")
        "Подключено"
    }

    fun writeFuses() {
        val c = chip ?: return run { error = "Сначала нажмите «Подключить»" }
        val vals = listOf("low", "high", "ext").filter { it in c.fuses }.associateWith { parseHex(fuseText[it]) }
        val miss = vals.filterValues { it == null }.keys.map { c.title(it) }
        if (miss.isNotEmpty()) { error = "Не заполнены: ${miss.joinToString()}"; return }
        val (errs, warns) = fuseDanger(c, vals)
        if (errs.isNotEmpty()) { error = "Запись отменена:\n" + errs.joinToString("\n"); return }
        val txt = vals.entries.joinToString(", ") { "${c.title(it.key)}=%02X".format(it.value) }
        confirm = Confirm("Записать фьюзы?", "${c.name}: $txt" + if (warns.isNotEmpty()) "\n\n" + warns.joinToString("\n") else "") {
            op("Запись фьюзов") { o ->
                if (o.c !== c) throw IllegalStateException("Подключён другой чип (${o.c.name}). Прочитайте фьюзы заново")
                vals.forEach { (k, v) -> o.writeFuse(k, v!!) }
                val f = o.readAllFuses(); showFuses(f)
                logMsg("Записано, прочитано: ${fmt(f)}")
                val bad = vals.filter { (k, v) -> ((f[k]!! xor v!!) and c.fuses.getValue(k).mask) != 0 }.keys
                if (bad.isNotEmpty()) throw IllegalStateException("После записи не совпали: " + bad.joinToString { c.title(it) })
                "Фьюзы записаны"
            }
        }
    }

    fun writeLock() {
        val c = chip ?: return run { error = "Сначала нажмите «Подключить»" }
        if ("lock" !in c.fuses) { error = "Lock не поддерживается"; return }
        val v = parseHex(fuseText["lock"]) ?: return run { error = "Введите значение Lock в hex" }
        val lb = c.findBit("LB1")
        val locked = if (lb != null) (v shr lb.second) and 1 == 0 else (v and 3) != 3
        val extra = if (locked) "\n\nВключается защита: чтение и проверка flash/EEPROM станут невозможны. Снять можно только стиранием чипа." else ""
        confirm = Confirm("Записать Lock?", "Lock=%02X$extra".format(v)) {
            op("Запись Lock") { o ->
                o.writeFuse("lock", v)
                val f = o.readAllFuses(); showFuses(f)
                logMsg("Lock записан, прочитано: ${fmt(f)}")
                if (((f["lock"]!! xor v) and c.fuses.getValue("lock").mask) != 0)
                    throw IllegalStateException("Lock после записи = %02X, ожидалось %02X".format(f["lock"], v))
                "Lock записан"
            }
        }
    }

    fun erase() {
        confirm = Confirm("Стереть чип?", "Сотрётся flash, Lock и EEPROM (если не запрограммирован EESAVE).") {
            op("Стирание") { o ->
                o.chipErase(); logMsg("Чип стёрт")
                showFuses(o.readAllFuses()); "Чип стёрт"
            }
        }
    }

    private fun load(uri: Uri?, size: Int, what: String): Hex.Image {
        uri ?: throw IllegalStateException("Не выбран файл $what")
        val img = Hex.parse(readText(uri), size)
        logMsg("$what: ${displayName(uri)}, данные до 0x%X".format(img.end))
        return img
    }

    private fun compare(data: ByteArray, img: ByteArray, what: String, t0: Long) {
        val bad = data.indices.filter { data[it] != img[it] }
        if (bad.isNotEmpty()) {
            val ex = bad.take(5).joinToString { "0x%05X: чип %02X, файл %02X".format(it, data[it], img[it]) }
            throw IllegalStateException("$what: проверка не прошла, ${bad.size} несовпадений. $ex")
        }
        logMsg("$what: проверка OK (${data.size} байт, ${(System.currentTimeMillis() - t0) / 1000} с)")
    }

    private fun verifyFlash(o: Ops, img: Hex.Image) {
        status = "Проверка flash…"
        val t0 = System.currentTimeMillis()
        val data = o.readFlash((img.end + 1) and 1.inv(), progressCb("Проверка"))
        compare(data, img.data, "Flash", t0)
    }

    fun flash() {
        if (!(optErase || optWrite || optVerify)) return
        val go = {
            op("Прошивка") { o ->
                val img = load(flashUri, o.c.flash.size, "Flash")
                if (optErase) { o.chipErase(); logMsg("Чип стёрт") }
                if (optWrite) {
                    status = "Запись flash…"
                    val t0 = System.currentTimeMillis()
                    o.writeFlash(img.data, progressCb("Запись"))
                    logMsg("Flash записан (${(System.currentTimeMillis() - t0) / 1000} с)")
                }
                if (optVerify) verifyFlash(o, img)
                "Прошивка"
            }
        }
        if (optWrite && !optErase) confirm = Confirm("Запись без стирания", "Запись без стирания обычно даёт ошибки проверки. Продолжить?", go)
        else go()
    }

    fun verifyOnly() = op("Проверка") { o -> verifyFlash(o, load(flashUri, o.c.flash.size, "Flash")); "Проверка OK" }

    fun readFlashTo(uri: Uri) = op("Чтение flash") { o ->
        val t0 = System.currentTimeMillis()
        val data = o.readFlash(progress = progressCb("Чтение"))
        writeText(uri, Hex.format(data))
        logMsg("Flash прочитан в ${displayName(uri)} (${(System.currentTimeMillis() - t0) / 1000} с)")
        "Чтение flash"
    }

    private fun needEeprom(o: Ops) = o.c.eeprom ?: throw IllegalStateException("У ${o.c.name} нет EEPROM")

    fun writeEeprom() = op("Запись EEPROM") { o ->
        val ee = needEeprom(o)
        val img = load(eepUri, ee.size, "EEPROM")
        val n = if (img.end > 0) img.end else ee.size
        status = "Запись EEPROM…"
        var t0 = System.currentTimeMillis()
        o.writeEeprom(img.data.copyOf(n), progressCb("Запись EEPROM"))
        logMsg("EEPROM записан ($n байт, ${(System.currentTimeMillis() - t0) / 1000} с)")
        status = "Проверка EEPROM…"
        t0 = System.currentTimeMillis()
        compare(o.readEeprom(n, progressCb("Проверка EEPROM")), img.data, "EEPROM", t0)
        "EEPROM записан"
    }

    fun verifyEeprom() = op("Проверка EEPROM") { o ->
        val ee = needEeprom(o)
        val img = load(eepUri, ee.size, "EEPROM")
        val t0 = System.currentTimeMillis()
        compare(o.readEeprom(if (img.end > 0) img.end else ee.size, progressCb("Проверка EEPROM")), img.data, "EEPROM", t0)
        "Проверка EEPROM OK"
    }

    fun readEepromTo(uri: Uri) = op("Чтение EEPROM") { o ->
        needEeprom(o)
        val t0 = System.currentTimeMillis()
        val data = o.readEeprom(progress = progressCb("Чтение EEPROM"))
        writeText(uri, Hex.format(data, trim = false))
        logMsg("EEPROM (${data.size} байт) прочитан в ${displayName(uri)} (${(System.currentTimeMillis() - t0) / 1000} с)")
        "Чтение EEPROM"
    }
}
