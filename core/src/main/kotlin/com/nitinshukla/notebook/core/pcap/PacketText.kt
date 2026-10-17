package com.nitinshukla.notebook.core.pcap

/**
 * Readable text for packets: the application payload as text, and a full plain-text report of
 * every packet (summary line, each decoded layer with its fields, the payload as text and a hex
 * dump) that can be exported and read in any editor.
 */
object PacketText {

    /**
     * The payload bytes as text. Valid UTF-8 is decoded as such; otherwise printable ASCII is
     * kept, line breaks and tabs are kept, and every other byte becomes '.'.
     */
    fun payloadText(bytes: ByteArray, offset: Int, length: Int): String {
        val start = offset.coerceIn(0, bytes.size)
        val end = (start + length.coerceAtLeast(0)).coerceAtMost(bytes.size)
        if (end <= start) return ""
        val slice = bytes.copyOfRange(start, end)
        decodeUtf8Text(slice)?.let { return it }
        val sb = StringBuilder(slice.size)
        var i = 0
        while (i < slice.size) {
            val b = slice[i].toInt() and 0xFF
            when {
                b == 0x0D && i + 1 < slice.size && slice[i + 1].toInt() == 0x0A -> { sb.append('\n'); i++ }
                b == 0x0A || b == 0x09 -> sb.append(b.toChar())
                b in 0x20..0x7E -> sb.append(b.toChar())
                else -> sb.append('.')
            }
            i++
        }
        return sb.toString()
    }

    /** Share of bytes that are printable ASCII or whitespace, 0..1. */
    fun printableRatio(bytes: ByteArray, offset: Int, length: Int): Double {
        val start = offset.coerceIn(0, bytes.size)
        val end = (start + length.coerceAtLeast(0)).coerceAtMost(bytes.size)
        if (end <= start) return 0.0
        var printable = 0
        for (i in start until end) {
            val b = bytes[i].toInt() and 0xFF
            if (b in 0x20..0x7E || b == 0x0A || b == 0x0D || b == 0x09) printable++
        }
        return printable.toDouble() / (end - start)
    }

    private fun decodeUtf8Text(bytes: ByteArray): String? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            return null
        }
        // Binary that happens to be valid UTF-8 (zeros, control bytes) is not text.
        val control = text.count { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
        if (control > text.length / 20) return null
        return text.replace("\r\n", "\n")
    }

    /** The full readable block for one packet. */
    fun describe(record: PacketRecord, decoded: DecodedPacket, bytes: ByteArray, firstTimestampMicros: Long, out: Appendable) {
        val rule = "=".repeat(78)
        out.append(rule).append('\n')
        out.append("Packet ").append(record.index.toString()).append("  ")
            .append(decoded.protocol).append("  ")
            .append(PcapTime.formatUtc(record.timestampMicros))
            .append("  (+").append(PcapTime.formatRelative(record.timestampMicros, firstTimestampMicros)).append(" s)\n")
        if (decoded.source.isNotEmpty() || decoded.destination.isNotEmpty()) {
            out.append(decoded.source).append(" -> ").append(decoded.destination).append('\n')
        }
        out.append(decoded.info).append('\n')
        out.append("Captured ").append(record.capturedLength.toString()).append(" of ")
            .append(record.originalLength.toString()).append(" bytes\n")
        for (layer in decoded.layers) {
            out.append("\n[").append(layer.name).append("]  ").append(layer.summary).append('\n')
            for (f in layer.fields) {
                out.append("    ").append(f.name).append(": ").append(f.value).append('\n')
            }
        }
        if (decoded.appPayloadLength > 0) {
            out.append("\n[Payload as text, ").append(decoded.appPayloadLength.toString()).append(" bytes]\n")
            val text = payloadText(bytes, decoded.appPayloadOffset, decoded.appPayloadLength)
            text.lineSequence().forEach { out.append("    ").append(it).append('\n') }
        }
        out.append("\n[Hex dump]\n")
        out.append(HexDump.format(bytes)).append("\n\n")
    }

    /**
     * Writes a report of [packets] read from [source] to [out]: a header, then every packet in
     * full. [onProgress] gets the number of packets written so far.
     */
    fun export(
        source: ByteSource,
        packets: List<PacketRecord>,
        summary: CaptureSummary,
        fileName: String,
        filterDescription: String?,
        out: Appendable,
        onProgress: (Int) -> Unit = {},
    ) {
        val first = summary.firstTimestampMicros ?: 0L
        out.append("Capture: ").append(fileName).append('\n')
        out.append("Format: ").append(summary.format.name.replace('_', ' ')).append('\n')
        out.append("Packets: ").append(packets.size.toString()).append(" of ").append(summary.packetCount.toString()).append('\n')
        if (!filterDescription.isNullOrBlank()) out.append("Filter: ").append(filterDescription).append('\n')
        summary.firstTimestampMicros?.let { out.append("First packet: ").append(PcapTime.formatUtc(it)).append('\n') }
        summary.lastTimestampMicros?.let { out.append("Last packet: ").append(PcapTime.formatUtc(it)).append('\n') }
        out.append('\n')
        packets.forEachIndexed { i, record ->
            val bytes = source.read(record.dataOffset, record.capturedLength)
            val decoded = PacketDecoder.decode(bytes, record.linkType)
            describe(record, decoded, bytes, first, out)
            if (i % 200 == 0) onProgress(i)
        }
        onProgress(packets.size)
    }

    /**
     * The payload of every packet in the same TCP/UDP conversation as [record] (both directions),
     * in capture order, each chunk headed by its direction. Used by "Follow stream".
     */
    fun followStream(source: ByteSource, packets: List<PacketRecord>, record: PacketRecord, maxBytes: Int = 2_000_000): String {
        if (record.srcPort < 0 || record.transport.isEmpty()) return ""
        val a = record.srcHost to record.srcPort
        val b = record.dstHost to record.dstPort
        val sb = StringBuilder()
        var total = 0
        for (p in packets) {
            if (p.transport != record.transport) continue
            val from = p.srcHost to p.srcPort
            val to = p.dstHost to p.dstPort
            val forward = from == a && to == b
            val backward = from == b && to == a
            if (!forward && !backward) continue
            val bytes = source.read(p.dataOffset, p.capturedLength)
            val decoded = PacketDecoder.decode(bytes, p.linkType)
            if (decoded.appPayloadLength <= 0) continue
            sb.append(if (forward) ">>> " else "<<< ")
                .append(p.source).append(" -> ").append(p.destination)
                .append("  (packet ").append(p.index.toString()).append(", ").append(decoded.appPayloadLength.toString()).append(" bytes)\n")
            sb.append(payloadText(bytes, decoded.appPayloadOffset, decoded.appPayloadLength)).append("\n\n")
            total += decoded.appPayloadLength
            if (total >= maxBytes) {
                sb.append("… stream cut at ").append(maxBytes.toString()).append(" bytes\n")
                break
            }
        }
        return sb.toString()
    }
}
