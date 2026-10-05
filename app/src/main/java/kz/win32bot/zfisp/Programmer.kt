package kz.win32bot.zfisp

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

const val ZF_VID = 0x03EB
const val ZF_PID = 0xC8B4

/**
 * ZhiFeng USBISP (HID 03EB:C8B4). Все пакеты — HID feature report ID 1, 8 байт.
 *  вход в ISP:  01 0D 01 spd 20 A0 40 C0  +  01 0D 00 spd 20 A0 40 C0
 *  ISP-команда: 01 0E b0 b1 b2 b3 00 04, ответ — GET_REPORT(feature 1), байты SPI
 *  выход:       01 0B 01 00 00 00 00 00
 * Команда выполняется программатором только при чтении ответа.
 */
class Programmer(manager: UsbManager, device: UsbDevice, private val speed: Int) : AutoCloseable {
    private val conn: UsbDeviceConnection
    private val iface: UsbInterface
    /** смещение ответа SPI в буфере GET_REPORT (определяется по эху команды Programming Enable) */
    private var off = 0

    init {
        iface = (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID } ?: device.getInterface(0)
        conn = manager.openDevice(device) ?: throw IOException("Не удалось открыть программатор (нет разрешения?)")
        if (!conn.claimInterface(iface, true)) {
            conn.close(); throw IOException("Не удалось захватить интерфейс программатора")
        }
    }

    private fun setFeature(data: ByteArray) {
        val r = conn.controlTransfer(0x21, 0x09, 0x0300 or (data[0].toInt() and 0xFF), iface.id, data, data.size, 1000)
        if (r < 0) throw IOException("Ошибка обмена с программатором (SET_REPORT)")
    }

    private fun getFeature(id: Int, len: Int): ByteArray {
        val b = ByteArray(len)
        val r = conn.controlTransfer(0xA1, 0x01, 0x0300 or id, iface.id, b, len, 1000)
        if (r < 0) throw IOException("Ошибка обмена с программатором (GET_REPORT)")
        return b.copyOf(r)
    }

    /** сырая 4-байтная ISP-команда -> 4 байта ответа SPI */
    fun raw(cmd: ByteArray): ByteArray {
        setFeature(byteArrayOf(1, 0x0E, cmd[0], cmd[1], cmd[2], cmd[3], 0, 4))
        val r = getFeature(1, 8)
        return ByteArray(4) { r.getOrElse(off + it) { 0 } }
    }

    fun run(p: String, addr: Long = 0, data: Int = 0): Int = Isp.decode(p, raw(Isp.encode(p, addr, data)))

    /** true — чип ответил эхом 0x53 (старые AT90S1200 эхо не дают) */
    fun enter(): Boolean {
        repeat(3) {
            setFeature(byteArrayOf(1, 0x0D, 1, speed.toByte(), 0x20, 0xA0.toByte(), 0x40, 0xC0.toByte()))
            setFeature(byteArrayOf(1, 0x0D, 0, speed.toByte(), 0x20, 0xA0.toByte(), 0x40, 0xC0.toByte()))
            setFeature(byteArrayOf(1, 0x0E, 0xAC.toByte(), 0x53, 0, 0, 0, 4))
            val r = getFeature(1, 8)
            // на Windows ответ лежит с 0-го байта (эхо 0x53 в байте 2); подстраиваемся, если стек отдаёт иначе
            for (k in 0..1) if (r.size > k + 2 && r[k + 2] == 0x53.toByte()) { off = k; return true }
            leave(); Thread.sleep(50)
        }
        return false
    }

    fun leave() {
        try { setFeature(byteArrayOf(1, 0x0B, 1, 0, 0, 0, 0, 0)) } catch (_: Exception) {}
    }

    fun signature(): List<Int> = List(3) { raw(byteArrayOf(0x30, 0, it.toByte(), 0))[3].toInt() and 0xFF }

    override fun close() {
        leave()
        try { conn.releaseInterface(iface) } catch (_: Exception) {}
        conn.close()
    }
}
