package com.nitinshukla.notebook.core.pcap

/**
 * Decodes a single packet's bytes into protocol layers.
 *
 * [decode] builds the full field tree for the detail view. The indexer uses the lighter
 * [summarize], which runs the same dissectors over a window of the packet without
 * allocating layers or fields.
 */
object PacketDecoder {
    /** Full decode for the detail view. Never throws: malformed data yields a "Malformed packet" field. */
    fun decode(bytes: ByteArray, linkType: Int): DecodedPacket {
        val ctx = Ctx(ByteReader(bytes), bytes.size, full = true)
        ctx.run(linkType)
        return ctx.toDecoded()
    }

    /**
     * Light decode for one index row, reading `bytes[base, base + available)` which holds the
     * first [available] bytes of a packet whose captured length is [totalLength].
     */
    internal fun summarize(bytes: ByteArray, base: Int, available: Int, totalLength: Int, linkType: Int, out: RowSummary) {
        val ctx = Ctx(ByteReader(bytes, base, available), totalLength, full = false)
        ctx.run(linkType)
        out.protocol = ctx.protocol
        out.source = ctx.source
        out.destination = ctx.destination
        out.info = ctx.info
        out.srcHost = ctx.srcHost
        out.dstHost = ctx.dstHost
        out.transport = ctx.transport
        out.srcPort = ctx.srcPort
        out.dstPort = ctx.dstPort
    }

    /** Mutable result of [summarize], reused across packets by the indexer. */
    internal class RowSummary {
        var protocol = ""
        var source = ""
        var destination = ""
        var info = ""
        var srcHost = ""
        var dstHost = ""
        var transport = ""
        var srcPort = -1
        var dstPort = -1
    }
}

/** Raised by dissectors for data that is present but structurally invalid. */
private class Malformed(message: String) : RuntimeException(message)

private class LayerBuilder(val name: String, val offset: Int) {
    val fields = ArrayList<Field>()
    var summary = ""
    var length = 0
    fun build() = Layer(name, summary, fields, offset, length)
}

/**
 * Decoding state. [end] is the logical packet length (captured length); the reader may hold fewer
 * bytes than that in light mode, in which case running out of bytes is not an error.
 */
private class Ctx(val r: ByteReader, val end: Int, val full: Boolean) {
    val layers = ArrayList<LayerBuilder>(6)
    var current: LayerBuilder? = null
    var protocol = "Unknown"
    var source = ""
    var destination = ""
    var info = ""
    var srcHost = ""
    var dstHost = ""
    /** "TCP", "UDP", "ICMP", "ICMPv6" or "" — the transport under any application protocol. */
    var transport = ""
    var srcPort = -1
    var dstPort = -1
    var payloadOffset = 0
    var payloadLength = 0
    /** Everything above TCP/UDP (HTTP, DNS, TLS…), kept even when a dissector decodes it. */
    var appOffset = 0
    var appLength = 0

    // ---- layer plumbing -------------------------------------------------------------------

    fun begin(name: String, offset: Int, protocol: String? = name) {
        if (protocol != null) this.protocol = protocol
        if (!full) return
        current = LayerBuilder(name, offset).also { layers.add(it) }
    }

    inline fun field(name: String, offset: Int = -1, length: Int = 0, value: () -> String) {
        if (full) current?.fields?.add(Field(name, value(), offset, length))
    }

    fun end(summary: String, length: Int) {
        current?.let { it.summary = summary; it.length = length }
        current = null
    }

    fun data(offset: Int, until: Int, name: String = "Data") {
        val len = (until - offset).coerceAtLeast(0)
        payloadOffset = offset.coerceAtMost(end)
        payloadLength = len
        if (len > 0 && full) {
            begin(name, offset, null)
            field("Length", offset, len) { "$len bytes" }
            end("$len bytes", len)
        }
    }

    fun run(linkType: Int) {
        try {
            link(linkType)
        } catch (e: Exception) {
            malformed(e)
        }
        current?.let { end(it.summary.ifEmpty { it.name }, (end - it.offset).coerceAtLeast(0)) }
    }

    private fun malformed(e: Exception) {
        // Running out of bytes in light mode just means the summary window ended.
        val outOfWindow = !full && r.limit < end && e is IndexOutOfBoundsException
        if (outOfWindow) return
        val message = when (e) {
            is Malformed -> e.message ?: "malformed"
            is IndexOutOfBoundsException -> "Packet ends before the end of the header"
            else -> e.toString()
        }
        info = if (info.isEmpty()) "Malformed packet" else "$info [Malformed: $message]"
        if (!full) return
        val cur = current
        if (cur != null) {
            cur.fields.add(Field("Malformed packet", message))
            if (cur.summary.isEmpty()) cur.summary = "Malformed"
            end(cur.summary, (end - cur.offset).coerceAtLeast(0))
        } else {
            begin("Malformed", payloadOffset, null)
            field("Malformed packet") { message }
            end("Malformed", 0)
        }
    }

    fun toDecoded(): DecodedPacket = DecodedPacket(
        layers = layers.map { it.build() },
        protocol = protocol,
        source = source,
        destination = destination,
        info = info,
        payloadOffset = payloadOffset,
        payloadLength = payloadLength,
        transport = transport,
        appPayloadOffset = if (appLength > 0) appOffset else payloadOffset,
        appPayloadLength = if (appLength > 0) appLength else payloadLength,
        srcHost = srcHost,
        dstHost = dstHost,
        srcPort = srcPort,
        dstPort = dstPort,
    )

    // ---- link layer -----------------------------------------------------------------------

    fun link(linkType: Int) {
        when (linkType) {
            LinkTypes.ETHERNET -> ethernet(0)
            LinkTypes.LINUX_SLL -> sll(0)
            LinkTypes.LINUX_SLL2 -> sll2(0)
            LinkTypes.NULL -> nullLoopback(0)
            LinkTypes.RAW, LinkTypes.RAW_12, LinkTypes.RAW_14 -> rawIp(0)
            LinkTypes.IPV4 -> ipv4(0)
            LinkTypes.IPV6 -> ipv6(0)
            LinkTypes.PPP -> ppp(0)
            else -> {
                val name = LinkTypes.name(linkType)
                begin("Link", 0, name)
                field("Link type") { "$name ($linkType)" }
                end(name, 0)
                info = name
                data(0, end)
            }
        }
    }

    private fun ethernet(off: Int) {
        begin("Ethernet II", off, "Ethernet")
        val dst = Addr.mac(r, off)
        val src = Addr.mac(r, off + 6)
        source = src; destination = dst; srcHost = src; dstHost = dst
        var type = r.u16(off + 12)
        field("Destination", off, 6) { dst }
        field("Source", off + 6, 6) { src }
        field("Type", off + 12, 2) { "${etherTypeName(type)} (0x${hex16(type)})" }
        end("$src → $dst", 14)
        info = "Ethernet II, type 0x${hex16(type)}"
        var p = off + 14
        while (type == 0x8100 || type == 0x88A8 || type == 0x9100) {
            begin("802.1Q Virtual LAN", p, null)
            val tci = r.u16(p)
            val inner = r.u16(p + 2)
            val vid = tci and 0xFFF
            field("Priority", p, 2) { (tci ushr 13).toString() }
            field("DEI", p, 2) { ((tci ushr 12) and 1).toString() }
            field("ID", p, 2) { vid.toString() }
            field("Type", p + 2, 2) { "${etherTypeName(inner)} (0x${hex16(inner)})" }
            end("VLAN $vid", 4)
            info = "802.1Q VLAN $vid, type 0x${hex16(inner)}"
            type = inner
            p += 4
        }
        etherType(type, p)
    }

    private fun etherType(type: Int, p: Int) {
        when (type) {
            0x0800 -> ipv4(p)
            0x86DD -> ipv6(p)
            0x0806, 0x8035 -> arp(p, rarp = type == 0x8035)
            else -> {
                if (type < 0x0600) {
                    protocol = "802.3"
                    info = "IEEE 802.3, length $type"
                } else {
                    protocol = etherTypeName(type)
                    info = "$protocol (0x${hex16(type)})"
                }
                data(p, end)
            }
        }
    }

    private fun sll(off: Int) {
        begin("Linux cooked capture", off, "SLL")
        val pktType = r.u16(off)
        val hwType = r.u16(off + 2)
        val addrLen = r.u16(off + 4)
        val proto = r.u16(off + 14)
        field("Packet type", off, 2) { sllPacketType(pktType) }
        field("Link-layer address type", off + 2, 2) { hwType.toString() }
        field("Link-layer address length", off + 4, 2) { addrLen.toString() }
        if (addrLen == 6) {
            val mac = Addr.mac(r, off + 6)
            source = mac; srcHost = mac
            field("Source", off + 6, 6) { mac }
        } else if (addrLen in 1..8) {
            field("Source", off + 6, addrLen) { Addr.hex(r.slice(off + 6, addrLen)) }
        }
        field("Protocol", off + 14, 2) { "${etherTypeName(proto)} (0x${hex16(proto)})" }
        end(sllPacketType(pktType), 16)
        info = "Linux cooked, protocol 0x${hex16(proto)}"
        etherType(proto, off + 16)
    }

    private fun sll2(off: Int) {
        begin("Linux cooked capture v2", off, "SLL2")
        val proto = r.u16(off)
        val ifIndex = r.u32(off + 4)
        val hwType = r.u16(off + 8)
        val pktType = r.u8(off + 10)
        val addrLen = r.u8(off + 11)
        field("Protocol", off, 2) { "${etherTypeName(proto)} (0x${hex16(proto)})" }
        field("Interface index", off + 4, 4) { ifIndex.toString() }
        field("Link-layer address type", off + 8, 2) { hwType.toString() }
        field("Packet type", off + 10, 1) { sllPacketType(pktType) }
        field("Link-layer address length", off + 11, 1) { addrLen.toString() }
        if (addrLen == 6) {
            val mac = Addr.mac(r, off + 12)
            source = mac; srcHost = mac
            field("Source", off + 12, 6) { mac }
        } else if (addrLen in 1..8) {
            field("Source", off + 12, addrLen) { Addr.hex(r.slice(off + 12, addrLen)) }
        }
        end(sllPacketType(pktType), 20)
        info = "Linux cooked v2, protocol 0x${hex16(proto)}"
        etherType(proto, off + 20)
    }

    private fun nullLoopback(off: Int) {
        begin("Null/Loopback", off, "Loopback")
        var family = r.u32(off)
        if (family > 0xFFFF) family = java.lang.Long.reverseBytes(family) ushr 32 // host byte order
        field("Family", off, 4) { "$family" }
        end("Family $family", 4)
        info = "Null/Loopback, family $family"
        when (family) {
            2L -> ipv4(off + 4)
            24L, 28L, 30L -> ipv6(off + 4)
            else -> data(off + 4, end)
        }
    }

    private fun rawIp(off: Int) {
        when (r.u8(off) ushr 4) {
            4 -> ipv4(off)
            6 -> ipv6(off)
            else -> {
                begin("Raw IP", off, "Raw IP")
                end("Raw IP", 0)
                info = "Raw IP, unknown version"
                data(off, end)
            }
        }
    }

    private fun ppp(off: Int) {
        begin("PPP", off, "PPP")
        var p = off
        if (r.u8(p) == 0xFF && r.u8(p + 1) == 0x03) {
            field("Address", p, 1) { "0xff" }
            field("Control", p + 1, 1) { "0x03" }
            p += 2
        }
        val proto: Int
        if (r.u8(p) and 1 == 1) {
            proto = r.u8(p); field("Protocol", p, 1) { "0x${hex16(proto)}" }; p += 1
        } else {
            proto = r.u16(p); field("Protocol", p, 2) { "0x${hex16(proto)}" }; p += 2
        }
        end("Protocol 0x${hex16(proto)}", p - off)
        info = "PPP, protocol 0x${hex16(proto)}"
        when (proto) {
            0x0021 -> ipv4(p)
            0x0057 -> ipv6(p)
            else -> {
                protocol = when (proto) {
                    0xC021 -> "LCP"; 0x8021 -> "IPCP"; 0x8057 -> "IPV6CP"; 0xC023 -> "PAP"; 0xC223 -> "CHAP"; else -> "PPP"
                }
                info = protocol
                data(p, end)
            }
        }
    }

    // ---- ARP ------------------------------------------------------------------------------

    private fun arp(p: Int, rarp: Boolean) {
        begin(if (rarp) "RARP" else "ARP", p)
        val hwType = r.u16(p)
        val protoType = r.u16(p + 2)
        val hlen = r.u8(p + 4)
        val plen = r.u8(p + 5)
        val op = r.u16(p + 6)
        field("Hardware type", p, 2) { if (hwType == 1) "Ethernet (1)" else hwType.toString() }
        field("Protocol type", p + 2, 2) { "${etherTypeName(protoType)} (0x${hex16(protoType)})" }
        field("Hardware size", p + 4, 1) { hlen.toString() }
        field("Protocol size", p + 5, 1) { plen.toString() }
        field("Opcode", p + 6, 2) { "${arpOpcode(op)} ($op)" }
        if (hlen > 32 || plen > 32) throw Malformed("ARP address sizes $hlen/$plen")
        val shaOff = p + 8
        val spaOff = shaOff + hlen
        val thaOff = spaOff + plen
        val tpaOff = thaOff + hlen
        val length = 8 + 2 * hlen + 2 * plen
        info = "${arpOpcode(op)}"
        val sha = hwAddr(shaOff, hlen)
        val spa = protoAddr(spaOff, plen)
        val tha = hwAddr(thaOff, hlen)
        val tpa = protoAddr(tpaOff, plen)
        field("Sender MAC address", shaOff, hlen) { sha }
        field("Sender IP address", spaOff, plen) { spa }
        field("Target MAC address", thaOff, hlen) { tha }
        field("Target IP address", tpaOff, plen) { tpa }
        info = when (op) {
            1 -> if (spa == tpa) "Gratuitous ARP for $spa (Request)" else "Who has $tpa? Tell $spa"
            2 -> "$spa is at $sha"
            3 -> "Who is $tha? Tell $sha"
            4 -> "$tha is at $tpa"
            else -> "${arpOpcode(op)} (opcode $op)"
        }
        end(info, length)
        data(p + length, end)
    }

    private fun hwAddr(at: Int, len: Int): String = if (len == 6) Addr.mac(r, at) else Addr.hex(r.slice(at, len), sep = ":")
    private fun protoAddr(at: Int, len: Int): String = when (len) {
        4 -> Addr.ipv4(r, at)
        16 -> Addr.ipv6(r, at)
        else -> Addr.hex(r.slice(at, len))
    }

    // ---- IP -------------------------------------------------------------------------------

    private fun ipv4(p: Int) {
        begin("Internet Protocol Version 4", p, "IPv4")
        val vi = r.u8(p)
        val version = vi ushr 4
        val ihl = (vi and 0xF) * 4
        field("Version", p, 1) { version.toString() }
        field("Header length", p, 1) { "$ihl bytes" }
        if (version != 4) throw Malformed("IPv4 version field is $version")
        if (ihl < 20) throw Malformed("IPv4 header length $ihl < 20")
        val tos = r.u8(p + 1)
        val totalLen = r.u16(p + 2)
        val id = r.u16(p + 4)
        val ff = r.u16(p + 6)
        val df = ff and 0x4000 != 0
        val mf = ff and 0x2000 != 0
        val fragOff = (ff and 0x1FFF) * 8
        val ttl = r.u8(p + 8)
        val proto = r.u8(p + 9)
        val checksum = r.u16(p + 10)
        val src = Addr.ipv4(r, p + 12)
        val dst = Addr.ipv4(r, p + 16)
        field("DSCP", p + 1, 1) { (tos ushr 2).toString() }
        field("ECN", p + 1, 1) { (tos and 3).toString() }
        field("Total length", p + 2, 2) { totalLen.toString() }
        field("Identification", p + 4, 2) { "0x${hex16(id)} ($id)" }
        field("Flags", p + 6, 2) {
            val f = ArrayList<String>(2)
            if (ff and 0x8000 != 0) f.add("Reserved")
            if (df) f.add("Don't fragment")
            if (mf) f.add("More fragments")
            if (f.isEmpty()) "none" else f.joinToString(", ")
        }
        field("Fragment offset", p + 6, 2) { fragOff.toString() }
        field("Time to live", p + 8, 1) { ttl.toString() }
        field("Protocol", p + 9, 1) { "${ipProtoName(proto)} ($proto)" }
        field("Header checksum", p + 10, 2) { "0x${hex16(checksum)}" }
        field("Source", p + 12, 4) { src }
        field("Destination", p + 16, 4) { dst }
        if (ihl > 20) field("Options", p + 20, ihl - 20) { "${ihl - 20} bytes" }
        source = src; destination = dst; srcHost = src; dstHost = dst
        info = "$src → $dst (${ipProtoName(proto)})"
        end("$src → $dst", ihl)
        if (!r.has(p, ihl) && r.limit >= end) throw Malformed("IPv4 header length $ihl exceeds packet")
        val ipEnd = if (totalLen >= ihl && p + totalLen <= end) p + totalLen else end
        if (fragOff > 0 || mf) {
            info = "Fragmented IP protocol (proto=${ipProtoName(proto)} $proto, off=$fragOff, ID=0x${hex16(id)})"
            if (fragOff > 0) {
                data(p + ihl, ipEnd)
                return
            }
        }
        ipProto(proto, p + ihl, ipEnd)
    }

    private fun ipv6(p: Int) {
        begin("Internet Protocol Version 6", p, "IPv6")
        val vtf = r.u32(p)
        val version = (vtf ushr 28).toInt()
        field("Version", p, 1) { version.toString() }
        if (version != 6) throw Malformed("IPv6 version field is $version")
        val tc = ((vtf ushr 20) and 0xFF).toInt()
        val flow = vtf and 0xFFFFF
        val payloadLen = r.u16(p + 4)
        val nextHeader = r.u8(p + 6)
        val hopLimit = r.u8(p + 7)
        val src = Addr.ipv6(r, p + 8)
        val dst = Addr.ipv6(r, p + 24)
        field("Traffic class", p, 2) { "0x${hex8(tc)} (DSCP ${tc ushr 2}, ECN ${tc and 3})" }
        field("Flow label", p + 1, 3) { "0x${java.lang.Long.toHexString(flow).padStart(5, '0')}" }
        field("Payload length", p + 4, 2) { payloadLen.toString() }
        field("Next header", p + 6, 1) { "${ipProtoName(nextHeader)} ($nextHeader)" }
        field("Hop limit", p + 7, 1) { hopLimit.toString() }
        field("Source", p + 8, 16) { src }
        field("Destination", p + 24, 16) { dst }
        source = src; destination = dst; srcHost = src; dstHost = dst
        info = "$src → $dst (${ipProtoName(nextHeader)})"
        end("$src → $dst", 40)
        val ipEnd = if (payloadLen > 0 && p + 40 + payloadLen <= end) p + 40 + payloadLen else end
        var q = p + 40
        var nh = nextHeader
        var hops = 0
        while (hops++ < 8) {
            when (nh) {
                0, 43, 60, 51 -> {
                    val name = when (nh) { 0 -> "Hop-by-Hop Options"; 43 -> "Routing"; 60 -> "Destination Options"; else -> "Authentication Header" }
                    val hdrLen = r.u8(q + 1)
                    val len = if (nh == 51) (hdrLen + 2) * 4 else (hdrLen + 1) * 8
                    val next = r.u8(q)
                    begin("IPv6 $name", q, null)
                    field("Next header", q, 1) { "${ipProtoName(next)} ($next)" }
                    field("Length", q + 1, 1) { "$len bytes" }
                    if (nh == 43) field("Routing type", q + 2, 1) { r.u8(q + 2).toString() }
                    end(name, len)
                    nh = next
                    q += len
                }
                44 -> {
                    val next = r.u8(q)
                    val of = r.u16(q + 2)
                    val fragOff = (of ushr 3) * 8
                    val more = of and 1 != 0
                    val fragId = r.u32(q + 4)
                    begin("IPv6 Fragment", q, null)
                    field("Next header", q, 1) { "${ipProtoName(next)} ($next)" }
                    field("Offset", q + 2, 2) { fragOff.toString() }
                    field("More fragments", q + 2, 2) { more.toString() }
                    field("Identification", q + 4, 4) { "0x${java.lang.Long.toHexString(fragId).padStart(8, '0')}" }
                    end("Fragment offset $fragOff", 8)
                    nh = next
                    q += 8
                    if (fragOff > 0) {
                        info = "IPv6 fragment (off=$fragOff, more=$more, ID=0x${java.lang.Long.toHexString(fragId)})"
                        data(q, ipEnd)
                        return
                    }
                }
                59 -> {
                    info = "$src → $dst (No Next Header)"
                    data(q, ipEnd)
                    return
                }
                else -> {
                    ipProto(nh, q, ipEnd)
                    return
                }
            }
        }
        throw Malformed("Too many IPv6 extension headers")
    }

    private fun ipProto(proto: Int, p: Int, until: Int) {
        when (proto) {
            1 -> icmp(p, until)
            6 -> tcp(p, until)
            17 -> udp(p, until)
            58 -> icmpv6(p, until)
            4 -> ipv4(p)
            41 -> ipv6(p)
            else -> {
                val name = ipProtoName(proto)
                if (!name.startsWith("IPPROTO_")) {
                    protocol = name
                    info = name
                } else {
                    info = "IP protocol $proto"
                }
                data(p, until)
            }
        }
    }

    // ---- transport ------------------------------------------------------------------------

    private fun tcp(p: Int, until: Int) {
        begin("Transmission Control Protocol", p, "TCP")
        transport = "TCP"
        val sport = r.u16(p)
        val dport = r.u16(p + 2)
        srcPort = sport; dstPort = dport
        source = Addr.endpoint(srcHost, sport)
        destination = Addr.endpoint(dstHost, dport)
        info = "$sport → $dport"
        val seq = r.u32(p + 4)
        val ack = r.u32(p + 8)
        val dof = r.u16(p + 12)
        val hdrLen = (dof ushr 12) * 4
        val flags = dof and 0x1FF
        val window = r.u16(p + 14)
        val checksum = r.u16(p + 16)
        val urgent = r.u16(p + 18)
        val flagStr = tcpFlags(flags)
        field("Source port", p, 2) { sport.toString() }
        field("Destination port", p + 2, 2) { dport.toString() }
        field("Sequence number", p + 4, 4) { seq.toString() }
        field("Acknowledgment number", p + 8, 4) { ack.toString() }
        field("Header length", p + 12, 1) { "$hdrLen bytes" }
        field("Flags", p + 12, 2) { "0x${java.lang.Integer.toHexString(flags).padStart(3, '0')} $flagStr" }
        field("Window", p + 14, 2) { window.toString() }
        field("Checksum", p + 16, 2) { "0x${hex16(checksum)}" }
        field("Urgent pointer", p + 18, 2) { urgent.toString() }
        if (hdrLen < 20) throw Malformed("TCP header length $hdrLen < 20")
        val payloadStart = p + hdrLen
        val payloadLen = (until - payloadStart).coerceAtLeast(0)
        info = "$sport → $dport $flagStr Seq=$seq Ack=$ack Win=$window Len=$payloadLen"
        if (hdrLen > 20) {
            val summary = tcpOptions(p + 20, p + hdrLen)
            field("Options", p + 20, hdrLen - 20) { summary }
        }
        end("$sport → $dport $flagStr", hdrLen)
        if (payloadStart > end && r.limit >= end) throw Malformed("TCP header extends past packet")
        application(sport, dport, payloadStart, until, tcp = true)
    }

    /** Parses TCP options; also appends MSS/WS/SACK_PERM/TSval to [info] like Wireshark. */
    private fun tcpOptions(from: Int, to: Int): String {
        val parts = ArrayList<String>(4)
        val extra = StringBuilder()
        var o = from
        while (o < to) {
            val kind = r.u8(o)
            if (kind == 0) { parts.add("EOL"); break }
            if (kind == 1) { parts.add("NOP"); o++; continue }
            if (o + 1 >= to) throw Malformed("TCP option $kind truncated")
            val len = r.u8(o + 1)
            if (len < 2 || o + len > to) throw Malformed("TCP option $kind has length $len")
            when (kind) {
                2 -> if (len == 4) { val mss = r.u16(o + 2); parts.add("MSS=$mss"); extra.append(" MSS=$mss") } else parts.add("MSS(bad)")
                3 -> if (len == 3) { val ws = r.u8(o + 2); parts.add("WS=$ws"); extra.append(" WS=${1 shl ws.coerceAtMost(30)}") } else parts.add("WS(bad)")
                4 -> { parts.add("SACK_PERM"); extra.append(" SACK_PERM") }
                5 -> parts.add("SACK(${(len - 2) / 8} blocks)")
                8 -> if (len == 10) {
                    val tsVal = r.u32(o + 2); val tsEcr = r.u32(o + 6)
                    parts.add("TS val=$tsVal ecr=$tsEcr"); extra.append(" TSval=$tsVal TSecr=$tsEcr")
                } else parts.add("TS(bad)")
                else -> parts.add("Kind $kind ($len bytes)")
            }
            o += len
        }
        if (extra.isNotEmpty()) info += extra
        return parts.joinToString(", ")
    }

    private fun udp(p: Int, until: Int) {
        begin("User Datagram Protocol", p, "UDP")
        transport = "UDP"
        val sport = r.u16(p)
        val dport = r.u16(p + 2)
        srcPort = sport; dstPort = dport
        source = Addr.endpoint(srcHost, sport)
        destination = Addr.endpoint(dstHost, dport)
        info = "$sport → $dport"
        val length = r.u16(p + 4)
        val checksum = r.u16(p + 6)
        val udpEnd = if (length >= 8 && p + length <= until) p + length else until
        val payloadLen = (udpEnd - p - 8).coerceAtLeast(0)
        field("Source port", p, 2) { sport.toString() }
        field("Destination port", p + 2, 2) { dport.toString() }
        field("Length", p + 4, 2) { length.toString() }
        field("Checksum", p + 6, 2) { "0x${hex16(checksum)}" }
        info = "$sport → $dport Len=$payloadLen"
        end("$sport → $dport", 8)
        if (length in 1..7) throw Malformed("UDP length $length < 8")
        application(sport, dport, p + 8, udpEnd, tcp = false)
    }

    private fun icmp(p: Int, until: Int) {
        begin("Internet Control Message Protocol", p, "ICMP")
        transport = "ICMP"
        val type = r.u8(p)
        val code = r.u8(p + 1)
        val checksum = r.u16(p + 2)
        val typeName = icmpType(type, code)
        field("Type", p, 1) { "$type ($typeName)" }
        field("Code", p + 1, 1) { code.toString() }
        field("Checksum", p + 2, 2) { "0x${hex16(checksum)}" }
        info = typeName
        if (type == 0 || type == 8) {
            val id = r.u16(p + 4)
            val seq = r.u16(p + 6)
            field("Identifier", p + 4, 2) { "0x${hex16(id)} ($id)" }
            field("Sequence number", p + 6, 2) { seq.toString() }
            info = "$typeName id=0x${hex16(id)}, seq=$seq"
        }
        end(typeName, 8)
        data(p + 8, until)
    }

    private fun icmpv6(p: Int, until: Int) {
        begin("Internet Control Message Protocol v6", p, "ICMPv6")
        transport = "ICMPv6"
        val type = r.u8(p)
        val code = r.u8(p + 1)
        val checksum = r.u16(p + 2)
        val typeName = icmpv6Type(type, code)
        field("Type", p, 1) { "$type ($typeName)" }
        field("Code", p + 1, 1) { code.toString() }
        field("Checksum", p + 2, 2) { "0x${hex16(checksum)}" }
        info = typeName
        var hdrLen = 4
        when (type) {
            128, 129 -> {
                val id = r.u16(p + 4)
                val seq = r.u16(p + 6)
                field("Identifier", p + 4, 2) { "0x${hex16(id)} ($id)" }
                field("Sequence number", p + 6, 2) { seq.toString() }
                info = "$typeName id=0x${hex16(id)}, seq=$seq"
                hdrLen = 8
            }
            133 -> hdrLen = 8
            134 -> {
                field("Cur hop limit", p + 4, 1) { r.u8(p + 4).toString() }
                field("Router lifetime", p + 6, 2) { "${r.u16(p + 6)} s" }
                hdrLen = 16
            }
            135, 136 -> {
                val target = Addr.ipv6(r, p + 8)
                field("Target address", p + 8, 16) { target }
                if (type == 136) {
                    val f = r.u8(p + 4)
                    val flags = listOfNotNull(
                        "rtr".takeIf { f and 0x80 != 0 }, "sol".takeIf { f and 0x40 != 0 }, "ovr".takeIf { f and 0x20 != 0 },
                    )
                    field("Flags", p + 4, 1) { flags.joinToString(", ").ifEmpty { "none" } }
                    info = "Neighbor Advertisement $target" + if (flags.isEmpty()) "" else " (${flags.joinToString(", ")})"
                } else {
                    info = "Neighbor Solicitation for $target"
                }
                hdrLen = 24
            }
            137 -> {
                field("Target address", p + 8, 16) { Addr.ipv6(r, p + 8) }
                field("Destination address", p + 24, 16) { Addr.ipv6(r, p + 24) }
                hdrLen = 40
            }
        }
        if (type in 133..137) {
            val linkAddr = ndpOptions(p + hdrLen, until)
            if (linkAddr != null && (type == 135 || type == 133)) info += " from $linkAddr"
        }
        end(typeName, hdrLen.coerceAtMost((until - p).coerceAtLeast(0)))
        data(p + hdrLen, until)
    }

    /** Lists NDP options as fields; returns the source/target link-layer MAC when present. */
    private fun ndpOptions(from: Int, until: Int): String? {
        var o = from
        var mac: String? = null
        var count = 0
        while (o + 2 <= until && count++ < 16) {
            val type = r.u8(o)
            val len = r.u8(o + 1) * 8
            if (len == 0) throw Malformed("NDP option with zero length")
            if (o + len > until) break
            val name = when (type) {
                1 -> "Source link-layer address"; 2 -> "Target link-layer address"; 3 -> "Prefix information"
                4 -> "Redirected header"; 5 -> "MTU"; 25 -> "Recursive DNS server"; else -> "Option $type"
            }
            if ((type == 1 || type == 2) && len == 8) {
                val m = Addr.mac(r, o + 2)
                if (mac == null) mac = m
                field(name, o, len) { m }
            } else if (type == 5 && len == 8) {
                field(name, o, len) { r.u32(o + 4).toString() }
            } else {
                field(name, o, len) { "$len bytes" }
            }
            o += len
        }
        return mac
    }

    // ---- application ----------------------------------------------------------------------

    private fun application(sport: Int, dport: Int, p: Int, until: Int, tcp: Boolean) {
        val len = until - p
        payloadOffset = p.coerceAtMost(end)
        payloadLength = len.coerceAtLeast(0)
        appOffset = payloadOffset
        appLength = (minOf(until, end) - appOffset).coerceAtLeast(0)
        if (len <= 0) return
        fun port(n: Int) = sport == n || dport == n
        when {
            port(53) || port(5353) -> dns(p, until, tcp, mdns = port(5353))
            tcp && looksLikeTls(p, until) -> tls(p, until)
            looksLikeHttp(p, until) -> http(p, until, if (!tcp && port(1900)) "SSDP" else "HTTP")
            tcp && port(443) -> tls(p, until)
            tcp && (port(80) || port(8080) || port(8000)) -> http(p, until, "HTTP")
            tcp && port(22) -> ssh(p, until)
            !tcp && (port(67) || port(68)) -> dhcp(p, until)
            !tcp && port(123) -> ntp(p, until)
            !tcp && port(443) -> quic(p, until)
            else -> data(p, until)
        }
    }

    // DNS -------------------------------------------------------------------------------------

    private fun dns(p: Int, until: Int, tcp: Boolean, mdns: Boolean) {
        var q = p
        if (tcp) {
            if (until - p < 2) { data(p, until); return }
            q += 2
        }
        if (until - q < 12) { data(p, until); return }
        begin(if (mdns) "Multicast Domain Name System" else "Domain Name System", p, if (mdns) "mDNS" else "DNS")
        if (tcp) field("Length", p, 2) { r.u16(p).toString() }
        val id = r.u16(q)
        val flags = r.u16(q + 2)
        val qd = r.u16(q + 4)
        val an = r.u16(q + 6)
        val ns = r.u16(q + 8)
        val ar = r.u16(q + 10)
        val qr = flags and 0x8000 != 0
        val opcode = (flags ushr 11) and 0xF
        val rcode = flags and 0xF
        field("Transaction ID", q, 2) { "0x${hex16(id)}" }
        field("Flags", q + 2, 2) {
            val f = ArrayList<String>(6)
            f.add(if (qr) "Response" else "Query")
            f.add(dnsOpcode(opcode))
            if (flags and 0x0400 != 0) f.add("Authoritative")
            if (flags and 0x0200 != 0) f.add("Truncated")
            if (flags and 0x0100 != 0) f.add("Recursion desired")
            if (flags and 0x0080 != 0) f.add("Recursion available")
            if (qr) f.add(dnsRcode(rcode))
            "0x${hex16(flags)} (${f.joinToString(", ")})"
        }
        field("Questions", q + 4, 2) { qd.toString() }
        field("Answer RRs", q + 6, 2) { an.toString() }
        field("Authority RRs", q + 8, 2) { ns.toString() }
        field("Additional RRs", q + 10, 2) { ar.toString() }
        val head = StringBuilder()
        head.append(dnsOpcode(opcode)).append(if (qr) " response" else "").append(" 0x").append(hex16(id))
        if (qr && rcode != 0) head.append(' ').append(dnsRcode(rcode))
        info = head.toString()
        var o = q + 12
        var i = 0
        while (i < qd && i < 32 && o < until) {
            val (name, next) = dnsName(o, q, until)
            val type = r.u16(next)
            val cls = r.u16(next + 2)
            field("Query", o, next + 4 - o) { "$name: type ${dnsType(type)}, class ${dnsClass(cls)}" }
            if (i == 0) {
                head.append(' ').append(dnsType(type)).append(' ').append(name)
                info = head.toString()
            }
            o = next + 4
            i++
        }
        var shown = 0
        for (section in 0 until 3) {
            val count = when (section) { 0 -> an; 1 -> ns; else -> ar }
            val label = when (section) { 0 -> "Answer"; 1 -> "Authority"; else -> "Additional" }
            var j = 0
            while (j < count && j < 64 && o < until) {
                val (name, next) = dnsName(o, q, until)
                val type = r.u16(next)
                val cls = r.u16(next + 2)
                val ttl = r.u32(next + 4)
                val rdLen = r.u16(next + 8)
                val rdOff = next + 10
                if (rdOff + rdLen > until) throw Malformed("DNS record data exceeds packet")
                val isOpt = type == 41
                val rdata = if (isOpt) "EDNS0, UDP payload size $cls" else dnsRdata(type, rdOff, rdLen, q, until)
                field(label, o, rdOff + rdLen - o) {
                    if (isOpt) "<Root>: type OPT, $rdata" else "$name: type ${dnsType(type)}, class ${dnsClass(cls)}, ttl $ttl, $rdata"
                }
                if (section == 0 && shown < 3) {
                    head.append(' ').append(dnsType(type)).append(' ').append(rdata)
                    info = head.toString()
                    shown++
                }
                o = rdOff + rdLen
                j++
            }
        }
        end(info, until - p)
        payloadOffset = until.coerceAtMost(end)
        payloadLength = 0
    }

    /** Reads a possibly compressed name at [o]; returns the name and the offset after it in the sequential stream. */
    private fun dnsName(o: Int, base: Int, until: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var pos = o
        var after = -1
        var jumps = 0
        var total = 0
        while (true) {
            if (pos >= until) throw Malformed("DNS name runs past packet")
            val len = r.u8(pos)
            if (len == 0) {
                if (after < 0) after = pos + 1
                break
            }
            if (len and 0xC0 == 0xC0) {
                if (pos + 1 >= until) throw Malformed("DNS name pointer truncated")
                val target = base + (((len and 0x3F) shl 8) or r.u8(pos + 1))
                if (after < 0) after = pos + 2
                if (++jumps > 16 || target >= pos) throw Malformed("DNS name pointer loop")
                pos = target
                continue
            }
            if (len and 0xC0 != 0) throw Malformed("DNS label type 0x${hex8(len and 0xC0)}")
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(r.ascii(pos + 1, len))
            total += len + 1
            if (total > 255) throw Malformed("DNS name longer than 255 bytes")
            pos += 1 + len
        }
        return (if (sb.isEmpty()) "<Root>" else sb.toString()) to after
    }

    private fun dnsRdata(type: Int, at: Int, len: Int, base: Int, until: Int): String = when {
        type == 1 && len == 4 -> Addr.ipv4(r, at)
        type == 28 && len == 16 -> Addr.ipv6(r, at)
        type == 5 || type == 12 || type == 2 -> dnsName(at, base, until).first
        type == 15 && len >= 3 -> "${r.u16(at)} ${dnsName(at + 2, base, until).first}"
        type == 33 && len >= 7 -> "${r.u16(at)} ${r.u16(at + 2)} ${r.u16(at + 4)} ${dnsName(at + 6, base, until).first}"
        type == 6 -> { val (m, n) = dnsName(at, base, until); "$m ${dnsName(n, base, until).first}" }
        type == 16 -> {
            val sb = StringBuilder()
            var o = at
            while (o < at + len) {
                val l = r.u8(o)
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append('"').append(r.ascii(o + 1, l.coerceAtMost(at + len - o - 1))).append('"')
                o += 1 + l
            }
            sb.toString()
        }
        else -> "$len bytes"
    }

    // HTTP ------------------------------------------------------------------------------------

    private fun looksLikeHttp(p: Int, until: Int): Boolean {
        if (until - p < 5) return false
        for (m in HTTP_STARTS) if (r.startsWith(p, m)) return true
        return false
    }

    private fun http(p: Int, until: Int, name: String) {
        begin("Hypertext Transfer Protocol", p, name)
        if (!looksLikeHttp(p, until)) {
            info = "Continuation"
            field("Continuation data", p, until - p) { "${until - p} bytes" }
            end("Continuation", until - p)
            return
        }
        var o = p
        var first = true
        var headers = 0
        var bodyStart = until
        while (o < until && headers < 32) {
            val eol = lineEnd(o, until)
            val lineLen = eol - o
            val trimmed = if (lineLen > 0 && r.u8(eol - 1) == '\r'.code) lineLen - 1 else lineLen
            val nextLine = if (eol < until) eol + 1 else until
            if (trimmed == 0) { bodyStart = nextLine; break }
            val text = r.ascii(o, trimmed.coerceAtMost(512))
            if (first) {
                info = text
                field(if (text.startsWith("HTTP/")) "Status line" else "Request line", o, trimmed) { text }
                first = false
            } else {
                val colon = text.indexOf(':')
                val hName = if (colon > 0) text.substring(0, colon) else "Header"
                val hValue = if (colon > 0) text.substring(colon + 1).trim() else text
                field(hName, o, trimmed) { hValue }
                headers++
            }
            o = nextLine
        }
        if (bodyStart < until) field("Body", bodyStart, until - bodyStart) { "${until - bodyStart} bytes" }
        end(info, until - p)
        payloadOffset = bodyStart.coerceAtMost(end)
        payloadLength = (until - bodyStart).coerceAtLeast(0)
    }

    /** Offset of the first '\n' at or after [from], or [until]. */
    private fun lineEnd(from: Int, until: Int): Int {
        var o = from
        while (o < until) {
            if (r.u8(o) == '\n'.code) return o
            o++
        }
        return until
    }

    // TLS -------------------------------------------------------------------------------------

    private fun looksLikeTls(p: Int, until: Int): Boolean {
        if (until - p < 5) return false
        val type = r.u8(p)
        return type in 0x14..0x17 && r.u8(p + 1) == 3 && r.u8(p + 2) <= 4
    }

    private fun tls(p: Int, until: Int) {
        var o = p
        var records = 0
        val descs = ArrayList<String>(2)
        protocol = "TLS"
        while (o + 5 <= until && records < 16) {
            val type = r.u8(o)
            val ver = r.u16(o + 1)
            val len = r.u16(o + 3)
            if (type !in 0x14..0x18 || ver ushr 8 != 3) {
                if (records == 0) {
                    info = "Continuation data"
                    data(o, until, "TLS segment data")
                    return
                }
                break
            }
            val recEnd = minOf(o + 5 + len, until)
            begin("Transport Layer Security", o, null)
            field("Content type", o, 1) { "${tlsContentType(type)} ($type)" }
            field("Version", o + 1, 2) { "${tlsVersion(ver)} (0x${hex16(ver)})" }
            field("Length", o + 3, 2) { len.toString() }
            var desc = tlsContentType(type)
            descs.add(desc)
            info = descs.joinToString(", ")
            when (type) {
                0x15 -> if (recEnd - o >= 7 && r.u8(o + 5) in 1..2) {
                    desc = "Alert (${if (r.u8(o + 5) == 1) "Warning" else "Fatal"})"
                } else {
                    desc = "Encrypted Alert"
                }
                0x16 -> if (recEnd - o >= 9) {
                    val hs = r.u8(o + 5)
                    val hsLen = r.u24(o + 6)
                    val hsName = tlsHandshakeType(hs)
                    field("Handshake type", o + 5, 1) { "$hsName ($hs)" }
                    field("Handshake length", o + 6, 3) { hsLen.toString() }
                    desc = hsName
                    descs[descs.size - 1] = desc
                    info = descs.joinToString(", ")
                    if (hs == 1) {
                        val sni = clientHello(o + 9, minOf(o + 9 + hsLen, recEnd))
                        if (sni != null) desc = "Client Hello (SNI=$sni)"
                    }
                }
            }
            descs[descs.size - 1] = desc
            info = descs.joinToString(", ")
            end("${tlsVersion(ver)} Record Layer: $desc", recEnd - o)
            records++
            o += 5 + len
        }
        if (o < until && o + 5 > until) {
            data(o, until, "TLS segment data")
        } else {
            payloadOffset = minOf(o, until, end)
            payloadLength = 0
        }
    }

    /** Parses ClientHello fields; returns the SNI host name when present. */
    private fun clientHello(h: Int, limit: Int): String? {
        if (h + 34 > limit) return null
        val ver = r.u16(h)
        field("Client version", h, 2) { "${tlsVersion(ver)} (0x${hex16(ver)})" }
        field("Random", h + 2, 32) { Addr.hex(r.slice(h + 2, 32)) }
        val sidLen = r.u8(h + 34)
        field("Session ID length", h + 34, 1) { sidLen.toString() }
        val cs = h + 35 + sidLen
        val csLen = r.u16(cs)
        field("Cipher suites", cs + 2, csLen) { "${csLen / 2} suites" }
        val comp = cs + 2 + csLen
        val compLen = r.u8(comp)
        field("Compression methods", comp + 1, compLen) { "$compLen method(s)" }
        val ext = comp + 1 + compLen
        if (ext + 2 > limit) return null
        val extTotal = r.u16(ext)
        var e = ext + 2
        val extEnd = minOf(e + extTotal, limit)
        var sni: String? = null
        var count = 0
        while (e + 4 <= extEnd && count++ < 64) {
            val et = r.u16(e)
            val el = r.u16(e + 2)
            if (e + 4 + el > extEnd) break
            when (et) {
                0 -> if (el >= 5) {
                    val nameLen = r.u16(e + 7)
                    if (r.u8(e + 6) == 0 && nameLen > 0 && e + 9 + nameLen <= e + 4 + el) {
                        sni = r.ascii(e + 9, nameLen)
                        field("Server name", e + 9, nameLen) { sni }
                    }
                }
                16 -> if (el >= 3) {
                    val protos = ArrayList<String>(2)
                    var a = e + 6
                    val aEnd = e + 4 + el
                    while (a < aEnd && protos.size < 8) {
                        val l = r.u8(a)
                        if (l == 0 || a + 1 + l > aEnd) break
                        protos.add(r.ascii(a + 1, l))
                        a += 1 + l
                    }
                    field("ALPN", e + 4, el) { protos.joinToString(", ") }
                }
                43 -> if (el >= 1) {
                    val n = r.u8(e + 4) / 2
                    val vs = ArrayList<String>(n)
                    for (i in 0 until n.coerceAtMost(8)) vs.add(tlsVersion(r.u16(e + 5 + i * 2)))
                    field("Supported versions", e + 4, el) { vs.joinToString(", ") }
                }
                else -> field("Extension", e, 4 + el) { "${tlsExtensionName(et)} ($et), $el bytes" }
            }
            e += 4 + el
        }
        return sni
    }

    // small UDP/TCP services --------------------------------------------------------------------

    private fun ssh(p: Int, until: Int) {
        begin("SSH Protocol", p, "SSH")
        if (r.startsWith(p, "SSH-")) {
            val eol = lineEnd(p, until)
            var len = eol - p
            if (len > 0 && r.u8(eol - 1) == '\r'.code) len--
            val text = r.ascii(p, len.coerceAtMost(255))
            field("Protocol", p, len) { text }
            info = "Protocol: $text"
        } else {
            field("Encrypted packet", p, until - p) { "${until - p} bytes" }
            info = "Encrypted packet (len=${until - p})"
        }
        end(info, until - p)
        payloadOffset = p
        payloadLength = until - p
    }

    private fun dhcp(p: Int, until: Int) {
        begin("Dynamic Host Configuration Protocol", p, "DHCP")
        val op = r.u8(p)
        val htype = r.u8(p + 1)
        val hlen = r.u8(p + 2)
        val xid = r.u32(p + 4)
        val xidHex = java.lang.Long.toHexString(xid).padStart(8, '0')
        field("Message type", p, 1) { if (op == 1) "Boot Request (1)" else if (op == 2) "Boot Reply (2)" else op.toString() }
        field("Hardware type", p + 1, 1) { if (htype == 1) "Ethernet (1)" else htype.toString() }
        field("Transaction ID", p + 4, 4) { "0x$xidHex" }
        info = "DHCP ${if (op == 1) "Request" else "Reply"} - Transaction ID 0x$xidHex"
        field("Client IP address", p + 12, 4) { Addr.ipv4(r, p + 12) }
        field("Your (client) IP address", p + 16, 4) { Addr.ipv4(r, p + 16) }
        field("Next server IP address", p + 20, 4) { Addr.ipv4(r, p + 20) }
        field("Relay agent IP address", p + 24, 4) { Addr.ipv4(r, p + 24) }
        if (hlen == 6) field("Client MAC address", p + 28, 6) { Addr.mac(r, p + 28) }
        if (p + 240 <= until && r.u32(p + 236) == 0x63825363L) {
            var o = p + 240
            var count = 0
            while (o < until && count++ < 64) {
                val code = r.u8(o)
                if (code == 0) { o++; continue }
                if (code == 255) break
                if (o + 1 >= until) break
                val len = r.u8(o + 1)
                if (o + 2 + len > until) break
                when (code) {
                    53 -> if (len == 1) {
                        val t = dhcpMessageType(r.u8(o + 2))
                        field("Option 53: DHCP Message Type", o, 2 + len) { t }
                        info = "DHCP $t - Transaction ID 0x$xidHex"
                    }
                    50 -> if (len == 4) field("Option 50: Requested IP Address", o, 2 + len) { Addr.ipv4(r, o + 2) }
                    54 -> if (len == 4) field("Option 54: DHCP Server Identifier", o, 2 + len) { Addr.ipv4(r, o + 2) }
                    12 -> field("Option 12: Host Name", o, 2 + len) { r.ascii(o + 2, len) }
                    51 -> if (len == 4) field("Option 51: IP Address Lease Time", o, 2 + len) { "${r.u32(o + 2)} s" }
                    else -> field("Option $code", o, 2 + len) { "$len bytes" }
                }
                o += 2 + len
            }
        }
        end(info, until - p)
        payloadOffset = until.coerceAtMost(end)
        payloadLength = 0
    }

    private fun ntp(p: Int, until: Int) {
        begin("Network Time Protocol", p, "NTP")
        val b = r.u8(p)
        val li = b ushr 6
        val vn = (b ushr 3) and 7
        val mode = b and 7
        val modeName = when (mode) {
            1 -> "symmetric active"; 2 -> "symmetric passive"; 3 -> "client"; 4 -> "server"
            5 -> "broadcast"; 6 -> "control"; 7 -> "private"; else -> "reserved"
        }
        field("Leap indicator", p, 1) { li.toString() }
        field("Version", p, 1) { vn.toString() }
        field("Mode", p, 1) { "$modeName ($mode)" }
        if (until - p >= 4) {
            field("Stratum", p + 1, 1) { r.u8(p + 1).toString() }
            field("Poll", p + 2, 1) { r.u8(p + 2).toString() }
            field("Precision", p + 3, 1) { r.u8(p + 3).toByte().toString() }
        }
        if (until - p >= 48) {
            val secs = r.u32(p + 40)
            field("Transmit timestamp", p + 40, 8) {
                if (secs == 0L) "0" else PcapTime.formatUtc((secs - NTP_EPOCH_OFFSET) * 1_000_000L)
            }
        }
        info = "NTP Version $vn, $modeName"
        end(info, until - p)
        payloadOffset = until.coerceAtMost(end)
        payloadLength = 0
    }

    private fun quic(p: Int, until: Int) {
        begin("QUIC", p, "QUIC")
        val first = r.u8(p)
        val long = first and 0x80 != 0
        field("Header form", p, 1) { if (long) "Long Header" else "Short Header" }
        if (long && until - p >= 5) {
            val version = r.u32(p + 1)
            field("Version", p + 1, 4) { "0x${java.lang.Long.toHexString(version).padStart(8, '0')}" }
        }
        info = "QUIC ${if (long) "Long Header" else "Short Header"}, Len=${until - p}"
        end(info, until - p)
        payloadOffset = p
        payloadLength = until - p
    }

    companion object {
        private const val NTP_EPOCH_OFFSET = 2_208_988_800L
        private val HTTP_STARTS = listOf(
            "GET ", "POST ", "PUT ", "DELETE ", "HEAD ", "OPTIONS ", "PATCH ", "CONNECT ", "TRACE ", "HTTP/1.",
            "M-SEARCH ", "NOTIFY ",
        )
    }
}

// ---- name tables ----------------------------------------------------------------------------

private fun hex16(v: Int) = Integer.toHexString(v and 0xFFFF).padStart(4, '0')
private fun hex8(v: Int) = Integer.toHexString(v and 0xFF).padStart(2, '0')

private fun etherTypeName(type: Int): String = when (type) {
    0x0800 -> "IPv4"; 0x0806 -> "ARP"; 0x86DD -> "IPv6"; 0x8100 -> "802.1Q"; 0x88A8 -> "802.1ad"; 0x9100 -> "QinQ"
    0x8035 -> "RARP"; 0x8847 -> "MPLS"; 0x8848 -> "MPLS multicast"; 0x8863 -> "PPPoE Discovery"; 0x8864 -> "PPPoE Session"
    0x888E -> "EAPOL"; 0x88CC -> "LLDP"; 0x8809 -> "LACP"; 0x880B -> "PPP"; 0x8137 -> "IPX"; 0x88F7 -> "PTP"
    0x892F -> "HSR"; 0x22F0 -> "AVTP"; 0x8906 -> "FCoE"; 0x8915 -> "RoCE"; 0x893A -> "1905.1"; 0x86DC -> "WSMP"
    else -> if (type < 0x0600) "Length" else "0x${hex16(type)}"
}

private fun sllPacketType(t: Int): String = when (t) {
    0 -> "Unicast to us"; 1 -> "Broadcast"; 2 -> "Multicast"; 3 -> "Unicast to another host"; 4 -> "Sent by us"; else -> "Type $t"
}

private fun arpOpcode(op: Int): String = when (op) {
    1 -> "ARP request"; 2 -> "ARP reply"; 3 -> "RARP request"; 4 -> "RARP reply"; 8 -> "InARP request"; 9 -> "InARP reply"; else -> "Opcode $op"
}

private fun ipProtoName(p: Int): String = when (p) {
    0 -> "HOPOPT"; 1 -> "ICMP"; 2 -> "IGMP"; 4 -> "IPIP"; 6 -> "TCP"; 17 -> "UDP"; 41 -> "IPv6"; 43 -> "IPv6-Route"
    44 -> "IPv6-Frag"; 47 -> "GRE"; 50 -> "ESP"; 51 -> "AH"; 58 -> "ICMPv6"; 59 -> "IPv6-NoNxt"; 60 -> "IPv6-Opts"
    88 -> "EIGRP"; 89 -> "OSPF"; 103 -> "PIM"; 112 -> "VRRP"; 115 -> "L2TP"; 132 -> "SCTP"; 136 -> "UDPLite"
    else -> "IPPROTO_$p"
}

private fun tcpFlags(flags: Int): String {
    val names = arrayOf("FIN", "SYN", "RST", "PSH", "ACK", "URG", "ECE", "CWR", "NS")
    val sb = StringBuilder("[")
    for (i in names.indices) {
        if (flags and (1 shl i) != 0) {
            if (sb.length > 1) sb.append(", ")
            sb.append(names[i])
        }
    }
    if (sb.length == 1) sb.append("<None>")
    return sb.append(']').toString()
}

private fun icmpType(type: Int, code: Int): String = when (type) {
    0 -> "Echo (ping) reply"
    3 -> "Destination unreachable (" + when (code) {
        0 -> "Network unreachable"; 1 -> "Host unreachable"; 2 -> "Protocol unreachable"; 3 -> "Port unreachable"
        4 -> "Fragmentation needed"; 5 -> "Source route failed"; 9 -> "Network administratively prohibited"
        10 -> "Host administratively prohibited"; 13 -> "Communication administratively filtered"; else -> "Code $code"
    } + ")"
    4 -> "Source quench"
    5 -> "Redirect"
    8 -> "Echo (ping) request"
    9 -> "Router advertisement"
    10 -> "Router solicitation"
    11 -> "Time-to-live exceeded (" + (if (code == 0) "Time to live exceeded in transit" else if (code == 1) "Fragment reassembly time exceeded" else "Code $code") + ")"
    12 -> "Parameter problem"
    13 -> "Timestamp request"
    14 -> "Timestamp reply"
    else -> "Type $type, code $code"
}

private fun icmpv6Type(type: Int, code: Int): String = when (type) {
    1 -> "Destination Unreachable (" + when (code) {
        0 -> "No route to destination"; 1 -> "Administratively prohibited"; 3 -> "Address unreachable"; 4 -> "Port unreachable"; else -> "Code $code"
    } + ")"
    2 -> "Packet Too Big"
    3 -> "Time Exceeded (" + (if (code == 0) "hop limit exceeded in transit" else "fragment reassembly time exceeded") + ")"
    4 -> "Parameter Problem"
    128 -> "Echo (ping) request"
    129 -> "Echo (ping) reply"
    130 -> "Multicast Listener Query"
    131 -> "Multicast Listener Report"
    132 -> "Multicast Listener Done"
    133 -> "Router Solicitation"
    134 -> "Router Advertisement"
    135 -> "Neighbor Solicitation"
    136 -> "Neighbor Advertisement"
    137 -> "Redirect"
    143 -> "Multicast Listener Report Message v2"
    else -> "Type $type, code $code"
}

private fun dnsOpcode(op: Int): String = when (op) {
    0 -> "Standard query"; 1 -> "Inverse query"; 2 -> "Server status request"; 4 -> "Notify"; 5 -> "Dynamic update"; else -> "Opcode $op"
}

private fun dnsRcode(rc: Int): String = when (rc) {
    0 -> "No error"; 1 -> "Format error"; 2 -> "Server failure"; 3 -> "No such name"; 4 -> "Not implemented"; 5 -> "Refused"
    6 -> "YXDomain"; 7 -> "YXRRSet"; 8 -> "NXRRSet"; 9 -> "Not authoritative"; 10 -> "Not zone"; else -> "Rcode $rc"
}

private fun dnsType(t: Int): String = when (t) {
    1 -> "A"; 2 -> "NS"; 5 -> "CNAME"; 6 -> "SOA"; 12 -> "PTR"; 13 -> "HINFO"; 15 -> "MX"; 16 -> "TXT"; 28 -> "AAAA"
    33 -> "SRV"; 35 -> "NAPTR"; 41 -> "OPT"; 43 -> "DS"; 46 -> "RRSIG"; 47 -> "NSEC"; 48 -> "DNSKEY"; 50 -> "NSEC3"
    64 -> "SVCB"; 65 -> "HTTPS"; 99 -> "SPF"; 252 -> "AXFR"; 255 -> "ANY"; 257 -> "CAA"; else -> "TYPE$t"
}

private fun dnsClass(c: Int): String = when (c and 0x7FFF) {
    1 -> "IN"; 3 -> "CH"; 4 -> "HS"; 254 -> "NONE"; 255 -> "ANY"; else -> "CLASS${c and 0x7FFF}"
}

private fun tlsContentType(t: Int): String = when (t) {
    0x14 -> "Change Cipher Spec"; 0x15 -> "Alert"; 0x16 -> "Handshake"; 0x17 -> "Application Data"; 0x18 -> "Heartbeat"; else -> "Type $t"
}

private fun tlsVersion(v: Int): String = when (v) {
    0x0300 -> "SSL 3.0"; 0x0301 -> "TLS 1.0"; 0x0302 -> "TLS 1.1"; 0x0303 -> "TLS 1.2"; 0x0304 -> "TLS 1.3"; else -> "0x${hex16(v)}"
}

private fun tlsHandshakeType(t: Int): String = when (t) {
    0 -> "Hello Request"; 1 -> "Client Hello"; 2 -> "Server Hello"; 4 -> "New Session Ticket"; 8 -> "Encrypted Extensions"
    11 -> "Certificate"; 12 -> "Server Key Exchange"; 13 -> "Certificate Request"; 14 -> "Server Hello Done"
    15 -> "Certificate Verify"; 16 -> "Client Key Exchange"; 20 -> "Finished"; else -> "Handshake type $t"
}

private fun tlsExtensionName(t: Int): String = when (t) {
    0 -> "server_name"; 1 -> "max_fragment_length"; 5 -> "status_request"; 10 -> "supported_groups"; 11 -> "ec_point_formats"
    13 -> "signature_algorithms"; 16 -> "application_layer_protocol_negotiation"; 18 -> "signed_certificate_timestamp"
    21 -> "padding"; 23 -> "extended_master_secret"; 27 -> "compress_certificate"; 35 -> "session_ticket"
    41 -> "pre_shared_key"; 42 -> "early_data"; 43 -> "supported_versions"; 44 -> "cookie"; 45 -> "psk_key_exchange_modes"
    51 -> "key_share"; 65281 -> "renegotiation_info"; else -> "type $t"
}

private fun dhcpMessageType(t: Int): String = when (t) {
    1 -> "Discover"; 2 -> "Offer"; 3 -> "Request"; 4 -> "Decline"; 5 -> "ACK"; 6 -> "NAK"; 7 -> "Release"; 8 -> "Inform"; else -> "Type $t"
}
