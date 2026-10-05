package kz.win32bot.zfisp

import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class CoreTest {
    @Test
    fun encodeDecodeMatchesDesktop() {
        val vec = JSONArray(javaClass.classLoader!!.getResource("isp_vectors.json")!!.readText())
        for (i in 0 until vec.length()) {
            val v = vec.getJSONArray(i)
            val p = v.getString(0)
            val exp = v.getJSONArray(3).let { a -> ByteArray(4) { a.getInt(it).toByte() } }
            assertArrayEquals("encode #$i $p", exp, Isp.encode(p, v.getLong(1), v.getInt(2)))
            val resp = v.getJSONArray(4).let { a -> ByteArray(4) { a.getInt(it).toByte() } }
            assertEquals("decode #$i $p", v.getInt(5), Isp.decode(p, resp))
        }
        println("ISP-векторов проверено: ${vec.length()}")
    }

    @Test
    fun chipsLoad() {
        val chips = Chips.parse(File("src/main/assets/chips.json").readText())
        assertEquals(128, chips.size)
        val m = chips.getValue(listOf(0x1E, 0x98, 0x02))
        assertEquals("ATmega2561", m.name)
        assertEquals(0x40000, m.flash.size)
        assertEquals("SPIEN", m.bitNames("high")[2])
        assertEquals("high" to 5, m.findBit("SPIEN"))
        val t13 = chips.getValue(listOf(0x1E, 0x90, 0x07))
        assertEquals("low" to 7, t13.findBit("SPIEN"))
        // SPIEN снят -> блок
        assertEquals(1, fuseDanger(m, mapOf("high" to 0xF8)).first.size)
        assertEquals(0, fuseDanger(m, mapOf("high" to 0xD8)).first.size)
    }

    @Test
    fun hexRoundTrip() {
        val data = ByteArray(0x30000) { 0xFF.toByte() }
        for (i in 0 until 0x20100) data[i] = (i * 7).toByte()
        val img = Hex.parse(Hex.format(data), data.size)
        assertArrayEquals(data, img.data)
        assertEquals(0x20100, img.end)
    }
}
