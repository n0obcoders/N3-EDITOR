package com.nitinshukla.notebook.core.pcap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcapUtilTest {

    private val twenty = "0123456789abcdef".toByteArray() + byteArrayOf(0x00, 0x7f, 0x80.toByte(), 0x41)

    @Test fun `hex dump lines pad the last partial line`() {
        val lines = HexDump.lines(twenty)
        assertEquals(2, lines.size)
        assertEquals(HexLine(0, "30 31 32 33 34 35 36 37  38 39 61 62 63 64 65 66", "0123456789abcdef"), lines[0])
        assertEquals(HexLine(16, "00 7f 80 41                                     ", "...A            "), lines[1])
        assertEquals(lines[0].hex.length, lines[1].hex.length)
        assertEquals(16, lines[1].ascii.length)
    }

    @Test fun `hex dump format is hexdump -C style`() {
        val expected = "00000000  30 31 32 33 34 35 36 37  38 39 61 62 63 64 65 66  |0123456789abcdef|\n" +
            "00000010  00 7f 80 41                                       |...A            |"
        assertEquals(expected, HexDump.format(twenty))
        assertEquals("", HexDump.format(ByteArray(0)))
        assertTrue(HexDump.lines(ByteArray(0)).isEmpty())
    }

    @Test fun `hex dump with eight columns and large offsets`() {
        val lines = HexDump.lines(ByteArray(300) { it.toByte() }, bytesPerLine = 8)
        assertEquals(38, lines.size)
        assertEquals("00 01 02 03 04 05 06 07", lines[0].hex)
        assertEquals(296, lines.last().offset)
        assertEquals("28 29 2a 2b            ", lines.last().hex)
        assertEquals("()*+    ", lines.last().ascii)
        assertTrue(HexDump.format(ByteArray(300), bytesPerLine = 8).lines().last().startsWith("00000128  "))
    }

    @Test fun `utc timestamp formatting`() {
        assertEquals("1970-01-01 00:00:00.000000", PcapTime.formatUtc(0))
        assertEquals("2023-11-14 22:13:20.123456", PcapTime.formatUtc(1_700_000_000_123_456L))
        assertEquals("2026-10-04 12:34:56.000001", PcapTime.formatUtc(1_791_117_296_000_001L))
        assertEquals("1969-12-31 23:59:59.999999", PcapTime.formatUtc(-1))
    }

    @Test fun `relative timestamp formatting`() {
        assertEquals("0.000000", PcapTime.formatRelative(5, 5))
        assertEquals("12.345678", PcapTime.formatRelative(1_000_012_345_678L, 1_000_000_000_000L))
        assertEquals("0.000001", PcapTime.formatRelative(1, 0))
        assertEquals("-1.500000", PcapTime.formatRelative(0, 1_500_000))
    }

    @Test fun `link type names`() {
        assertEquals("Ethernet", LinkTypes.name(1))
        assertEquals("Raw IP", LinkTypes.name(101))
        assertEquals("Raw IP", LinkTypes.name(12))
        assertEquals("Raw IP", LinkTypes.name(14))
        assertEquals("Linux cooked (SLL)", LinkTypes.name(113))
        assertEquals("Linux cooked v2 (SLL2)", LinkTypes.name(276))
        assertEquals("IEEE 802.11", LinkTypes.name(105))
        assertEquals("Radiotap", LinkTypes.name(127))
        assertEquals("Raw IPv4", LinkTypes.name(228))
        assertEquals("Raw IPv6", LinkTypes.name(229))
        assertEquals("Null/Loopback", LinkTypes.name(0))
        assertEquals("PPP", LinkTypes.name(9))
        assertEquals("LINKTYPE_4242", LinkTypes.name(4242))
    }

    @Test fun `ipv6 compression follows rfc 5952`() {
        fun fmt(s: String) = Addr.ipv6(ByteReader(ip6(s)), 0)
        assertEquals("2001:db8::1", fmt("2001:db8::1"))
        assertEquals("::", fmt("::"))
        assertEquals("::1", fmt("::1"))
        assertEquals("fe80::", fmt("fe80::"))
        assertEquals("2001:db8:0:1:1:1:1:1", fmt("2001:db8:0:1:1:1:1:1")) // single zero group is not compressed
        assertEquals("2001:0:0:1::1", fmt("2001:0:0:1:0:0:0:1")) // longest run wins
        assertEquals("2001:db8::1:0:0:1", fmt("2001:db8:0:0:1:0:0:1")) // first of equal runs wins
    }
}
