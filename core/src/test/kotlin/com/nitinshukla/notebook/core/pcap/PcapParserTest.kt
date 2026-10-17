package com.nitinshukla.notebook.core.pcap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PcapParserTest {

    private val udpPacket = Pk.eth(Pk.ipv4(Pk.udp(1000, 2000, ByteArray(6)), 17), 0x0800)
    private val dnsPacket = Pk.eth(Pk.ipv4(Pk.udp(5000, 53, Pk.dnsQuery(1, "example.com")), 17, src = "10.0.0.1", dst = "8.8.8.8"), 0x0800)
    private val arpPacket = Pk.eth(Pk.arp(1, Pk.MAC_A, "10.0.0.1", "00:00:00:00:00:00", "10.0.0.9"), 0x0806, dst = "ff:ff:ff:ff:ff:ff")

    private fun index(bytes: ByteArray, maxPackets: Int = 250_000) = PcapParser.index(ByteArraySource(bytes), maxPackets)

    // ---- format detection -----------------------------------------------------------------

    @Test fun `detect format from magic`() {
        assertEquals(CaptureFormat.PCAP, PcapParser.detectFormat(hexBytes("a1b2c3d4 00020004")))
        assertEquals(CaptureFormat.PCAP, PcapParser.detectFormat(hexBytes("d4c3b2a1")))
        assertEquals(CaptureFormat.PCAP_NANOSECOND, PcapParser.detectFormat(hexBytes("a1b23c4d")))
        assertEquals(CaptureFormat.PCAP_NANOSECOND, PcapParser.detectFormat(hexBytes("4d3cb2a1")))
        assertEquals(CaptureFormat.PCAPNG, PcapParser.detectFormat(hexBytes("0a0d0d0a 1c000000 4d3c2b1a")))
        assertEquals(CaptureFormat.PCAPNG, PcapParser.detectFormat(hexBytes("0a0d0d0a 0000001c 1a2b3c4d")))
        assertNull(PcapParser.detectFormat(hexBytes("0a0d0d0a 0000001c deadbeef")))
        assertNull(PcapParser.detectFormat(hexBytes("0a0d0d0a")))
        assertNull(PcapParser.detectFormat("hello".toByteArray()))
        assertNull(PcapParser.detectFormat(ByteArray(0)))
    }

    @Test fun `garbage input throws format exception`() {
        try {
            index("This is a text file, not a capture.\n".toByteArray())
            fail("expected PcapFormatException")
        } catch (e: PcapFormatException) {
            assertTrue(e.message!!, e.message!!.contains("Not a pcap"))
        }
        try {
            index(byteArrayOf(1, 2))
            fail("expected PcapFormatException")
        } catch (e: PcapFormatException) {
            assertTrue(e.message!!, e.message!!.contains("too short"))
        }
    }

    // ---- classic pcap ---------------------------------------------------------------------

    @Test fun `pcap big endian microseconds`() {
        val bytes = PcapBuilder(little = false)
            .packet(arpPacket, tsSec = 1_700_000_000L, tsFrac = 500)
            .packet(dnsPacket, tsSec = 1_700_000_001L, tsFrac = 250_000)
            .packet(udpPacket, tsSec = 1_700_000_002L, tsFrac = 0, origLen = 1500)
            .build()
        val idx = index(bytes)
        val s = idx.summary
        assertEquals(CaptureFormat.PCAP, s.format)
        assertEquals(bytes.size.toLong(), s.fileSize)
        assertEquals(3, s.packetCount)
        assertTrue(s.warnings.toString(), s.warnings.isEmpty())
        assertFalse(s.truncated)
        assertEquals(1, s.interfaces.size)
        assertEquals(InterfaceInfo(0, 1, "Ethernet", 262144, null, 1_000_000L), s.interfaces[0])

        val p1 = idx.packets[0]
        assertEquals(1, p1.index)
        assertEquals(1_700_000_000_000_500L, p1.timestampMicros)
        assertEquals(40L, p1.dataOffset)
        assertEquals(arpPacket.size, p1.capturedLength)
        assertEquals(arpPacket.size, p1.originalLength)
        assertEquals(1, p1.linkType)
        assertEquals("ARP", p1.protocol)
        assertEquals("Who has 10.0.0.9? Tell 10.0.0.1", p1.info)
        assertEquals(Pk.MAC_A, p1.source)

        val p2 = idx.packets[1]
        assertEquals("DNS", p2.protocol)
        assertEquals("Standard query 0x0001 A example.com", p2.info)
        assertEquals("10.0.0.1:5000", p2.source)
        assertEquals("8.8.8.8:53", p2.destination)
        assertEquals(40L + arpPacket.size + 16, p2.dataOffset)
        assertEquals(1_700_000_001_250_000L, p2.timestampMicros)

        val p3 = idx.packets[2]
        assertEquals(1500, p3.originalLength)
        assertEquals(udpPacket.size, p3.capturedLength)
        assertEquals("UDP", p3.protocol)
        // Packet bytes can be re-read from the source using the record.
        val source = ByteArraySource(bytes)
        assertTrue(source.read(p3.dataOffset, p3.capturedLength).contentEquals(udpPacket))
    }

    @Test fun `pcap little endian`() {
        val bytes = PcapBuilder(little = true).packet(dnsPacket, tsSec = 1, tsFrac = 2).build()
        val idx = index(bytes)
        assertEquals(CaptureFormat.PCAP, idx.summary.format)
        assertEquals(1, idx.packets.size)
        assertEquals(1_000_002L, idx.packets[0].timestampMicros)
        assertEquals("DNS", idx.packets[0].protocol)
        assertEquals(dnsPacket.size, idx.packets[0].capturedLength)
    }

    @Test fun `pcap nanosecond both endiannesses`() {
        for (little in listOf(false, true)) {
            val bytes = PcapBuilder(little = little, nanos = true).packet(udpPacket, tsSec = 10, tsFrac = 123_456_789).build()
            val idx = index(bytes)
            assertEquals(CaptureFormat.PCAP_NANOSECOND, idx.summary.format)
            assertEquals(10_123_456L, idx.packets[0].timestampMicros)
            assertEquals(1_000_000_000L, idx.summary.interfaces[0].timestampResolution)
        }
    }

    @Test fun `pcap link type is honoured`() {
        val raw = Pk.ipv4(Pk.udp(1, 2, ByteArray(2)), 17)
        val idx = index(PcapBuilder(linkType = 101).packet(raw).build())
        assertEquals("Raw IP", idx.summary.interfaces[0].linkTypeName)
        assertEquals(101, idx.packets[0].linkType)
        assertEquals("UDP", idx.packets[0].protocol)
    }

    @Test fun `truncated last packet keeps earlier packets and warns`() {
        val full = PcapBuilder().packet(arpPacket).packet(dnsPacket).packet(udpPacket).build()
        val cut = full.copyOf(full.size - 5)
        val idx = index(cut)
        assertEquals(2, idx.packets.size)
        assertEquals("ARP", idx.packets[0].protocol)
        assertEquals("DNS", idx.packets[1].protocol)
        assertEquals(1, idx.summary.warnings.size)
        val expectedOffset = 24 + 16 + arpPacket.size + 16 + dnsPacket.size
        assertEquals("Capture ends mid-packet at offset $expectedOffset (packet 3 declares ${udpPacket.size} bytes, ${udpPacket.size - 5} available)", idx.summary.warnings[0])
        assertFalse(idx.summary.truncated)
    }

    @Test fun `partial record header at end warns`() {
        val bytes = PcapBuilder().packet(arpPacket).raw(ByteArray(7)).build()
        val idx = index(bytes)
        assertEquals(1, idx.packets.size)
        assertTrue(idx.summary.warnings[0], idx.summary.warnings[0].startsWith("Capture ends mid-packet header at offset ${24 + 16 + arpPacket.size}"))
    }

    @Test fun `oversized captured length is treated as corruption`() {
        val bytes = PcapBuilder().packet(arpPacket).packet(udpPacket, inclLen = 300_000).build()
        val idx = index(bytes)
        assertEquals(1, idx.packets.size)
        assertTrue(idx.summary.warnings[0], idx.summary.warnings[0].startsWith("Corrupt packet header at offset ${24 + 16 + arpPacket.size}"))
        assertTrue(idx.summary.warnings[0].contains("300000"))
    }

    @Test fun `header only pcap has no packets`() {
        val idx = index(PcapBuilder().build())
        assertEquals(0, idx.summary.packetCount)
        assertNull(idx.summary.firstTimestampMicros)
        assertNull(idx.summary.lastTimestampMicros)
        assertEquals(0L, idx.summary.durationMicros)
        assertTrue(idx.summary.warnings.isEmpty())
        assertTrue(idx.summary.protocolCounts.isEmpty())
        assertTrue(idx.summary.topTalkers.isEmpty())
    }

    @Test fun `max packets truncation`() {
        val b = PcapBuilder()
        repeat(10) { b.packet(udpPacket, tsSec = it.toLong()) }
        val idx = index(b.build(), maxPackets = 4)
        assertEquals(4, idx.packets.size)
        assertTrue(idx.summary.truncated)
        assertEquals(4, idx.summary.packetCount)
        assertEquals(3_000_000L, idx.summary.durationMicros)
        assertTrue(idx.summary.warnings.isEmpty())
    }

    @Test fun `progress callback reports final counts`() {
        val b = PcapBuilder()
        repeat(5) { b.packet(udpPacket) }
        val bytes = b.build()
        val calls = ArrayList<Pair<Int, Long>>()
        PcapParser.index(ByteArraySource(bytes)) { packets, read -> calls.add(packets to read) }
        assertEquals(5 to bytes.size.toLong(), calls.last())
    }

    @Test fun `captures larger than one chunk are indexed across buffer refills`() {
        // ~1.3 MiB: records straddle the 1 MiB chunk boundary, and one packet is 60 KB.
        val big = Pk.eth(Pk.ipv4(Pk.udp(7, 8, ByteArray(60_000)), 17), 0x0800)
        val b = PcapBuilder()
        val count = 20_000
        repeat(count) { i -> if (i == 9_000) b.packet(big, tsSec = i.toLong()) else b.packet(udpPacket, tsSec = i.toLong()) }
        val bytes = b.build()
        assertTrue(bytes.size > (1 shl 20))
        var progress = 0
        val idx = PcapParser.index(ByteArraySource(bytes)) { _, _ -> progress++ }
        assertEquals(count, idx.packets.size)
        assertTrue(idx.summary.warnings.toString(), idx.summary.warnings.isEmpty())
        assertTrue(idx.packets.all { it.protocol == "UDP" })
        assertEquals("7 → 8 Len=60000", idx.packets[9_000].info)
        assertEquals(big.size, idx.packets[9_000].capturedLength)
        assertTrue(progress > 1)
        assertEquals(idx.packets.last().dataOffset + udpPacket.size, bytes.size.toLong())
    }

    // ---- pcapng ---------------------------------------------------------------------------

    @Test fun `pcapng little endian with two interfaces and tsresol`() {
        val raw = Pk.ipv4(Pk.udp(1, 2, ByteArray(2)), 17)
        val bytes = PcapngBuilder(little = true)
            .shb()
            .idb(linkType = 1, snapLen = 65535, name = "eth0")
            .idb(linkType = 101, snapLen = 2048, name = "tun0", tsresol = 9)
            .epb(arpPacket, interfaceId = 0, timestamp = 1_700_000_000_000_001L)
            .epb(raw, interfaceId = 1, timestamp = 1_700_000_000_123_456_789L)
            .build()
        val idx = index(bytes)
        val s = idx.summary
        assertEquals(CaptureFormat.PCAPNG, s.format)
        assertTrue(s.warnings.toString(), s.warnings.isEmpty())
        assertEquals(
            listOf(
                InterfaceInfo(0, 1, "Ethernet", 65535, "eth0", 1_000_000L),
                InterfaceInfo(1, 101, "Raw IP", 2048, "tun0", 1_000_000_000L),
            ),
            s.interfaces,
        )
        assertEquals(2, idx.packets.size)
        val p1 = idx.packets[0]
        assertEquals(0, p1.interfaceId)
        assertEquals(1, p1.linkType)
        assertEquals("ARP", p1.protocol)
        assertEquals(1_700_000_000_000_001L, p1.timestampMicros)
        // SHB 28 + IDB(eth0) 32 + IDB(tun0, two options) 40 + EPB header 28
        assertEquals(28L + 32 + 40 + 28, p1.dataOffset)
        assertTrue(ByteArraySource(bytes).read(p1.dataOffset, p1.capturedLength).contentEquals(arpPacket))
        val p2 = idx.packets[1]
        assertEquals(1, p2.interfaceId)
        assertEquals(101, p2.linkType)
        assertEquals("UDP", p2.protocol)
        assertEquals(1_700_000_000_123_456L, p2.timestampMicros)
    }

    @Test fun `pcapng big endian`() {
        val bytes = PcapngBuilder(little = false).shb().idb().epb(dnsPacket, timestamp = 5_000_000L).build()
        val idx = index(bytes)
        assertEquals(1, idx.packets.size)
        assertEquals(5_000_000L, idx.packets[0].timestampMicros)
        assertEquals("DNS", idx.packets[0].protocol)
        assertEquals(dnsPacket.size, idx.packets[0].capturedLength)
        assertTrue(idx.summary.warnings.isEmpty())
    }

    @Test fun `pcapng power of two tsresol and tsoffset`() {
        // 2^-10 resolution: 2048 ticks = 2 s; offset 100 s.
        val bytes = PcapngBuilder(little = true).shb().idb(tsresol = 0x80 or 10, tsOffset = 100).epb(udpPacket, timestamp = 2048 + 512).build()
        val idx = index(bytes)
        assertEquals(102_500_000L, idx.packets[0].timestampMicros)
        assertEquals(1024L, idx.summary.interfaces[0].timestampResolution)
        val ms = PcapngBuilder(little = true).shb().idb(tsresol = 3).epb(udpPacket, timestamp = 1_234L).build()
        assertEquals(1_234_000L, index(ms).packets[0].timestampMicros)
    }

    @Test fun `pcapng simple packet block uses interface zero snaplen`() {
        val bytes = PcapngBuilder(little = true).shb().idb(snapLen = 40).spb(udpPacket, origLen = udpPacket.size).build()
        val idx = index(bytes)
        val p = idx.packets.single()
        assertEquals(40, p.capturedLength)
        assertEquals(udpPacket.size, p.originalLength)
        assertEquals(0L, p.timestampMicros)
        assertEquals(28L + 20 + 12, p.dataOffset)
        assertEquals("UDP", p.protocol)
        // Without a snaplen limit the full packet is used.
        val full = index(PcapngBuilder(little = true).shb().idb(snapLen = 0).spb(udpPacket).build())
        assertEquals(udpPacket.size, full.packets.single().capturedLength)
    }

    @Test fun `pcapng obsolete packet block`() {
        val b = PcapngBuilder(little = true).shb().idb()
        val body = Buf(true).u16(0).u16(0).u32(0).u32(7_000_000L).u32(arpPacket.size).u32(arpPacket.size).bytes(arpPacket).build()
        b.block(2, body)
        val idx = index(b.build())
        val p = idx.packets.single()
        assertEquals("ARP", p.protocol)
        assertEquals(7_000_000L, p.timestampMicros)
        assertEquals(28L + 20 + 28, p.dataOffset)
    }

    @Test fun `pcapng skips name resolution statistics custom and unknown blocks`() {
        val bytes = PcapngBuilder(little = true)
            .shb()
            .idb()
            .block(4, Buf(true).u16(1).u16(8).bytes(ip4("1.2.3.4")).ascii("abc").u8(0).u16(0).u16(0).build()) // NRB
            .epb(udpPacket)
            .block(5, Buf(true).u32(0).u32(0).u32(0).build()) // ISB
            .block(0x40000BADL, ByteArray(9)) // custom
            .block(0x12345678L, ByteArray(3)) // unknown
            .epb(arpPacket)
            .build()
        val idx = index(bytes)
        assertEquals(listOf("UDP", "ARP"), idx.packets.map { it.protocol })
        assertTrue(idx.summary.warnings.toString(), idx.summary.warnings.isEmpty())
    }

    @Test fun `pcapng multiple sections with different endianness`() {
        val bytes = PcapngBuilder(little = true).shb().idb(linkType = 1, name = "a").epb(udpPacket, timestamp = 1).build() +
            PcapngBuilder(little = false).shb().idb(linkType = 101, name = "b").epb(Pk.ipv4(Pk.udp(1, 2, ByteArray(1)), 17), interfaceId = 0, timestamp = 2).build()
        val idx = index(bytes)
        assertEquals(2, idx.packets.size)
        assertEquals(listOf("a", "b"), idx.summary.interfaces.map { it.name })
        assertEquals(listOf(0, 1), idx.packets.map { it.interfaceId })
        assertEquals(listOf(1, 101), idx.packets.map { it.linkType })
        assertEquals(listOf(1L, 2L), idx.packets.map { it.timestampMicros })
        assertTrue(idx.summary.warnings.isEmpty())
    }

    @Test fun `pcapng invalid block length stops with warning`() {
        val good = PcapngBuilder(little = true).shb().idb().epb(udpPacket).build()
        val bad = good + Buf(true).u32(6).u32(10).u32(0).build() // length 10 is < 12 and unaligned
        val idx = index(bad)
        assertEquals(1, idx.packets.size)
        assertEquals("Invalid block length 10 at offset ${good.size}", idx.summary.warnings.single())
    }

    @Test fun `pcapng truncated mid block warns`() {
        val full = PcapngBuilder(little = true).shb().idb().epb(udpPacket).epb(arpPacket).build()
        val cut = full.copyOf(full.size - 3)
        val idx = index(cut)
        assertEquals(1, idx.packets.size)
        assertTrue(idx.summary.warnings.single(), idx.summary.warnings.single().startsWith("Capture ends mid-block at offset ${28 + 20 + 32 + ((udpPacket.size + 3) / 4) * 4} (block declares"))
    }

    @Test fun `pcapng mismatched trailing length and bad byte order magic`() {
        val good = PcapngBuilder(little = true).shb().idb().build()
        val epb = PcapngBuilder(little = true).epb(udpPacket).build()
        epb[epb.size - 1] = 0x7F // corrupt the trailing length
        val idx = index(good + epb)
        assertEquals(1, idx.packets.size) // the packet itself was indexed before the trailer check
        assertTrue(idx.summary.warnings.single(), idx.summary.warnings.single().contains("mismatched trailing length"))

        val badBom = hexBytes("0a0d0d0a 1c000000 deadbeef 0100 0000 ffffffffffffffff 1c000000")
        try {
            index(badBom)
            fail()
        } catch (e: PcapFormatException) {
            // not recognised as a capture at all
        }
    }

    @Test fun `pcapng packet referencing undefined interface is still indexed`() {
        val bytes = PcapngBuilder(little = true).shb().idb().epb(udpPacket, interfaceId = 5).epb(udpPacket, interfaceId = 5).build()
        val idx = index(bytes)
        assertEquals(2, idx.packets.size)
        assertEquals("UDP", idx.packets[0].protocol)
        assertEquals(1, idx.summary.warnings.size)
        assertTrue(idx.summary.warnings[0], idx.summary.warnings[0].contains("undefined interface 5"))
    }

    // ---- summary aggregates ---------------------------------------------------------------

    @Test fun `summary aggregates protocols talkers conversations and duration`() {
        val tcp = Pk.eth(Pk.ipv4(Pk.tcp(40000, 443, flags = 0x018, payload = ByteArray(100)), 6, src = "10.0.0.1", dst = "1.1.1.1"), 0x0800)
        val bytes = PcapBuilder()
            .packet(dnsPacket, tsSec = 100, tsFrac = 0)             // 10.0.0.1 -> 8.8.8.8
            .packet(udpPacket, tsSec = 100, tsFrac = 500_000)       // 10.0.0.1 -> 10.0.0.2
            .packet(tcp, tsSec = 101, tsFrac = 0, origLen = 1000)   // 10.0.0.1 -> 1.1.1.1 (TLS by port)
            .packet(arpPacket, tsSec = 102, tsFrac = 250_000)       // MACs only
            .packet(dnsPacket, tsSec = 103, tsFrac = 0)
            .build()
        val s = index(bytes).summary
        assertEquals(5, s.packetCount)
        assertEquals(100_000_000L, s.firstTimestampMicros)
        assertEquals(103_000_000L, s.lastTimestampMicros)
        assertEquals(3_000_000L, s.durationMicros)
        assertEquals(dnsPacket.size * 2L + udpPacket.size + tcp.size + arpPacket.size, s.totalCapturedBytes)
        assertEquals(dnsPacket.size * 2L + udpPacket.size + 1000 + arpPacket.size, s.totalOriginalBytes)
        assertEquals(listOf("DNS" to 2, "ARP" to 1, "TLS" to 1, "UDP" to 1), s.protocolCounts.entries.map { it.key to it.value })
        val talkers = s.topTalkers.toMap()
        assertEquals("10.0.0.1", s.topTalkers[0].first)
        assertEquals(dnsPacket.size * 2L + udpPacket.size + 1000, talkers["10.0.0.1"])
        assertEquals(1000L, talkers["1.1.1.1"])
        assertEquals(dnsPacket.size * 2L, talkers["8.8.8.8"])
        assertEquals(arpPacket.size.toLong(), talkers["ff:ff:ff:ff:ff:ff"])
        assertEquals(arpPacket.size.toLong(), talkers[Pk.MAC_A])
        assertEquals("10.0.0.1 ⇄ 8.8.8.8" to dnsPacket.size * 2L, s.topConversations[1])
        assertEquals("1.1.1.1 ⇄ 10.0.0.1" to 1000L, s.topConversations[0])
        assertEquals(4, s.topConversations.size)
    }

    @Test fun `top lists are capped at ten`() {
        val b = PcapBuilder()
        repeat(15) { i -> b.packet(Pk.eth(Pk.ipv4(Pk.udp(1, 2, ByteArray(i)), 17, src = "10.0.1.$i", dst = "10.0.2.$i"), 0x0800)) }
        val s = index(b.build()).summary
        assertEquals(10, s.topTalkers.size)
        assertEquals(10, s.topConversations.size)
        assertEquals("10.0.1.14", s.topTalkers[0].first)
        assertEquals(mapOf("UDP" to 15), s.protocolCounts)
    }

    @Test fun `byte array source reads partial at end`() {
        val src = ByteArraySource(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(5L, src.size)
        assertTrue(src.read(3, 10).contentEquals(byteArrayOf(4, 5)))
        assertEquals(0, src.read(5, 10).size)
        assertEquals(0, src.read(-1, 10).size)
        assertTrue(src.read(0, 2).contentEquals(byteArrayOf(1, 2)))
    }

    @Test fun `random corruption after a valid header never throws`() {
        val rnd = java.util.Random(7)
        val base = PcapBuilder().packet(dnsPacket).packet(arpPacket).build()
        repeat(300) {
            val bytes = base.copyOf()
            repeat(1 + rnd.nextInt(6)) { bytes[24 + rnd.nextInt(bytes.size - 24)] = rnd.nextInt(256).toByte() }
            val idx = index(bytes)
            assertTrue(idx.summary.packetCount <= 2)
        }
        val ngBase = PcapngBuilder(little = true).shb().idb(name = "x").epb(dnsPacket).epb(arpPacket).build()
        repeat(300) {
            val bytes = ngBase.copyOf()
            repeat(1 + rnd.nextInt(6)) { bytes[12 + rnd.nextInt(bytes.size - 12)] = rnd.nextInt(256).toByte() }
            index(bytes)
        }
    }
}
