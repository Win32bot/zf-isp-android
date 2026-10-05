package kz.win32bot.zfisp

/** Intel HEX */
object Hex {
    class Image(val data: ByteArray, val end: Int)

    fun parse(text: String, size: Int): Image {
        val img = ByteArray(size) { 0xFF.toByte() }
        var base = 0
        var end = 0
        text.lineSequence().forEachIndexed { no, raw ->
            val ln = raw.trim()
            if (ln.isEmpty()) return@forEachIndexed
            require(ln.startsWith(":")) { "строка ${no + 1}: не Intel HEX" }
            val b = try {
                ByteArray((ln.length - 1) / 2) { ln.substring(1 + 2 * it, 3 + 2 * it).toInt(16).toByte() }
            } catch (e: Exception) {
                throw IllegalArgumentException("строка ${no + 1}: не Intel HEX")
            }
            require(b.sumOf { it.toInt() and 0xFF } and 0xFF == 0) { "строка ${no + 1}: неверная контрольная сумма" }
            val n = b[0].toInt() and 0xFF
            val a = ((b[1].toInt() and 0xFF) shl 8) or (b[2].toInt() and 0xFF)
            when (b[3].toInt()) {
                0 -> {
                    val addr = base + a
                    require(addr + n <= size) { "данные по адресу 0x%X не помещаются в память чипа (%d байт)".format(addr, size) }
                    System.arraycopy(b, 4, img, addr, n)
                    end = maxOf(end, addr + n)
                }
                1 -> return Image(img, end)
                2 -> base = (((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF)) shl 4
                4 -> base = (((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF)) shl 16
            }
        }
        return Image(img, end)
    }

    fun format(data: ByteArray, trim: Boolean = true): String {
        var last = data.size
        if (trim) while (last > 0 && data[last - 1] == 0xFF.toByte()) last--
        val sb = StringBuilder()
        fun rec(bytes: List<Int>) {
            val cs = (-bytes.sum()) and 0xFF
            sb.append(':')
            bytes.forEach { sb.append("%02X".format(it)) }
            sb.append("%02X\n".format(cs))
        }
        var seg = -1
        var a = 0
        while (a < last) {
            if (a shr 16 != seg) {
                seg = a shr 16
                rec(listOf(2, 0, 0, 4, seg shr 8, seg and 0xFF))
            }
            val n = minOf(16, last - a)
            rec(listOf(n, (a shr 8) and 0xFF, a and 0xFF, 0) + (0 until n).map { data[a + it].toInt() and 0xFF })
            a += 16
        }
        sb.append(":00000001FF\n")
        return sb.toString()
    }
}
