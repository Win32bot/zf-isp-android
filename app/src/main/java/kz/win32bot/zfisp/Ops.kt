package kz.win32bot.zfisp

/** Операции с чипом по его описанию (команды из avrdude.conf). */
class Ops(private val p: Programmer, val c: Chip, private val checkCancel: () -> Unit) {
    private fun sleepUs(us: Int, factor: Double, extraMs: Long) {
        Thread.sleep((us * factor / 1000).toLong() + extraMs)
    }

    fun readFuse(k: String): Int = p.run(c.fuses.getValue(k).read)

    fun readAllFuses(): Map<String, Int?> = FUSE_KEYS.associateWith { if (it in c.fuses) readFuse(it) else null }

    fun writeFuse(k: String, v: Int) {
        val f = c.fuses.getValue(k)
        p.run(f.write, data = v)
        sleepUs(f.delayUs, 1.2, 2)
    }

    fun chipErase() {
        p.run(c.erase)
        sleepUs(c.eraseDelayUs, 1.5, 20)
    }

    fun writeFlash(img: ByteArray, progress: (Int, Int) -> Unit) {
        val fl = c.flash
        if (fl.paged) {
            val page = fl.page
            val pages = (0 until fl.size step page).filter { pa -> (pa until pa + page).any { img[it] != 0xFF.toByte() } }
            var ext = -1L
            pages.forEachIndexed { n, pa ->
                checkCancel()
                var a = pa
                while (a < pa + page) {
                    val w = (a shr 1).toLong()
                    // грузим все байты, включая FF: буфер страницы в ISP не очищается
                    p.run(fl.loadLo!!, w, img[a].toInt() and 0xFF)
                    p.run(fl.loadHi!!, w, img[a + 1].toInt() and 0xFF)
                    a += 2
                }
                val w = (pa shr 1).toLong()
                if (fl.ext != null && (w shr 16) != ext) { ext = w shr 16; p.run(fl.ext, ext) }
                p.run(fl.writePage!!, w)
                sleepUs(fl.delayUs, 1.1, 1)
                progress(n + 1, pages.size)
            }
        } else {
            val words = (0 until fl.size step 2).filter { img[it] != 0xFF.toByte() || img[it + 1] != 0xFF.toByte() }
            words.forEachIndexed { n, a ->
                checkCancel()
                for ((cmd, b) in listOf(fl.writeLo!! to img[a], fl.writeHi!! to img[a + 1])) {
                    if (b != 0xFF.toByte()) {
                        p.run(cmd, (a shr 1).toLong(), b.toInt() and 0xFF)
                        sleepUs(fl.delayUs, 1.1, 1)
                    }
                }
                if (n % 64 == 0) progress(n + 1, words.size)
            }
            progress(1, 1)
        }
    }

    fun readFlash(until: Int = c.flash.size, progress: (Int, Int) -> Unit): ByteArray {
        val fl = c.flash
        val out = ByteArray(until)
        var ext = -1L
        var a = 0
        while (a < until) {
            if (a % 1024 == 0) { checkCancel(); progress(a, until) }
            val w = (a shr 1).toLong()
            if (fl.ext != null && (w shr 16) != ext) { ext = w shr 16; p.run(fl.ext, ext) }
            out[a] = p.run(fl.readLo, w).toByte()
            if (a + 1 < until) out[a + 1] = p.run(fl.readHi, w).toByte()
            a += 2
        }
        progress(until, until)
        return out
    }

    fun readEeprom(until: Int = c.eeprom!!.size, progress: (Int, Int) -> Unit): ByteArray {
        val ee = c.eeprom!!
        val out = ByteArray(until)
        for (a in 0 until until) {
            if (a % 256 == 0) { checkCancel(); progress(a, until) }
            out[a] = p.run(ee.read, a.toLong()).toByte()
        }
        progress(until, until)
        return out
    }

    fun writeEeprom(data: ByteArray, progress: (Int, Int) -> Unit) {
        val ee = c.eeprom!!
        val size = data.size
        if (ee.page > 1 && ee.load != null && ee.writePage != null) {
            var pa = 0
            while (pa < size) {
                checkCancel()
                for (a in pa until minOf(pa + ee.page, size)) p.run(ee.load, a.toLong(), data[a].toInt() and 0xFF)
                p.run(ee.writePage, pa.toLong())
                sleepUs(ee.delayUs, 1.2, 1)
                pa += ee.page
                progress(minOf(pa, size), size)
            }
        } else {
            for (a in 0 until size) {
                checkCancel()
                p.run(ee.write!!, a.toLong(), data[a].toInt() and 0xFF)
                sleepUs(ee.delayUs, 1.2, 1)
                if (a % 16 == 0) progress(a + 1, size)
            }
        }
        progress(size, size)
    }
}

/** Опасные значения фьюзов: (блокирующие, предупреждения) */
fun fuseDanger(c: Chip?, vals: Map<String, Int?>): Pair<List<String>, List<String>> {
    val errs = mutableListOf<String>()
    val warns = mutableListOf<String>()
    if (c == null) return errs to warns
    data class Rule(val name: String, val bad: Int, val msg: String, val block: Boolean)
    listOf(
        Rule("SPIEN", 1, "SPIEN не запрограммирован — ISP перестанет работать", true),
        Rule("RSTDISBL", 0, "RSTDISBL запрограммирован — RESET отключится, ISP перестанет работать", true),
        Rule("DWEN", 0, "DWEN запрограммирован — включится debugWIRE, ISP будет недоступен", false),
        Rule("JTAGEN", 0, "JTAGEN включён — JTAG займёт свои ножки порта", false),
    ).forEach { r ->
        val pos = c.findBit(r.name) ?: return@forEach
        val v = vals[pos.first] ?: return@forEach
        if ((v shr pos.second) and 1 == r.bad) (if (r.block) errs else warns).add(r.msg)
    }
    return errs to warns
}
