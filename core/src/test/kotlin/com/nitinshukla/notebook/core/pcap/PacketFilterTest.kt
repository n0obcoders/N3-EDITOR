package com.nitinshukla.notebook.core.pcap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketFilterTest {

    /** 1: TCP 10.0.0.1:50000 → 93.184.216.34:443, 2: DNS over UDP 10.0.0.1 → 8.8.8.8:53,
     *  3: TCP 10.0.0.9:51000 → 10.0.0.1:80 with an HTTP request, 4: UDP 192.168.1.5:5000 → 10.0.0.1:443, 5: ARP. */
    private val capture: CaptureIndex by lazy {
        val http = "GET /index.html HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()
        val bytes = PcapBuilder()
            .packet(Pk.eth(Pk.ipv4(Pk.tcp(50000, 443, 0x002), 6, "10.0.0.1", "93.184.216.34"), 0x0800))
            .packet(Pk.eth(Pk.ipv4(Pk.udp(40000, 53, Pk.dnsQuery(1, "example.com")), 17, "10.0.0.1", "8.8.8.8"), 0x0800))
            .packet(Pk.eth(Pk.ipv4(Pk.tcp(51000, 80, 0x018, payload = http), 6, "10.0.0.9", "10.0.0.1"), 0x0800))
            .packet(Pk.eth(Pk.ipv4(Pk.udp(5000, 443, ByteArray(20)), 17, "192.168.1.5", "10.0.0.1"), 0x0800))
            .packet(Pk.eth(Pk.arp(1, Pk.MAC_A, "10.0.0.2", "00:00:00:00:00:00", "10.0.0.1"), 0x0806))
            .build()
        PcapParser.index(ByteArraySource(bytes))
    }

    private fun shown(filter: PacketFilter): List<Int> {
        val p = filter.compile()
        return capture.packets.filter(p).map { it.index }
    }

    @Test fun `records carry transport hosts and ports`() {
        val dns = capture.packets[1]
        assertEquals("DNS", dns.protocol)
        assertEquals("UDP", dns.transport)
        assertEquals("10.0.0.1", dns.srcHost)
        assertEquals("8.8.8.8", dns.dstHost)
        assertEquals(40000, dns.srcPort)
        assertEquals(53, dns.dstPort)
        assertEquals(-1, capture.packets[4].srcPort)
    }

    @Test fun `empty filter shows everything`() {
        assertEquals(listOf(1, 2, 3, 4, 5), shown(PacketFilter()))
    }

    @Test fun `ip then transport then port combine`() {
        assertEquals(listOf(1, 2, 3, 4), shown(PacketFilter(ip = "10.0.0.1")))
        assertEquals(listOf(1, 3), shown(PacketFilter(ip = "10.0.0.1", transport = TransportFilter.TCP)))
        assertEquals(listOf(2, 4), shown(PacketFilter(ip = "10.0.0.1", transport = TransportFilter.UDP)))
        assertEquals(listOf(1), shown(PacketFilter(ip = "10.0.0.1", transport = TransportFilter.TCP, port = "443")))
        assertEquals(listOf(4), shown(PacketFilter(ip = "10.0.0.1", transport = TransportFilter.UDP, port = "443")))
    }

    @Test fun `ip prefixes and cidr`() {
        assertEquals(listOf(1, 2, 3, 4), shown(PacketFilter(ip = "10.0.")))
        assertEquals(listOf(4), shown(PacketFilter(ip = "192.168.0.0/16")))
        assertEquals(listOf(1, 2, 3, 4), shown(PacketFilter(ip = "10.0.0.*")))
    }

    @Test fun `port ranges`() {
        assertEquals(listOf(2, 3), shown(PacketFilter(port = "50-100")))
    }

    @Test fun `typed expressions`() {
        assertEquals(listOf(1), shown(PacketFilter(expression = "ip 10.0.0.1 tcp port 443")))
        assertEquals(listOf(1), shown(PacketFilter(expression = "ip.addr==10.0.0.1 && tcp.port==443")))
        assertEquals(listOf(4), shown(PacketFilter(expression = "udp.port==443")))
        assertEquals(listOf(3), shown(PacketFilter(expression = "src 10.0.0.9")))
        assertEquals(listOf(2), shown(PacketFilter(expression = "dst port 53")))
        assertEquals(listOf(2), shown(PacketFilter(expression = "dns")))
        assertEquals(listOf(1, 3, 4, 5), shown(PacketFilter(expression = "not dns")))
        assertEquals(listOf(5), shown(PacketFilter(expression = "arp")))
        assertEquals(listOf(3), shown(PacketFilter(expression = "index.html")))
    }

    @Test fun `builder and expression together`() {
        assertEquals(listOf(3), shown(PacketFilter(ip = "10.0.0.1", transport = TransportFilter.TCP, expression = "http")))
    }

    @Test fun `payload text and export`() {
        val record = capture.packets[2]
        val out = StringBuilder()
        val src = ByteArraySource(
            PcapBuilder().packet(Pk.eth(Pk.ipv4(Pk.tcp(51000, 80, 0x018, payload = "GET / HTTP/1.1\r\nHost: a\r\n\r\n".toByteArray()), 6, "10.0.0.9", "10.0.0.1"), 0x0800)).build(),
        )
        val idx = PcapParser.index(src)
        PacketText.export(src, idx.packets, idx.summary, "test.pcap", "tcp", out)
        val text = out.toString()
        assertTrue(text, text.contains("Packet 1"))
        assertTrue(text, text.contains("[Transmission Control Protocol]"))
        assertTrue(text, text.contains("Source port: 51000"))
        assertTrue(text, text.contains("    GET / HTTP/1.1"))
        assertTrue(text, text.contains("    Host: a"))
        assertTrue(text, text.contains("[Hex dump]"))
        assertEquals("TCP", record.transport)
    }

    @Test fun `binary payload becomes dots`() {
        val bytes = byteArrayOf(0x41, 0x00, 0x42, 0x0A, 0xFF.toByte(), 0x43)
        assertEquals("A.B\n.C", PacketText.payloadText(bytes, 0, bytes.size))
    }

    @Test fun `follow stream joins both directions`() {
        val req = "GET / HTTP/1.1\r\n\r\n".toByteArray()
        val resp = "HTTP/1.1 200 OK\r\n\r\nhello".toByteArray()
        val src = ByteArraySource(
            PcapBuilder()
                .packet(Pk.eth(Pk.ipv4(Pk.tcp(51000, 80, 0x018, payload = req), 6, "10.0.0.9", "10.0.0.1"), 0x0800))
                .packet(Pk.eth(Pk.ipv4(Pk.udp(1, 2, ByteArray(4)), 17, "10.0.0.9", "10.0.0.1"), 0x0800))
                .packet(Pk.eth(Pk.ipv4(Pk.tcp(80, 51000, 0x018, payload = resp), 6, "10.0.0.1", "10.0.0.9"), 0x0800))
                .build(),
        )
        val idx = PcapParser.index(src)
        val text = PacketText.followStream(src, idx.packets, idx.packets[0])
        assertTrue(text, text.contains(">>> 10.0.0.9:51000 -> 10.0.0.1:80"))
        assertTrue(text, text.contains("<<< 10.0.0.1:80 -> 10.0.0.9:51000"))
        assertTrue(text, text.contains("hello"))
        assertTrue(text, !text.contains("packet 2,"))
    }
}
