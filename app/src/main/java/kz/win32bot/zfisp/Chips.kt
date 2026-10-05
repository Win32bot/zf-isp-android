package kz.win32bot.zfisp

import org.json.JSONArray
import org.json.JSONObject

val FUSE_KEYS = listOf("low", "high", "ext", "lock")
val FUSE_TITLE = mapOf("low" to "Low", "high" to "High", "ext" to "Extended", "lock" to "Lock")

data class FlashDef(
    val size: Int, val page: Int,
    val readLo: String, val readHi: String,
    val loadLo: String?, val loadHi: String?, val writePage: String?, val ext: String?,
    val writeLo: String?, val writeHi: String?, val delayUs: Int,
) {
    val paged get() = loadLo != null && writePage != null
}

data class EepromDef(
    val size: Int, val page: Int, val read: String, val write: String?,
    val load: String?, val writePage: String?, val delayUs: Int,
)

data class FuseDef(val mem: String, val read: String, val write: String, val mask: Int, val delayUs: Int)

data class Chip(
    val sig: List<Int>, val id: String, val name: String,
    val flash: FlashDef, val eeprom: EepromDef?, val fuses: Map<String, FuseDef>,
    val erase: String, val eraseDelayUs: Int, val pollIndex: Int,
    val bits: Map<String, List<String>>?,
) {
    val sigText get() = sig.joinToString(" ") { "%02X".format(it) }

    /** имена битов 7..0 ("" — без имени) */
    fun bitNames(k: String): List<String> = bits?.get(k) ?: List(8) { "" }

    /** фьюз и номер бита по имени */
    fun findBit(name: String): Pair<String, Int>? {
        for (k in FUSE_KEYS) {
            if (k !in fuses) continue
            val i = bitNames(k).indexOf(name)
            if (i >= 0) return k to (7 - i)
        }
        return null
    }

    fun title(k: String) = if (k == "low" && fuses[k]?.mem == "fuse") "Fuse" else FUSE_TITLE.getValue(k)
}

object Chips {
    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else getString(k)

    fun parse(json: String): Map<List<Int>, Chip> {
        val arr = JSONArray(json)
        val res = LinkedHashMap<List<Int>, Chip>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val sigA = o.getJSONArray("sig")
            val sig = List(3) { sigA.getInt(it) }
            val f = o.getJSONObject("flash")
            val flash = FlashDef(
                f.getInt("size"), f.getInt("page"), f.getString("read_lo"), f.getString("read_hi"),
                f.str("load_lo"), f.str("load_hi"), f.str("writepage"), f.str("ext"),
                f.str("write_lo"), f.str("write_hi"), f.getInt("delay"),
            )
            val ee = if (o.isNull("eeprom")) null else o.getJSONObject("eeprom").let {
                EepromDef(it.getInt("size"), it.getInt("page"), it.getString("read"), it.str("write"),
                    it.str("load"), it.str("writepage"), it.getInt("delay"))
            }
            val fz = o.getJSONObject("fuses")
            val fuses = LinkedHashMap<String, FuseDef>()
            for (k in FUSE_KEYS) if (fz.has(k)) fz.getJSONObject(k).let {
                fuses[k] = FuseDef(it.getString("mem"), it.getString("read"), it.getString("write"),
                    it.getInt("mask"), it.getInt("delay"))
            }
            val bits = if (o.isNull("bits")) null else o.getJSONObject("bits").let { b ->
                b.keys().asSequence().associateWith { k -> b.getJSONArray(k).let { a -> List(a.length()) { a.getString(it) } } }
            }
            res[sig] = Chip(sig, o.getString("id"), o.getString("name"), flash, ee, fuses,
                o.getString("erase"), o.getInt("erase_delay"), o.getInt("pollindex"), bits)
        }
        return res
    }
}
