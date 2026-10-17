package com.nitinshukla.notebook.core.pcap

import java.io.ByteArrayOutputStream

/** Growable byte buffer with big/little-endian writers for hand-assembling captures and packets. */
class Buf(private val little: Boolean = false) {
    private val out = ByteArrayOutputStream()
    val size: Int get() = out.size()

    fun u8(v: Int) = apply { out.write(v and 0xFF) }
    fun u16(v: Int) = apply { if (little) { u8(v); u8(v ushr 8) } else { u8(v ushr 8); u8(v) } }
    fun u32(v: Long) = apply { if (little) { u16(v.toInt()); u16((v ushr 16).toInt()) } else { u16((v ushr 16).toInt()); u16(v.toInt()) } }
    fun u32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
    fun u64(v: Long) = apply { if (little) { u32(v); u32(v ushr 32) } else { u32(v ushr 32); u32(v) } }
    fun bytes(b: ByteArray) = apply { out.write(b) }
    fun ascii(s: String) = apply { out.write(s.toByteArray(Charsets.ISO_8859_1)) }
    fun hex(s: String) = bytes(hexBytes(s))
    fun pad4() = apply { while (size % 4 != 0) u8(0) }
    fun zeros(n: Int) = apply { repeat(n) { u8(0) } }
    fun build(): ByteArray = out.toByteArray()
}

fun hexBytes(s: String): ByteArray {
    val clean = s.replace(Regex("[^0-9a-fA-F]"), "")
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun mac(s: String): ByteArray = hexBytes(s.replace(":", ""))
fun ip4(s: String): ByteArray = s.split('.').map { it.toInt().toByte() }.toByteArray()

/** Expands an IPv6 literal (with optional ::) into 16 bytes. */
fun ip6(s: String): ByteArray {
    val parts = s.split("::")
    val head = if (parts[0].isEmpty()) emptyList() else parts[0].split(':')
    val tail = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].split(':') else emptyList()
    val groups = IntArray(8)
    head.forEachIndexed { i, g -> groups[i] = g.toInt(16) }
    tail.forEachIndexed { i, g -> groups[8 - tail.size + i] = g.toInt(16) }
    val b = Buf()
    groups.forEach { b.u16(it) }
    return b.build()
}

// ---- packets ----------------------------------------------------------------------------------

object Pk {
    const val MAC_A = "aa:bb:cc:dd:ee:01"
    const val MAC_B = "aa:bb:cc:dd:ee:02"

    fun eth(payload: ByteArray, type: Int, dst: String = MAC_B, src: String = MAC_A): ByteArray =
        Buf().bytes(mac(dst)).bytes(mac(src)).u16(type).bytes(payload).build()

    fun vlan(payload: ByteArray, vid: Int, innerType: Int, dst: String = MAC_B, src: String = MAC_A): ByteArray =
        Buf().bytes(mac(dst)).bytes(mac(src)).u16(0x8100).u16(vid).u16(innerType).bytes(payload).build()

    fun arp(op: Int, sha: String, spa: String, tha: String, tpa: String): ByteArray =
        Buf().u16(1).u16(0x0800).u8(6).u8(4).u16(op).bytes(mac(sha)).bytes(ip4(spa)).bytes(mac(tha)).bytes(ip4(tpa)).build()

    fun ipv4(
        payload: ByteArray, proto: Int, src: String = "10.0.0.1", dst: String = "10.0.0.2",
        ttl: Int = 64, id: Int = 0x1234, flags: Int = 0x4000, options: ByteArray = ByteArray(0),
    ): ByteArray {
        val ihl = 5 + options.size / 4
        return Buf().u8(0x40 or ihl).u8(0).u16(ihl * 4 + payload.size).u16(id).u16(flags).u8(ttl).u8(proto).u16(0)
            .bytes(ip4(src)).bytes(ip4(dst)).bytes(options).bytes(payload).build()
    }

    fun ipv6(payload: ByteArray, next: Int, src: String = "2001:db8::1", dst: String = "2001:db8::2", hop: Int = 64): ByteArray =
        Buf().u32(0x60000000L).u16(payload.size).u8(next).u8(hop).bytes(ip6(src)).bytes(ip6(dst)).bytes(payload).build()

    fun tcp(
        sport: Int, dport: Int, flags: Int, seq: Long = 0, ack: Long = 0, window: Int = 65535,
        options: ByteArray = ByteArray(0), payload: ByteArray = ByteArray(0),
    ): ByteArray {
        require(options.size % 4 == 0)
        val dataOffset = 5 + options.size / 4
        return Buf().u16(sport).u16(dport).u32(seq).u32(ack).u16((dataOffset shl 12) or flags).u16(window).u16(0).u16(0)
            .bytes(options).bytes(payload).build()
    }

    fun udp(sport: Int, dport: Int, payload: ByteArray): ByteArray =
        Buf().u16(sport).u16(dport).u16(8 + payload.size).u16(0).bytes(payload).build()

    /** DNS name in wire format. */
    fun dnsName(name: String): ByteArray {
        val b = Buf()
        name.split('.').filter { it.isNotEmpty() }.forEach { b.u8(it.length).ascii(it) }
        return b.u8(0).build()
    }

    fun dnsQuery(id: Int, name: String, type: Int = 1): ByteArray =
        Buf().u16(id).u16(0x0100).u16(1).u16(0).u16(0).u16(0).bytes(dnsName(name)).u16(type).u16(1).build()

    /** Response with one A answer whose name is a compression pointer to the question (offset 12). */
    fun dnsResponseA(id: Int, name: String, address: String, ttl: Long = 300): ByteArray =
        Buf().u16(id).u16(0x8180).u16(1).u16(1).u16(0).u16(0)
            .bytes(dnsName(name)).u16(1).u16(1)
            .u16(0xC00C).u16(1).u16(1).u32(ttl).u16(4).bytes(ip4(address)).build()

    fun icmpv6NeighborSolicitation(target: String, sourceMac: String = MAC_A): ByteArray =
        Buf().u8(135).u8(0).u16(0).u32(0).bytes(ip6(target)).u8(1).u8(1).bytes(mac(sourceMac)).build()

    fun icmpEcho(request: Boolean, id: Int, seq: Int): ByteArray =
        Buf().u8(if (request) 8 else 0).u8(0).u16(0).u16(id).u16(seq).ascii("abcdefgh").build()

    fun tlsClientHello(sni: String?): ByteArray {
        val ext = Buf()
        if (sni != null) {
            val nameBytes = sni.toByteArray()
            ext.u16(0).u16(nameBytes.size + 5).u16(nameBytes.size + 3).u8(0).u16(nameBytes.size).bytes(nameBytes)
        }
        ext.u16(43).u16(3).u8(2).u16(0x0304) // supported_versions: TLS 1.3
        val extBytes = ext.build()
        val body = Buf().u16(0x0303).zeros(32).u8(0) // version, random, no session id
            .u16(4).u16(0x1301).u16(0xC02F) // two cipher suites
            .u8(1).u8(0) // null compression
            .u16(extBytes.size).bytes(extBytes).build()
        val handshake = Buf().u8(1).u8(0).u16(body.size).bytes(body).build()
        return Buf().u8(0x16).u16(0x0301).u16(handshake.size).bytes(handshake).build()
    }

    fun tlsAppData(len: Int): ByteArray = Buf().u8(0x17).u16(0x0303).u16(len).zeros(len).build()
}

// ---- captures ---------------------------------------------------------------------------------

/** Classic pcap writer. */
class PcapBuilder(private val little: Boolean = false, private val nanos: Boolean = false, linkType: Int = 1, snapLen: Int = 262144) {
    private val buf = Buf(little)

    init {
        buf.u32(if (nanos) 0xa1b23c4dL else 0xa1b2c3d4L).u16(2).u16(4).u32(0).u32(0).u32(snapLen).u32(linkType)
    }

    fun packet(data: ByteArray, tsSec: Long = 1_700_000_000L, tsFrac: Long = 0, origLen: Int = data.size, inclLen: Int = data.size) = apply {
        buf.u32(tsSec).u32(tsFrac).u32(inclLen).u32(origLen).bytes(data)
    }

    fun raw(bytes: ByteArray) = apply { buf.bytes(bytes) }
    fun build(): ByteArray = buf.build()
}

/** pcapng writer; every block is padded and carries its trailing length. */
class PcapngBuilder(private val little: Boolean = false) {
    private val out = Buf(little)

    fun block(type: Long, body: ByteArray) = apply {
        val padded = Buf().bytes(body).pad4().build()
        val total = 12 + padded.size
        out.u32(type).u32(total).bytes(padded).u32(total)
    }

    fun shb() = block(0x0a0d0d0aL, Buf(little).u32(0x1a2b3c4dL).u16(1).u16(0).u64(-1L).build())

    fun idb(linkType: Int = 1, snapLen: Int = 262144, name: String? = null, tsresol: Int? = null, tsOffset: Long? = null) = apply {
        val b = Buf(little).u16(linkType).u16(0).u32(snapLen)
        if (name != null) option(b, 2, name.toByteArray())
        if (tsresol != null) option(b, 9, byteArrayOf(tsresol.toByte()))
        if (tsOffset != null) option(b, 14, Buf(little).u64(tsOffset).build())
        if (name != null || tsresol != null || tsOffset != null) b.u16(0).u16(0)
        block(1, b.build())
    }

    private fun option(b: Buf, code: Int, value: ByteArray) {
        b.u16(code).u16(value.size).bytes(value).pad4()
    }

    fun epb(data: ByteArray, interfaceId: Int = 0, timestamp: Long = 1_700_000_000_000_000L, origLen: Int = data.size) = apply {
        val b = Buf(little).u32(interfaceId).u32(timestamp ushr 32).u32(timestamp and 0xFFFFFFFFL).u32(data.size).u32(origLen).bytes(data)
        block(6, b.build())
    }

    fun spb(data: ByteArray, origLen: Int = data.size) = block(3, Buf(little).u32(origLen).bytes(data).build())

    fun raw(bytes: ByteArray) = apply { out.bytes(bytes) }
    fun build(): ByteArray = out.build()
}
