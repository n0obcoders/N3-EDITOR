package com.nitinshukla.notebook.core.pcap

/**
 * Indexes classic pcap and pcapng captures.
 *
 * The source is read in 1 MiB chunks; only the record headers and the first
 * [SUMMARY_WINDOW] bytes of every packet are looked at, straight from the chunk buffer, so
 * memory use is dominated by the [PacketRecord] list.
 */
object PcapParser {
    /** Captured lengths above this are treated as corruption. */
    const val MAX_PACKET_BYTES = 256 * 1024

    /** How many leading bytes of a packet the row summary decodes. */
    internal const val SUMMARY_WINDOW = 512

    private const val CHUNK_SIZE = 1 shl 20
    private const val PROGRESS_EVERY = 2048

    private const val PCAP_MAGIC = 0xa1b2c3d4L
    private const val PCAP_MAGIC_SWAPPED = 0xd4c3b2a1L
    private const val PCAP_NANO_MAGIC = 0xa1b23c4dL
    private const val PCAP_NANO_MAGIC_SWAPPED = 0x4d3cb2a1L
    private const val PCAPNG_SHB = 0x0a0d0d0aL
    private const val PCAPNG_BOM = 0x1a2b3c4dL
    private const val PCAPNG_BOM_SWAPPED = 0x4d3c2b1aL

    private const val BLOCK_IDB = 1L
    private const val BLOCK_PB = 2L
    private const val BLOCK_SPB = 3L
    private const val BLOCK_EPB = 6L

    /** Detects the capture format from the first bytes, or null when they are not a capture. */
    fun detectFormat(header: ByteArray): CaptureFormat? {
        if (header.size < 4) return null
        return when (be32(header, 0)) {
            PCAP_MAGIC, PCAP_MAGIC_SWAPPED -> CaptureFormat.PCAP
            PCAP_NANO_MAGIC, PCAP_NANO_MAGIC_SWAPPED -> CaptureFormat.PCAP_NANOSECOND
            PCAPNG_SHB -> {
                if (header.size < 12) return null
                when (be32(header, 8)) {
                    PCAPNG_BOM, PCAPNG_BOM_SWAPPED -> CaptureFormat.PCAPNG
                    else -> null
                }
            }
            else -> null
        }
    }

    /**
     * Detects the format from the magic bytes and indexes the whole capture.
     *
     * @throws PcapFormatException when the source is not a capture. Once the magic is recognised
     * this never throws: a truncated or corrupt tail stops indexing and adds a warning.
     */
    fun index(
        source: ByteSource,
        maxPackets: Int = 250_000,
        onProgress: ((packets: Int, bytesRead: Long) -> Unit)? = null,
    ): CaptureIndex {
        val head = source.read(0, 32)
        val format = detectFormat(head)
            ?: throw PcapFormatException(
                if (head.size < 4) "File is too short to be a capture"
                else "Not a pcap or pcapng capture (magic ${Addr.hex(head, 0, 4)})",
            )
        val reader = ChunkReader(source, CHUNK_SIZE)
        val acc = Accumulator(format, source.size, maxPackets, onProgress)
        try {
            when (format) {
                CaptureFormat.PCAP -> indexPcap(reader, acc, head, nanos = false)
                CaptureFormat.PCAP_NANOSECOND -> indexPcap(reader, acc, head, nanos = true)
                CaptureFormat.PCAPNG -> indexPcapng(reader, acc)
            }
        } catch (e: Exception) {
            acc.warn("Indexing stopped at offset ${acc.lastOffset}: ${e.message ?: e.javaClass.simpleName}")
        }
        onProgress?.invoke(acc.packets.size, acc.lastOffset)
        return acc.build()
    }

    // ---- classic pcap -----------------------------------------------------------------------

    private fun indexPcap(reader: ChunkReader, acc: Accumulator, head: ByteArray, nanos: Boolean) {
        reader.littleEndian = (head[0].toInt() and 0xFF) == 0xd4 || (head[0].toInt() and 0xFF) == 0x4d
        if (!reader.ensure(0, 24)) {
            acc.warn("File is shorter than the 24-byte pcap header")
            return
        }
        val snapLen = reader.u32(16).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val linkType = (reader.u32(20) and 0xFFFF).toInt()
        acc.interfaces.add(
            InterfaceInfo(
                id = 0,
                linkType = linkType,
                linkTypeName = LinkTypes.name(linkType),
                snapLen = snapLen,
                name = null,
                timestampResolution = if (nanos) 1_000_000_000L else 1_000_000L,
            ),
        )
        var offset = 24L
        val size = reader.size
        while (offset < size) {
            acc.lastOffset = offset
            val remaining = size - offset
            if (remaining < 16) {
                acc.warn("Capture ends mid-packet header at offset $offset ($remaining trailing bytes)")
                return
            }
            reader.ensure(offset, 16)
            val tsSec = reader.u32(offset)
            val frac = reader.u32(offset + 4)
            val incl = reader.u32(offset + 8)
            val orig = reader.u32(offset + 12)
            if (incl > MAX_PACKET_BYTES) {
                acc.warn("Corrupt packet header at offset $offset: captured length $incl exceeds $MAX_PACKET_BYTES bytes")
                return
            }
            val dataOffset = offset + 16
            if (dataOffset + incl > size) {
                acc.warn("Capture ends mid-packet at offset $offset (packet ${acc.packets.size + 1} declares $incl bytes, ${size - dataOffset} available)")
                return
            }
            val micros = tsSec * 1_000_000L + if (nanos) frac / 1000 else frac
            if (!acc.add(reader, dataOffset, incl.toInt(), orig.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), micros, 0, linkType)) return
            offset = dataOffset + incl
        }
        acc.lastOffset = offset
    }

    // ---- pcapng -----------------------------------------------------------------------------

    private class SectionInterface(
        val globalId: Int,
        val linkType: Int,
        val snapLen: Int,
        val ticksPerSecond: Long,
        val offsetMicros: Long,
    )

    private fun indexPcapng(reader: ChunkReader, acc: Accumulator) {
        val size = reader.size
        val section = ArrayList<SectionInterface>()
        var warnedUnknownInterface = false
        var offset = 0L
        while (offset < size) {
            acc.lastOffset = offset
            val remaining = size - offset
            if (remaining < 12) {
                acc.warn("Capture ends mid-block at offset $offset ($remaining trailing bytes)")
                return
            }
            reader.ensure(offset, 12)
            val type = reader.u32(offset) // the SHB type is a palindrome, so endianness does not matter yet
            if (type == PCAPNG_SHB) {
                when (be32(reader.buf, reader.bufIndex(offset + 8))) {
                    PCAPNG_BOM -> reader.littleEndian = false
                    PCAPNG_BOM_SWAPPED -> reader.littleEndian = true
                    else -> {
                        acc.warn("Bad byte-order magic in section header at offset $offset")
                        return
                    }
                }
                section.clear()
            }
            val length = reader.u32(offset + 4)
            if (length < 12 || length % 4 != 0L) {
                acc.warn("Invalid block length $length at offset $offset")
                return
            }
            if (offset + length > size) {
                acc.warn("Capture ends mid-block at offset $offset (block declares $length bytes, $remaining available)")
                return
            }
            val body = offset + 8
            val bodyEnd = offset + length - 4
            when (type) {
                BLOCK_IDB -> {
                    val parseEnd = minOf(bodyEnd, offset + CHUNK_SIZE)
                    reader.ensure(offset, (parseEnd - offset).toInt())
                    if (bodyEnd - body < 8) {
                        acc.warn("Interface description block at offset $offset is too short")
                        return
                    }
                    val linkType = reader.u16(body)
                    val snapLen = reader.u32(body + 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    var name: String? = null
                    var ticks = 1_000_000L
                    var tsOffsetSeconds = 0L
                    var o = body + 8
                    while (o + 4 <= parseEnd) {
                        val code = reader.u16(o)
                        val len = reader.u16(o + 2)
                        if (code == 0) break
                        if (o + 4 + len > parseEnd) break
                        when (code) {
                            2 -> name = String(reader.buf, reader.bufIndex(o + 4), len, Charsets.UTF_8).trimEnd('\u0000')
                            9 -> if (len >= 1) ticks = ticksPerSecond(reader.u8(o + 4))
                            14 -> if (len == 8) tsOffsetSeconds = reader.u64(o + 4)
                        }
                        o += 4 + ((len + 3) / 4) * 4
                    }
                    val globalId = acc.interfaces.size
                    acc.interfaces.add(InterfaceInfo(globalId, linkType, LinkTypes.name(linkType), snapLen, name, ticks))
                    section.add(SectionInterface(globalId, linkType, snapLen, ticks, tsOffsetSeconds * 1_000_000L))
                }
                BLOCK_EPB, BLOCK_PB -> {
                    if (length < 32) {
                        acc.warn("Packet block at offset $offset is too short ($length bytes)")
                        return
                    }
                    reader.ensure(offset, 28)
                    val ifId = if (type == BLOCK_EPB) reader.u32(body).toInt() else reader.u16(body)
                    val ts = (reader.u32(body + 4) shl 32) or reader.u32(body + 8)
                    val cap = reader.u32(body + 12)
                    val orig = reader.u32(body + 16)
                    val dataOffset = offset + 28
                    if (cap > MAX_PACKET_BYTES || dataOffset + cap > bodyEnd) {
                        acc.warn("Corrupt packet block at offset $offset: captured length $cap does not fit the block")
                        return
                    }
                    var iface = section.getOrNull(ifId)
                    if (iface == null) {
                        if (!warnedUnknownInterface) {
                            acc.warn("Packet ${acc.packets.size + 1} at offset $offset references undefined interface $ifId")
                            warnedUnknownInterface = true
                        }
                        iface = section.firstOrNull()
                    }
                    val micros = if (iface == null) 0L else toMicros(ts, iface)
                    val linkType = iface?.linkType ?: LinkTypes.ETHERNET
                    val globalIf = iface?.globalId ?: ifId
                    if (!acc.add(reader, dataOffset, cap.toInt(), orig.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), micros, globalIf, linkType)) return
                }
                BLOCK_SPB -> {
                    if (length < 16) {
                        acc.warn("Simple packet block at offset $offset is too short ($length bytes)")
                        return
                    }
                    reader.ensure(offset, 12)
                    val orig = reader.u32(body)
                    val iface = section.firstOrNull()
                    var cap = minOf(orig, bodyEnd - (offset + 12))
                    if (iface != null && iface.snapLen > 0) cap = minOf(cap, iface.snapLen.toLong())
                    if (cap > MAX_PACKET_BYTES) {
                        acc.warn("Corrupt simple packet block at offset $offset: length $cap")
                        return
                    }
                    if (iface == null && !warnedUnknownInterface) {
                        acc.warn("Simple packet block at offset $offset appears before any interface description")
                        warnedUnknownInterface = true
                    }
                    val linkType = iface?.linkType ?: LinkTypes.ETHERNET
                    if (!acc.add(reader, offset + 12, cap.toInt(), orig.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), 0L, iface?.globalId ?: 0, linkType)) return
                }
                else -> Unit // SHB body, NRB, ISB, DSB, custom and unknown blocks are skipped by length
            }
            // The trailing length must repeat the leading one; a mismatch means the stream is unreliable.
            reader.ensure(offset + length - 4, 4)
            val trailer = reader.u32(offset + length - 4)
            if (trailer != length) {
                acc.warn("Block at offset $offset has mismatched trailing length ($trailer vs $length)")
                return
            }
            offset += length
        }
        acc.lastOffset = offset
    }

    private fun ticksPerSecond(tsresol: Int): Long {
        val exponent = tsresol and 0x7F
        return if (tsresol and 0x80 != 0) {
            1L shl exponent.coerceAtMost(62)
        } else {
            var t = 1L
            repeat(exponent.coerceAtMost(18)) { t *= 10 }
            t
        }
    }

    private fun toMicros(ts: Long, iface: SectionInterface): Long {
        val tps = iface.ticksPerSecond
        val micros = when {
            tps == 1_000_000L -> ts
            tps > 1_000_000L && tps % 1_000_000L == 0L -> ts / (tps / 1_000_000L)
            tps < 1_000_000L && 1_000_000L % tps == 0L -> ts * (1_000_000L / tps)
            else -> {
                val seconds = ts / tps
                val rem = ts % tps
                val fraction = if (tps <= Long.MAX_VALUE / 1_000_000L) rem * 1_000_000L / tps else rem / (tps / 1_000_000L)
                seconds * 1_000_000L + fraction
            }
        }
        return micros + iface.offsetMicros
    }

    private fun be32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    // ---- helpers ----------------------------------------------------------------------------

    /** Sliding window over a [ByteSource]; integer accessors take absolute offsets. */
    private class ChunkReader(private val source: ByteSource, private val chunkSize: Int) {
        val size: Long = source.size
        var buf = ByteArray(0)
            private set
        private var bufStart = 0L
        var littleEndian = false

        /** Makes `[offset, offset + length)` available in [buf]; false when the source ends first. */
        fun ensure(offset: Long, length: Int): Boolean {
            require(length <= chunkSize) { "window $length exceeds chunk size" }
            if (offset >= bufStart && offset + length <= bufStart + buf.size) return true
            if (offset + length > size) return false
            buf = source.read(offset, chunkSize)
            bufStart = offset
            return offset + length <= bufStart + buf.size
        }

        fun bufIndex(offset: Long): Int = (offset - bufStart).toInt()
        fun u8(offset: Long): Int = buf[bufIndex(offset)].toInt() and 0xFF

        fun u16(offset: Long): Int {
            val a = u8(offset)
            val b = u8(offset + 1)
            return if (littleEndian) (b shl 8) or a else (a shl 8) or b
        }

        fun u32(offset: Long): Long {
            val a = u16(offset).toLong()
            val b = u16(offset + 2).toLong()
            return if (littleEndian) (b shl 16) or a else (a shl 16) or b
        }

        fun u64(offset: Long): Long {
            val a = u32(offset)
            val b = u32(offset + 4)
            return if (littleEndian) (b shl 32) or a else (a shl 32) or b
        }
    }

    /** Collects packets, interfaces, warnings and the aggregates for [CaptureSummary]. */
    private class Accumulator(
        private val format: CaptureFormat,
        private val fileSize: Long,
        private val maxPackets: Int,
        private val onProgress: ((Int, Long) -> Unit)?,
    ) {
        val packets = ArrayList<PacketRecord>()
        val interfaces = ArrayList<InterfaceInfo>()
        private val warnings = ArrayList<String>()
        private val protocolCounts = HashMap<String, Int>()
        private val talkers = HashMap<String, Long>()
        private val conversations = HashMap<String, Long>()
        private val row = PacketDecoder.RowSummary()
        /** Captures repeat a handful of hosts; share one String per address across records. */
        private val hosts = HashMap<String, String>()
        private fun intern(host: String): String = if (host.isEmpty()) "" else hosts.getOrPut(host) { host }
        private var totalCaptured = 0L
        private var totalOriginal = 0L
        private var first = Long.MAX_VALUE
        private var last = Long.MIN_VALUE
        private var truncated = false
        var lastOffset = 0L

        fun warn(message: String) {
            warnings.add(message)
        }

        /** Adds one packet; returns false once [maxPackets] has been reached. */
        fun add(reader: ChunkReader, dataOffset: Long, capLen: Int, origLen: Int, micros: Long, interfaceId: Int, linkType: Int): Boolean {
            if (packets.size >= maxPackets) {
                truncated = true
                return false
            }
            val window = minOf(capLen, SUMMARY_WINDOW)
            val available = if (reader.ensure(dataOffset, window)) window else 0
            PacketDecoder.summarize(reader.buf, reader.bufIndex(dataOffset), available, capLen, linkType, row)
            packets.add(
                PacketRecord(
                    index = packets.size + 1,
                    timestampMicros = micros,
                    capturedLength = capLen,
                    originalLength = origLen,
                    dataOffset = dataOffset,
                    interfaceId = interfaceId,
                    linkType = linkType,
                    protocol = row.protocol,
                    source = row.source,
                    destination = row.destination,
                    info = row.info,
                    transport = row.transport,
                    srcHost = intern(row.srcHost),
                    dstHost = intern(row.dstHost),
                    srcPort = row.srcPort,
                    dstPort = row.dstPort,
                ),
            )
            totalCaptured += capLen
            totalOriginal += origLen
            if (micros < first) first = micros
            if (micros > last) last = micros
            protocolCounts.merge(row.protocol, 1, Int::plus)
            val bytes = origLen.toLong()
            val a = row.srcHost
            val b = row.dstHost
            if (a.isNotEmpty()) talkers.merge(a, bytes, Long::plus)
            if (b.isNotEmpty() && b != a) talkers.merge(b, bytes, Long::plus)
            if (a.isNotEmpty() && b.isNotEmpty()) {
                val key = if (a <= b) "$a ⇄ $b" else "$b ⇄ $a"
                conversations.merge(key, bytes, Long::plus)
            }
            if (packets.size % PROGRESS_EVERY == 0) onProgress?.invoke(packets.size, dataOffset + capLen)
            return true
        }

        fun build(): CaptureIndex {
            val sortedProtocols = LinkedHashMap<String, Int>()
            protocolCounts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .forEach { sortedProtocols[it.key] = it.value }
            val hasPackets = packets.isNotEmpty()
            val summary = CaptureSummary(
                format = format,
                fileSize = fileSize,
                packetCount = packets.size,
                totalCapturedBytes = totalCaptured,
                totalOriginalBytes = totalOriginal,
                firstTimestampMicros = if (hasPackets) first else null,
                lastTimestampMicros = if (hasPackets) last else null,
                durationMicros = if (hasPackets) last - first else 0L,
                interfaces = interfaces.toList(),
                protocolCounts = sortedProtocols,
                topTalkers = top(talkers),
                topConversations = top(conversations),
                truncated = truncated,
                warnings = warnings.toList(),
            )
            return CaptureIndex(summary, packets)
        }

        private fun top(map: Map<String, Long>): List<Pair<String, Long>> =
            map.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .take(10).map { it.key to it.value }
    }
}
