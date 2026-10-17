package com.nitinshukla.notebook.core.pcap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketDecoderTest {

    private fun DecodedPacket.layer(name: String): Layer =
        layers.firstOrNull { it.name.startsWith(name) } ?: error("no layer '$name' in ${layers.map { it.name }}")

    private fun Layer.value(field: String): String =
        fields.firstOrNull { it.name == field }?.value ?: error("no field '$field' in ${fields.map { it.name }}")

    private fun Layer.field(field: String): Field = fields.first { it.name == field }

    @Test fun `ethernet arp request`() {
        val pkt = Pk.eth(Pk.arp(1, Pk.MAC_A, "10.0.0.2", "00:00:00:00:00:00", "10.0.0.1"), 0x0806, dst = "ff:ff:ff:ff:ff:ff")
        val d = PacketDecoder.decode(pkt, LinkTypes.ETHERNET)
        assertEquals(listOf("Ethernet II", "ARP"), d.layers.map { it.name })
        assertEquals("ARP", d.protocol)
        assertEquals("Who has 10.0.0.1? Tell 10.0.0.2", d.info)
        assertEquals(Pk.MAC_A, d.source)
        assertEquals("ff:ff:ff:ff:ff:ff", d.destination)
        val arp = d.layer("ARP")
        assertEquals("ARP request (1)", arp.value("Opcode"))
        assertEquals("10.0.0.1", arp.value("Target IP address"))
        assertEquals(Field("Sender MAC address", Pk.MAC_A, 22, 6), arp.field("Sender MAC address"))
        assertEquals(14, arp.offset)
        assertEquals(28, arp.length)
        assertEquals(pkt.size, d.payloadOffset)
        assertEquals(0, d.payloadLength)
    }

    @Test fun `arp reply info`() {
        val d = PacketDecoder.decode(Pk.eth(Pk.arp(2, Pk.MAC_A, "10.0.0.1", Pk.MAC_B, "10.0.0.2"), 0x0806), 1)
        assertEquals("10.0.0.1 is at aa:bb:cc:dd:ee:01", d.info)
    }

    @Test fun `ipv4 tcp syn ack with options`() {
        val options = Buf().u8(2).u8(4).u16(1460).u8(1).u8(3).u8(3).u8(7).u8(4).u8(2).u8(1).u8(1).build() // MSS, NOP, WS, SACK_PERM, NOP, NOP
        val tcp = Pk.tcp(443, 51234, flags = 0x012, seq = 1000, ack = 1, window = 65535, options = options)
        val pkt = Pk.eth(Pk.ipv4(tcp, 6, src = "93.184.216.34", dst = "192.168.1.10", ttl = 57), 0x0800)
        val d = PacketDecoder.decode(pkt, 1)
        assertEquals(listOf("Ethernet II", "Internet Protocol Version 4", "Transmission Control Protocol"), d.layers.map { it.name })
        assertEquals("TCP", d.protocol)
        assertEquals("93.184.216.34:443", d.source)
        assertEquals("192.168.1.10:51234", d.destination)
        assertEquals("443 → 51234 [SYN, ACK] Seq=1000 Ack=1 Win=65535 Len=0 MSS=1460 WS=128 SACK_PERM", d.info)
        val ip = d.layer("Internet Protocol Version 4")
        assertEquals("57", ip.value("Time to live"))
        assertEquals("Don't fragment", ip.value("Flags"))
        assertEquals("TCP (6)", ip.value("Protocol"))
        assertEquals("20 bytes", ip.value("Header length"))
        assertEquals(Field("Source", "93.184.216.34", 26, 4), ip.field("Source"))
        val t = d.layer("Transmission Control Protocol")
        assertEquals("32 bytes", t.value("Header length"))
        assertEquals("MSS=1460, NOP, WS=7, SACK_PERM, NOP, NOP", t.value("Options"))
        assertEquals("0x012 [SYN, ACK]", t.value("Flags"))
        assertEquals(34, t.offset)
        assertEquals(32, t.length)
        assertEquals(0, d.payloadLength)
    }

    @Test fun `tcp flag names and payload length`() {
        val tcp = Pk.tcp(1234, 5678, flags = 0x018, seq = 5, ack = 6, payload = ByteArray(10))
        val d = PacketDecoder.decode(Pk.eth(Pk.ipv4(tcp, 6), 0x0800), 1)
        assertEquals("1234 → 5678 [PSH, ACK] Seq=5 Ack=6 Win=65535 Len=10", d.info)
        assertEquals("TCP", d.protocol)
        assertEquals("Data", d.layers.last().name)
        assertEquals(54, d.payloadOffset)
        assertEquals(10, d.payloadLength)
        val none = PacketDecoder.decode(Pk.eth(Pk.ipv4(Pk.tcp(1, 2, flags = 0), 6), 0x0800), 1)
        assertTrue(none.info, none.info.contains("[<None>]"))
        val fin = PacketDecoder.decode(Pk.eth(Pk.ipv4(Pk.tcp(1, 2, flags = 0x011), 6), 0x0800), 1)
        assertTrue(fin.info, fin.info.contains("[FIN, ACK]"))
    }

    @Test fun `udp dns query`() {
        val dns = Pk.dnsQuery(0x1a2b, "example.com")
        val d = PacketDecoder.decode(Pk.eth(Pk.ipv4(Pk.udp(51000, 53, dns), 17), 0x0800), 1)
        assertEquals("DNS", d.protocol)
        assertEquals("Standard query 0x1a2b A example.com", d.info)
        assertEquals("10.0.0.1:51000", d.source)
        assertEquals("10.0.0.2:53", d.destination)
        val l = d.layer("Domain Name System")
        assertEquals("0x1a2b", l.value("Transaction ID"))
        assertEquals("1", l.value("Questions"))
        assertEquals("example.com: type A, class IN", l.value("Query"))
        assertTrue(l.value("Flags").contains("Recursion desired"))
        assertEquals(42, l.offset)
        assertEquals(dns.size, l.length)
    }

    @Test fun `udp dns response with compression pointer`() {
        val dns = Pk.dnsResponseA(0x1a2b, "example.com", "93.184.216.34")
        val d = PacketDecoder.decode(Pk.eth(Pk.ipv4(Pk.udp(53, 51000, dns), 17), 0x0800), 1)
        assertEquals("DNS", d.protocol)
        assertEquals("Standard query response 0x1a2b A example.com A 93.184.216.34", d.info)
        val l = d.layer("Domain Name System")
        assertEquals("example.com: type A, class IN, ttl 300, 93.184.216.34", l.value("Answer"))
        assertTrue(l.value("Flags").contains("Response"))
    }

    @Test fun `dns aaaa cname and nxdomain`() {
        // Response: NXDOMAIN, question AAAA, one CNAME answer pointing back to the question name.
        val dns = Buf().u16(7).u16(0x8183).u16(1).u16(1).u16(0).u16(0)
            .bytes(Pk.dnsName("www.example.com")).u16(28).u16(1)
            .u16(0xC00C).u16(5).u16(1).u32(60).u16(6).u8(3).ascii("foo").u16(0xC010) // "foo" + pointer to "example.com"
            .build()
        val d = PacketDecoder.decode(Pk.eth(Pk.ipv4(Pk.udp(53, 4000, dns), 17), 0x0800), 1)
        assertEquals("Standard query response 0x0007 No such name AAAA www.example.com CNAME foo.example.com", d.info)
    }

    @Test fun `dns pointer loop is rejected not hung`() {
        val dns = Buf().u16(1).u16(0x0100).u16(1).u16(0).u16(0).u16(0).u16(0xC00C).u16(1).u16(1).build()
        val d = PacketDecoder.decode(Pk.eth(Pk.ipv4(Pk.udp(1000, 53, dns), 17), 0x0800), 1)
        assertEquals("DNS", d.protocol)
        val malformed = d.layers.flatMap { it.fields }.firstOrNull { it.name == "Malformed packet" }
        assertNotNull(malformed)
        assertTrue(malformed!!.value, malformed.value.contains("pointer loop"))
        assertTrue(d.info, d.info.contains("Malformed"))
    }

    @Test fun `mdns labelled separately`() {
        val d = PacketDecoder.decode(Pk.eth(Pk.ipv4(Pk.udp(5353, 5353, Pk.dnsQuery(0, "_http._tcp.local", 12)), 17), 0x0800), 1)
        assertEquals("mDNS", d.protocol)
        assertEquals("Standard query 0x0000 PTR _http._tcp.local", d.info)
    }

    @Test fun `ipv6 tcp`() {
        val tcp = Pk.tcp(40000, 8080, flags = 0x002, seq = 7)
        val d = PacketDecoder.decode(Pk.eth(Pk.ipv6(tcp, 6, src = "2001:db8:0:0:1:0:0:1", dst = "fe80::1"), 0x86DD), 1)
        assertEquals("TCP", d.protocol)
        assertEquals("[2001:db8::1:0:0:1]:40000", d.source)
        assertEquals("[fe80::1]:8080", d.destination)
        val ip = d.layer("Internet Protocol Version 6")
        assertEquals("64", ip.value("Hop limit"))
        assertEquals("TCP (6)", ip.value("Next header"))
        assertEquals("20", ip.value("Payload length"))
        assertEquals(Field("Destination", "fe80::1", 38, 16), ip.field("Destination"))
        assertEquals(40, ip.length)
        assertEquals("40000 → 8080 [SYN] Seq=7 Ack=0 Win=65535 Len=0", d.info)
    }

    @Test fun `ipv6 with hop by hop extension header`() {
        val hopByHop = Buf().u8(17).u8(0).zeros(6).build() // next=UDP, len=0 (8 bytes)
        val payload = hopByHop + Pk.udp(1, 2, ByteArray(3))
        val d = PacketDecoder.decode(Pk.eth(Pk.ipv6(payload, 0), 0x86DD), 1)
        assertEquals(listOf("Ethernet II", "Internet Protocol Version 6", "IPv6 Hop-by-Hop Options", "User Datagram Protocol", "Data"), d.layers.map { it.name })
        assertEquals("UDP", d.protocol)
        assertEquals("1 → 2 Len=3", d.info)
    }

    @Test fun `ipv6 icmpv6 neighbor solicitation`() {
        val ns = Pk.icmpv6NeighborSolicitation("fe80::2", sourceMac = Pk.MAC_A)
        val d = PacketDecoder.decode(Pk.eth(Pk.ipv6(ns, 58, src = "fe80::1", dst = "ff02::1:ff00:2", hop = 255), 0x86DD), 1)
        assertEquals("ICMPv6", d.protocol)
        assertEquals("Neighbor Solicitation for fe80::2 from aa:bb:cc:dd:ee:01", d.info)
        assertEquals("fe80::1", d.source)
        val l = d.layer("Internet Control Message Protocol v6")
        assertEquals("fe80::2", l.value("Target address"))
        assertEquals("aa:bb:cc:dd:ee:01", l.value("Source link-layer address"))
    }

    @Test fun `icmp echo request`() {
        val d = PacketDecoder.decode(Pk.eth(Pk.ipv4(Pk.icmpEcho(true, 0x1234, 7), 1), 0x0800), 1)
        assertEquals("ICMP", d.protocol)
        assertEquals("Echo (ping) request id=0x1234, seq=7", d.info)
        assertEquals("8 bytes", d.layer("Data").summary)
        val unreachable = Buf().u8(3).u8(3).u16(0).u32(0).build()
        val u = PacketDecoder.decode(Pk.eth(Pk.ipv4(unreachable, 1), 0x0800), 1)
        assertEquals("Destination unreachable (Port unreachable)", u.info)
    }

    @Test fun `vlan tagged ipv4`() {
        val pkt = Pk.vlan(Pk.ipv4(Pk.udp(1000, 2000, ByteArray(4)), 17), vid = 42, innerType = 0x0800)
        val d = PacketDecoder.decode(pkt, 1)
        assertEquals(listOf("Ethernet II", "802.1Q Virtual LAN", "Internet Protocol Version 4", "User Datagram Protocol", "Data"), d.layers.map { it.name })
        assertEquals("42", d.layer("802.1Q").value("ID"))
        assertEquals(14, d.layer("802.1Q").offset)
        assertEquals(18, d.layer("Internet Protocol Version 4").offset)
        assertEquals("UDP", d.protocol)
        assertEquals("1000 → 2000 Len=4", d.info)
    }

    @Test fun `http get request`() {
        val http = "GET /index.html HTTP/1.1\r\nHost: example.com\r\nUser-Agent: test\r\n\r\n".toByteArray()
        val tcp = Pk.tcp(51000, 80, flags = 0x018, seq = 1, ack = 1, payload = http)
        val d = PacketDecoder.decode(Pk.eth(Pk.ipv4(tcp, 6), 0x0800), 1)
        assertEquals("HTTP", d.protocol)
        assertEquals("GET /index.html HTTP/1.1", d.info)
        val l = d.layer("Hypertext Transfer Protocol")
        assertEquals("GET /index.html HTTP/1.1", l.value("Request line"))
        assertEquals("example.com", l.value("Host"))
        assertEquals("test", l.value("User-Agent"))
        assertEquals(0, d.payloadLength)
    }

    @Test fun `http response detected by content on odd port`() {
        val http = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi".toByteArray()
        val d = PacketDecoder.decode(Pk.eth(Pk.ipv4(Pk.tcp(9999, 1111, flags = 0x018, payload = http), 6), 0x0800), 1)
        assertEquals("HTTP", d.protocol)
        assertEquals("HTTP/1.1 200 OK", d.info)
        assertEquals("2 bytes", d.layer("Hypertext Transfer Protocol").value("Body"))
        assertEquals(2, d.payloadLength)
        val cont = PacketDecoder.decode(Pk.eth(Pk.ipv4(Pk.tcp(80, 1111, flags = 0x010, payload = ByteArray(5)), 6), 0x0800), 1)
        assertEquals("HTTP", cont.protocol)
        assertEquals("Continuation", cont.info)
    }

    @Test fun `tls client hello with sni`() {
        val tls = Pk.tlsClientHello("www.example.org")
        val pkt = Pk.eth(Pk.ipv4(Pk.tcp(51000, 443, flags = 0x018, payload = tls), 6), 0x0800)
        val d = PacketDecoder.decode(pkt, 1)
        assertEquals("TLS", d.protocol)
        assertEquals("Client Hello (SNI=www.example.org)", d.info)
        val l = d.layer("Transport Layer Security")
        assertEquals("Handshake (22)", l.value("Content type"))
        assertEquals("TLS 1.0 (0x0301)", l.value("Version"))
        assertEquals("Client Hello (1)", l.value("Handshake type"))
        assertEquals("www.example.org", l.value("Server name"))
        assertEquals("2 suites", l.value("Cipher suites"))
        assertEquals("TLS 1.3", l.value("Supported versions"))
        assertEquals("TLS 1.0 Record Layer: Client Hello (SNI=www.example.org)", l.summary)
        val sniField = l.field("Server name")
        assertEquals("www.example.org", String(pkt, sniField.offset, sniField.length))
    }

    @Test fun `tls records by content and multiple records`() {
        val payload = Pk.tlsClientHello(null) + Pk.tlsAppData(3)
        val d = PacketDecoder.decode(Pk.eth(Pk.ipv4(Pk.tcp(7000, 8443, flags = 0x018, payload = payload), 6), 0x0800), 1)
        assertEquals("TLS", d.protocol)
        assertEquals("Client Hello, Application Data", d.info)
        assertEquals(2, d.layers.count { it.name == "Transport Layer Security" })
        assertNull(d.layers.last().fields.firstOrNull { it.name == "Server name" })
    }

    @Test fun `ssh banner dhcp ntp and quic labels`() {
        val ssh = PacketDecoder.decode(Pk.eth(Pk.ipv4(Pk.tcp(22, 5000, flags = 0x018, payload = "SSH-2.0-OpenSSH_9.6\r\n".toByteArray()), 6), 0x0800), 1)
        assertEquals("SSH", ssh.protocol)
        assertEquals("Protocol: SSH-2.0-OpenSSH_9.6", ssh.info)

        val dhcp = Buf().u8(1).u8(1).u8(6).u8(0).u32(0xdeadbeefL).u16(0).u16(0).zeros(16).bytes(mac(Pk.MAC_A)).zeros(10)
            .zeros(192).u32(0x63825363L).u8(53).u8(1).u8(1).u8(255).build()
        val dd = PacketDecoder.decode(Pk.eth(Pk.ipv4(Pk.udp(68, 67, dhcp), 17, src = "0.0.0.0", dst = "255.255.255.255"), 0x0800), 1)
        assertEquals("DHCP", dd.protocol)
        assertEquals("DHCP Discover - Transaction ID 0xdeadbeef", dd.info)
        assertEquals(Pk.MAC_A, dd.layer("Dynamic Host Configuration Protocol").value("Client MAC address"))

        val ntp = Buf().u8(0x23).u8(2).u8(6).u8(0xEC).zeros(44).build()
        val nd = PacketDecoder.decode(Pk.eth(Pk.ipv4(Pk.udp(123, 123, ntp), 17), 0x0800), 1)
        assertEquals("NTP", nd.protocol)
        assertEquals("NTP Version 4, client", nd.info)

        val quic = Buf().u8(0xC3).u32(1).zeros(20).build()
        val qd = PacketDecoder.decode(Pk.eth(Pk.ipv4(Pk.udp(50000, 443, quic), 17), 0x0800), 1)
        assertEquals("QUIC", qd.protocol)
        assertEquals("QUIC Long Header, Len=25", qd.info)
    }

    @Test fun `linux cooked sll and sll2`() {
        val inner = Pk.ipv4(Pk.udp(1, 2, ByteArray(1)), 17)
        val sll = Buf().u16(0).u16(1).u16(6).bytes(mac(Pk.MAC_A)).u16(0).u16(0x0800).bytes(inner).build()
        val d = PacketDecoder.decode(sll, LinkTypes.LINUX_SLL)
        assertEquals("Linux cooked capture", d.layers[0].name)
        assertEquals("UDP", d.protocol)
        assertEquals("10.0.0.1:1", d.source)
        val sll2 = Buf().u16(0x0800).u16(0).u32(3).u16(1).u8(0).u8(6).bytes(mac(Pk.MAC_A)).u16(0).bytes(inner).build()
        val d2 = PacketDecoder.decode(sll2, LinkTypes.LINUX_SLL2)
        assertEquals("Linux cooked capture v2", d2.layers[0].name)
        assertEquals("3", d2.layers[0].value("Interface index"))
        assertEquals("UDP", d2.protocol)
    }

    @Test fun `raw ip null loopback and unknown link types`() {
        val v4 = Pk.ipv4(Pk.udp(1, 2, ByteArray(1)), 17)
        assertEquals("UDP", PacketDecoder.decode(v4, LinkTypes.RAW).protocol)
        assertEquals("UDP", PacketDecoder.decode(v4, LinkTypes.IPV4).protocol)
        val v6 = Pk.ipv6(Pk.udp(1, 2, ByteArray(1)), 17)
        assertEquals("UDP", PacketDecoder.decode(v6, LinkTypes.RAW).protocol)
        assertEquals("UDP", PacketDecoder.decode(v6, LinkTypes.IPV6).protocol)
        val loopLe = Buf().u8(2).u8(0).u8(0).u8(0).bytes(v4).build()
        val dl = PacketDecoder.decode(loopLe, LinkTypes.NULL)
        assertEquals("Null/Loopback", dl.layers[0].name)
        assertEquals("UDP", dl.protocol)
        val loopBe = Buf().u32(2).bytes(v4).build()
        assertEquals("UDP", PacketDecoder.decode(loopBe, LinkTypes.NULL).protocol)
        val wifi = PacketDecoder.decode(ByteArray(40), LinkTypes.IEEE802_11)
        assertEquals(listOf("Link", "Data"), wifi.layers.map { it.name })
        assertEquals("IEEE 802.11", wifi.protocol)
        assertEquals("LINKTYPE_999", PacketDecoder.decode(ByteArray(4), 999).protocol)
    }

    @Test fun `unknown ethertype and ip protocol`() {
        val d = PacketDecoder.decode(Pk.eth(ByteArray(10), 0x88CC), 1)
        assertEquals("LLDP", d.protocol)
        assertEquals("Data", d.layers.last().name)
        val gre = PacketDecoder.decode(Pk.eth(Pk.ipv4(ByteArray(8), 47), 0x0800), 1)
        assertEquals("GRE", gre.protocol)
        val odd = PacketDecoder.decode(Pk.eth(Pk.ipv4(ByteArray(8), 200), 0x0800), 1)
        assertEquals("IPv4", odd.protocol)
        assertEquals("IP protocol 200", odd.info)
    }

    @Test fun `fragmented ipv4 is not dissected past ip`() {
        val d = PacketDecoder.decode(Pk.eth(Pk.ipv4(ByteArray(16), 17, flags = 0x0005), 0x0800), 1)
        assertEquals("IPv4", d.protocol)
        assertEquals("Fragmented IP protocol (proto=UDP 17, off=40, ID=0x1234)", d.info)
        assertEquals(listOf("Ethernet II", "Internet Protocol Version 4", "Data"), d.layers.map { it.name })
    }

    @Test fun `truncated headers produce malformed field not exception`() {
        val tcpPkt = Pk.eth(Pk.ipv4(Pk.tcp(1, 2, flags = 2), 6), 0x0800)
        for (cut in 0 until tcpPkt.size) {
            val d = PacketDecoder.decode(tcpPkt.copyOf(cut), 1)
            assertTrue("cut=$cut", d.layers.isNotEmpty() || cut < 14)
        }
        val d = PacketDecoder.decode(tcpPkt.copyOf(40), 1)
        val tcp = d.layer("Transmission Control Protocol")
        assertEquals("Malformed packet", tcp.fields.last().name)
        assertEquals(6, tcp.length)
        assertTrue(d.info, d.info.endsWith("[Malformed: Packet ends before the end of the header]"))
        val empty = PacketDecoder.decode(ByteArray(0), 1)
        assertEquals("Malformed packet", empty.info)
        assertEquals("Malformed packet", empty.layers.single().fields.single().name)
    }

    @Test fun `bad ip version and header length are reported`() {
        val badVersion = Pk.eth(byteArrayOf(0x75) + ByteArray(39), 0x0800)
        val d = PacketDecoder.decode(badVersion, 1)
        assertEquals("IPv4 version field is 7", d.layer("Internet Protocol Version 4").value("Malformed packet"))
        val badIhl = Pk.eth(Pk.ipv4(Pk.tcp(1, 2, flags = 2), 6).also { it[0] = 0x42 }, 0x0800)
        assertEquals("IPv4 header length 8 < 20", PacketDecoder.decode(badIhl, 1).layer("Internet Protocol Version 4").value("Malformed packet"))
    }

    @Test fun `random bytes never throw`() {
        val rnd = java.util.Random(42)
        val linkTypes = intArrayOf(0, 1, 9, 101, 113, 276, 228, 229, 105)
        repeat(3000) { i ->
            val bytes = ByteArray(rnd.nextInt(120))
            rnd.nextBytes(bytes)
            // Bias towards plausible headers so the deeper dissectors get exercised.
            if (bytes.size > 14 && i % 2 == 0) { bytes[12] = 0x08; bytes[13] = if (i % 4 == 0) 0x00 else 0xDD.toByte() }
            if (bytes.size > 24 && i % 3 == 0) { bytes[14] = 0x45; bytes[23] = if (i % 6 == 0) 6 else 17 }
            val d = PacketDecoder.decode(bytes, linkTypes[i % linkTypes.size])
            assertNotNull(d.info)
        }
    }

    @Test fun `summarize matches full decode and tolerates a short window`() {
        val http = "GET / HTTP/1.1\r\nHost: a\r\n\r\n".toByteArray()
        val pkt = Pk.eth(Pk.ipv4(Pk.tcp(1000, 80, flags = 0x018, seq = 1, ack = 1, payload = http), 6), 0x0800)
        val full = PacketDecoder.decode(pkt, 1)
        val row = PacketDecoder.RowSummary()
        val padded = ByteArray(7) + pkt + ByteArray(3)
        PacketDecoder.summarize(padded, 7, pkt.size, pkt.size, 1, row)
        assertEquals(full.protocol, row.protocol)
        assertEquals(full.info, row.info)
        assertEquals(full.source, row.source)
        assertEquals("10.0.0.1", row.srcHost)
        assertEquals("10.0.0.2", row.dstHost)
        // Only the first 40 bytes available: TCP ports are known, the rest is silently skipped.
        PacketDecoder.summarize(padded, 7, 40, pkt.size, 1, row)
        assertEquals("TCP", row.protocol)
        assertEquals("1000 → 80", row.info)
        assertEquals("10.0.0.1:1000", row.source)
    }
}
