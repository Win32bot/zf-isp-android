package kz.win32bot.zfisp

/**
 * Формат SPI-команд из avrdude.conf:
 *  компактный  "0010.0000--aaaa.aaaa--aaaa.aaaa--oooo.oooo"
 *  явный       "0 0 1 0 1 0 0 0  x x x x x x a1 a0  ..."
 * a — бит адреса, i — бит данных, o — бит ответа, x/0 — ноль, 1 — единица.
 */
object Isp {
    private val cache = HashMap<String, Array<String>>()

    fun pattern(p: String): Array<String> = synchronized(cache) {
        cache.getOrPut(p) {
            val t = if (p.trim().contains(' ')) p.trim().split(Regex("\\s+")).toTypedArray()
            else p.replace("--", "").replace(".", "").map { it.toString() }.toTypedArray()
            require(t.size == 32) { "плохой формат команды: $p" }
            t
        }
    }

    fun encode(p: String, addr: Long = 0, data: Int = 0): ByteArray {
        val out = IntArray(4)
        pattern(p).forEachIndexed { idx, tok ->
            val byte = idx / 8
            val bit = 7 - idx % 8
            val v = when {
                tok == "1" -> 1L
                tok[0] == 'a' -> {
                    val n = if (tok.length > 1) tok.substring(1).toInt()
                    else if (byte == 1 || byte == 2) (2 - byte) * 8 + bit else bit
                    (addr shr n) and 1L
                }
                tok[0] == 'i' -> {
                    val n = if (tok.length > 1) tok.substring(1).toInt() else bit
                    ((data shr n) and 1).toLong()
                }
                else -> 0L
            }
            if (v != 0L) out[byte] = out[byte] or (1 shl bit)
        }
        return ByteArray(4) { out[it].toByte() }
    }

    fun decode(p: String, resp: ByteArray): Int {
        var v = 0
        pattern(p).forEachIndexed { idx, tok ->
            if (tok[0] == 'o') {
                val byte = idx / 8
                val bit = 7 - idx % 8
                val n = if (tok.length > 1) tok.substring(1).toInt() else bit
                if ((resp[byte].toInt() shr bit) and 1 == 1) v = v or (1 shl n)
            }
        }
        return v
    }
}
