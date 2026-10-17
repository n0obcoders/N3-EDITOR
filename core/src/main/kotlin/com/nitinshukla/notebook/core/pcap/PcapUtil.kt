package com.nitinshukla.notebook.core.pcap

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Hex dump in `hexdump -C` / Wireshark style. */
object HexDump {
    /** One [HexLine] per [bytesPerLine] bytes; the last line is padded to the full width. */
    fun lines(bytes: ByteArray, bytesPerLine: Int = 16): List<HexLine> {
        val width = bytesPerLine.coerceAtLeast(1)
        val out = ArrayList<HexLine>((bytes.size + width - 1) / width)
        var offset = 0
        while (offset < bytes.size) {
            val hex = StringBuilder(width * 3 + 1)
            val ascii = StringBuilder(width)
            for (col in 0 until width) {
                if (col > 0) hex.append(' ')
                if (col > 0 && col % 8 == 0) hex.append(' ')
                val i = offset + col
                if (i < bytes.size) {
                    val b = bytes[i].toInt() and 0xFF
                    hex.append(HEX_DIGITS[b ushr 4]).append(HEX_DIGITS[b and 0xF])
                    ascii.append(if (b in 0x20..0x7E) b.toChar() else '.')
                } else {
                    hex.append("  ")
                    ascii.append(' ')
                }
            }
            out.add(HexLine(offset, hex.toString(), ascii.toString()))
            offset += width
        }
        return out
    }

    /** "00000000  00 11 22 ... |ascii|" per line. */
    fun format(bytes: ByteArray, bytesPerLine: Int = 16): String =
        lines(bytes, bytesPerLine).joinToString("\n") { line ->
            "%08x  %s  |%s|".format(line.offset, line.hex, line.ascii)
        }

    private const val HEX_DIGITS = "0123456789abcdef"
}

/** Names for pcap link-layer header types. */
object LinkTypes {
    const val NULL = 0
    const val ETHERNET = 1
    const val PPP = 9
    const val RAW_12 = 12
    const val RAW_14 = 14
    const val RAW = 101
    const val IEEE802_11 = 105
    const val LINUX_SLL = 113
    const val IEEE802_11_RADIOTAP = 127
    const val IPV4 = 228
    const val IPV6 = 229
    const val LINUX_SLL2 = 276

    fun name(linkType: Int): String = when (linkType) {
        NULL -> "Null/Loopback"
        ETHERNET -> "Ethernet"
        PPP -> "PPP"
        RAW_12, RAW_14, RAW -> "Raw IP"
        IEEE802_11 -> "IEEE 802.11"
        LINUX_SLL -> "Linux cooked (SLL)"
        IEEE802_11_RADIOTAP -> "Radiotap"
        IPV4 -> "Raw IPv4"
        IPV6 -> "Raw IPv6"
        LINUX_SLL2 -> "Linux cooked v2 (SLL2)"
        else -> "LINKTYPE_$linkType"
    }
}

/** Timestamp formatting for packet rows. */
object PcapTime {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

    /** "2026-10-04 12:34:56.123456" in UTC. */
    fun formatUtc(micros: Long): String {
        val seconds = Math.floorDiv(micros, 1_000_000L)
        val frac = Math.floorMod(micros, 1_000_000L)
        return formatter.format(Instant.ofEpochSecond(seconds)) + "." + frac.toString().padStart(6, '0')
    }

    /** Seconds since [firstMicros] with six decimals, e.g. "12.345678". */
    fun formatRelative(micros: Long, firstMicros: Long): String {
        val delta = micros - firstMicros
        val sign = if (delta < 0) "-" else ""
        val abs = Math.abs(delta)
        return sign + (abs / 1_000_000L) + "." + (abs % 1_000_000L).toString().padStart(6, '0')
    }
}

/**
 * Bounds-checked big-endian reader over `bytes[base, base + limit)`. Positions are relative to
 * [base]; every accessor throws [IndexOutOfBoundsException] on overrun.
 */
internal class ByteReader(private val bytes: ByteArray, private val base: Int = 0, val limit: Int = bytes.size - base) {
    fun u8(at: Int): Int {
        if (at < 0 || at >= limit) throw IndexOutOfBoundsException("offset $at of $limit")
        return bytes[base + at].toInt() and 0xFF
    }

    fun u16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)
    fun u24(at: Int): Int = (u16(at) shl 8) or u8(at + 2)
    fun u32(at: Int): Long = (u16(at).toLong() shl 16) or u16(at + 2).toLong()
    fun has(at: Int, length: Int): Boolean = at >= 0 && length >= 0 && at + length <= limit

    fun slice(at: Int, length: Int): ByteArray {
        if (!has(at, length)) throw IndexOutOfBoundsException("slice $at+$length of $limit")
        return bytes.copyOfRange(base + at, base + at + length)
    }

    /** ASCII text with non-printable bytes shown as '.'. */
    fun ascii(at: Int, length: Int): String {
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            val b = u8(at + i)
            sb.append(if (b in 0x20..0x7E) b.toChar() else '.')
        }
        return sb.toString()
    }

    /** True when the bytes at [at] equal [prefix]'s ASCII bytes. */
    fun startsWith(at: Int, prefix: String): Boolean {
        if (!has(at, prefix.length)) return false
        for (i in prefix.indices) if (u8(at + i) != prefix[i].code) return false
        return true
    }
}

internal object Addr {
    private const val HEX = "0123456789abcdef"

    fun mac(r: ByteReader, at: Int): String {
        val sb = StringBuilder(17)
        for (i in 0 until 6) {
            if (i > 0) sb.append(':')
            val b = r.u8(at + i)
            sb.append(HEX[b ushr 4]).append(HEX[b and 0xF])
        }
        return sb.toString()
    }

    fun ipv4(r: ByteReader, at: Int): String =
        "${r.u8(at)}.${r.u8(at + 1)}.${r.u8(at + 2)}.${r.u8(at + 3)}"

    /** RFC 5952 compressed form: longest run of two or more zero groups becomes "::". */
    fun ipv6(r: ByteReader, at: Int): String {
        val groups = IntArray(8) { r.u16(at + it * 2) }
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (groups[i] == 0) {
                var j = i
                while (j < 8 && groups[j] == 0) j++
                if (j - i > bestLen) { bestStart = i; bestLen = j - i }
                i = j
            } else {
                i++
            }
        }
        if (bestLen < 2) bestStart = -1
        val sb = StringBuilder(39)
        i = 0
        while (i < 8) {
            if (i == bestStart) {
                sb.append("::")
                i += bestLen
                continue
            }
            if (sb.isNotEmpty() && !sb.endsWith(':')) sb.append(':')
            sb.append(Integer.toHexString(groups[i]))
            i++
        }
        return sb.toString()
    }

    fun hex(bytes: ByteArray, from: Int = 0, to: Int = bytes.size, sep: String = ""): String {
        val sb = StringBuilder()
        for (i in from until to) {
            if (i > from) sb.append(sep)
            val b = bytes[i].toInt() and 0xFF
            sb.append(HEX[b ushr 4]).append(HEX[b and 0xF])
        }
        return sb.toString()
    }

    /** Formats host:port, bracketing IPv6 hosts. */
    fun endpoint(host: String, port: Int): String =
        if (host.contains(':')) "[$host]:$port" else "$host:$port"
}
