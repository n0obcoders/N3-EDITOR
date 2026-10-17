package com.nitinshukla.notebook.core.pcap

/** Random access to capture bytes. The app wraps a RandomAccessFile; tests wrap a ByteArray. */
interface ByteSource {
    val size: Long

    /** Reads up to [length] bytes starting at [offset]; may return fewer bytes at EOF. */
    fun read(offset: Long, length: Int): ByteArray
}

/** [ByteSource] over an in-memory array. */
class ByteArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset >= bytes.size || length <= 0) return ByteArray(0)
        val start = offset.toInt()
        val end = minOf(bytes.size.toLong(), offset + length).toInt()
        return bytes.copyOfRange(start, end)
    }
}

/** Container format of a capture file. */
enum class CaptureFormat { PCAP, PCAP_NANOSECOND, PCAPNG }

/** One capture interface (pcapng IDB, or the single implicit interface of a classic pcap). */
data class InterfaceInfo(
    val id: Int,
    val linkType: Int,
    val linkTypeName: String,
    val snapLen: Int,
    val name: String?,
    /** Timestamp ticks per second, e.g. 1_000_000 for microseconds. */
    val timestampResolution: Long,
)

/** One packet in the index. Kept small: no strings beyond what the list row shows. */
data class PacketRecord(
    /** 1-based display number. */
    val index: Int,
    /** Absolute UTC epoch microseconds (0 when unknown). */
    val timestampMicros: Long,
    val capturedLength: Int,
    val originalLength: Int,
    /** Offset of the packet bytes within the source. */
    val dataOffset: Long,
    val interfaceId: Int,
    val linkType: Int,
    /** Highest decoded protocol, e.g. "TCP", "DNS", "ARP", "Unknown". */
    val protocol: String,
    /** "ip:port", a MAC, or "" when none. */
    val source: String,
    val destination: String,
    /** Short one-line summary. */
    val info: String,
    /** "TCP", "UDP", "ICMP", "ICMPv6" or "" — what carries [protocol] (DNS is carried by UDP). */
    val transport: String = "",
    /** IP addresses without ports, "" for non-IP packets. */
    val srcHost: String = "",
    val dstHost: String = "",
    /** Transport ports, -1 when the packet has none. */
    val srcPort: Int = -1,
    val dstPort: Int = -1,
)

/** Whole-capture aggregates for the summary screen. */
data class CaptureSummary(
    val format: CaptureFormat,
    val fileSize: Long,
    val packetCount: Int,
    val totalCapturedBytes: Long,
    val totalOriginalBytes: Long,
    /** Null when the capture has no packets. */
    val firstTimestampMicros: Long?,
    val lastTimestampMicros: Long?,
    val durationMicros: Long,
    val interfaces: List<InterfaceInfo>,
    /** Top-level protocol -> packet count, sorted descending by count. */
    val protocolCounts: Map<String, Int>,
    /** Address (ip or mac, no port) -> bytes, top 10 descending. */
    val topTalkers: List<Pair<String, Long>>,
    /** "a ⇄ b" -> bytes, top 10 descending. */
    val topConversations: List<Pair<String, Long>>,
    /** True when `maxPackets` was hit before the end of the capture. */
    val truncated: Boolean,
    /** Non-fatal problems, e.g. "Capture ends mid-packet at offset N". */
    val warnings: List<String>,
)

/** Result of [PcapParser.index]. */
data class CaptureIndex(val summary: CaptureSummary, val packets: List<PacketRecord>)

/** Thrown when the input is not a pcap/pcapng capture at all. */
class PcapFormatException(message: String) : Exception(message)

/**
 * One decoded header field. [offset]/[length] locate it within the packet bytes so the hex
 * dump can highlight it; [offset] is -1 when the field has no byte range.
 */
data class Field(val name: String, val value: String, val offset: Int = -1, val length: Int = 0)

/** One protocol layer of a decoded packet, covering `[offset, offset + length)` of the packet bytes. */
data class Layer(val name: String, val summary: String, val fields: List<Field>, val offset: Int, val length: Int)

/** Full decode of one packet for the detail view. */
data class DecodedPacket(
    val layers: List<Layer>,
    val protocol: String,
    val source: String,
    val destination: String,
    val info: String,
    /** Start of the undecoded payload (or `bytes.size` when everything was decoded). */
    val payloadOffset: Int,
    val payloadLength: Int,
    val transport: String = "",
    /** Application data above TCP/UDP (or the undecoded payload), for reading packet contents. */
    val appPayloadOffset: Int = payloadOffset,
    val appPayloadLength: Int = payloadLength,
    val srcHost: String = "",
    val dstHost: String = "",
    val srcPort: Int = -1,
    val dstPort: Int = -1,
)

/** One row of a hex dump. */
data class HexLine(
    val offset: Int,
    /** "00 11 22 ..." padded to the full column count, with an extra space after 8 bytes. */
    val hex: String,
    /** One char per column, '.' for non-printable bytes. */
    val ascii: String,
)
