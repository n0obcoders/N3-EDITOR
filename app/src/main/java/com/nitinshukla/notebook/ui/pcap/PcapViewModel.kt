package com.nitinshukla.notebook.ui.pcap

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nitinshukla.notebook.core.pcap.ByteSource
import com.nitinshukla.notebook.core.pcap.CaptureIndex
import com.nitinshukla.notebook.core.pcap.DecodedPacket
import com.nitinshukla.notebook.core.pcap.HexDump
import com.nitinshukla.notebook.core.pcap.HexLine
import com.nitinshukla.notebook.core.pcap.PacketDecoder
import com.nitinshukla.notebook.core.pcap.PacketFilter
import com.nitinshukla.notebook.core.pcap.PacketText
import com.nitinshukla.notebook.core.pcap.TransportFilter
import com.nitinshukla.notebook.core.pcap.PacketRecord
import com.nitinshukla.notebook.core.pcap.PcapFormatException
import com.nitinshukla.notebook.core.pcap.PcapParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject

/** [ByteSource] over a file in the app cache; one read per call, no buffering of the whole file. */
private class FileByteSource(private val file: File) : ByteSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    @Synchronized
    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset >= size || length <= 0) return ByteArray(0)
        val n = minOf(length.toLong(), size - offset).toInt()
        val out = ByteArray(n)
        raf.seek(offset)
        raf.readFully(out)
        return out
    }

    fun close() = runCatching { raf.close() }
}

/** Everything the detail sheet needs for one packet. */
data class PacketDetail(
    val record: PacketRecord,
    val decoded: DecodedPacket,
    val hex: List<HexLine>,
    val bytes: ByteArray,
    /** The application payload as readable text ("" when there is none). */
    val payloadText: String = "",
    /** True when most payload bytes are not printable, so the text is mostly dots. */
    val payloadBinary: Boolean = false,
)

@HiltViewModel
class PcapViewModel @Inject constructor(app: Application) : AndroidViewModel(app) {

    var fileName by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var progressPackets by mutableIntStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var index by mutableStateOf<CaptureIndex?>(null)
        private set
    /** The one combined filter: IP, TCP/UDP and port from the builder plus the typed expression. */
    var filter by mutableStateOf(PacketFilter())
        private set
    var filtered by mutableStateOf<List<PacketRecord>>(emptyList())
        private set
    var detail by mutableStateOf<PacketDetail?>(null)
        private set
    var relativeTime by mutableStateOf(true)
        private set
    /** Follow-stream text for the packet in [detail], or null when not shown. */
    var stream by mutableStateOf<String?>(null)
        private set
    var streamLoading by mutableStateOf(false)
        private set
    /** Packets written so far while exporting, or -1 when no export runs. */
    var exportProgress by mutableIntStateOf(-1)
        private set
    var exportTotal by mutableIntStateOf(0)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    private var source: FileByteSource? = null
    private var cacheFile: File? = null
    private var filterJob: Job? = null

    fun open(uri: Uri) {
        close()
        loading = true
        progressPackets = 0
        viewModelScope.launch {
            try {
                val (name, file) = withContext(Dispatchers.IO) { copyToCache(uri) }
                fileName = name
                cacheFile = file
                val src = FileByteSource(file)
                source = src
                var lastUpdate = 0L
                val result = withContext(Dispatchers.Default) {
                    PcapParser.index(src) { packets, _ ->
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 100) { lastUpdate = now; progressPackets = packets }
                    }
                }
                index = result
                filtered = result.packets
                filter = PacketFilter()
            } catch (e: PcapFormatException) {
                error = e.message
                close()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                close()
            } finally {
                loading = false
            }
        }
    }

    fun close() {
        filterJob?.cancel()
        source?.close()
        source = null
        cacheFile?.delete()
        cacheFile = null
        index = null
        filtered = emptyList()
        detail = null
        stream = null
        fileName = null
        filter = PacketFilter()
    }

    fun consumeError() { error = null }

    fun consumeMessage() { message = null }

    fun toggleRelativeTime() { relativeTime = !relativeTime }

    fun updateFilter(transform: (PacketFilter) -> PacketFilter) {
        filter = transform(filter)
        applyFilter()
    }

    fun clearFilter() {
        filter = PacketFilter()
        applyFilter()
    }

    private fun applyFilter() {
        val all = index?.packets ?: return
        val spec = filter
        filterJob?.cancel()
        filterJob = viewModelScope.launch(Dispatchers.Default) {
            delay(120)
            val result = if (spec.isEmpty) all else {
                val predicate = spec.compile()
                all.filter(predicate)
            }
            ensureActive()
            withContext(Dispatchers.Main) { filtered = result }
        }
    }

    /** Follow the TCP/UDP conversation of the packet shown in the detail view. */
    fun followStream() {
        val record = detail?.record ?: return
        val src = source ?: return
        val all = index?.packets ?: return
        streamLoading = true
        viewModelScope.launch {
            stream = withContext(Dispatchers.Default) { PacketText.followStream(src, all, record) }
            streamLoading = false
        }
    }

    fun closeStream() { stream = null }

    /** A file name for the text export, next to the capture's own name. */
    fun exportFileName(): String = (fileName?.substringBeforeLast('.') ?: "capture") + "-packets.txt"

    /**
     * Writes every packet in the current filtered list, fully decoded with payload text and hex,
     * to [uri] as UTF-8 text.
     */
    fun exportText(uri: Uri) {
        val src = source ?: return
        val idx = index ?: return
        val packets = filtered
        val name = fileName ?: "capture"
        val spec = filter
        exportTotal = packets.size
        exportProgress = 0
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?: throw java.io.IOException("Could not open the file for writing")
                    stream.bufferedWriter(Charsets.UTF_8).use { out ->
                        PacketText.export(src, packets, idx.summary, name, describe(spec), out) { n ->
                            viewModelScope.launch(Dispatchers.Main) { exportProgress = n }
                        }
                    }
                }
                message = getApplication<Application>().getString(com.nitinshukla.notebook.R.string.pcap_exported, packets.size)
            } catch (e: Exception) {
                message = getApplication<Application>().getString(com.nitinshukla.notebook.R.string.pcap_export_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                exportProgress = -1
            }
        }
    }

    private fun describe(f: PacketFilter): String? {
        if (f.isEmpty) return null
        val parts = ArrayList<String>(4)
        if (f.ip.isNotBlank()) parts += "ip ${f.ip.trim()}"
        if (f.transport != TransportFilter.ANY) parts += f.transport.name.lowercase()
        if (f.port.isNotBlank()) parts += "port ${f.port.trim()}"
        if (f.expression.isNotBlank()) parts += f.expression.trim()
        return parts.joinToString(" ")
    }

    fun select(record: PacketRecord) {
        val src = source ?: return
        stream = null
        viewModelScope.launch {
            detail = withContext(Dispatchers.Default) {
                val bytes = src.read(record.dataOffset, record.capturedLength)
                val decoded = PacketDecoder.decode(bytes, record.linkType)
                PacketDetail(
                    record, decoded, HexDump.lines(bytes), bytes,
                    payloadText = PacketText.payloadText(bytes, decoded.appPayloadOffset, decoded.appPayloadLength),
                    payloadBinary = decoded.appPayloadLength > 0 && PacketText.printableRatio(bytes, decoded.appPayloadOffset, decoded.appPayloadLength) < 0.7,
                )
            }
        }
    }

    fun clearSelection() { detail = null; stream = null }

    /** Previous / next packet in the filtered list, from the one shown in the detail view. */
    fun step(delta: Int) {
        val current = detail?.record ?: return
        val list = filtered
        val at = list.indexOfFirst { it.index == current.index }
        if (at < 0) return
        list.getOrNull(at + delta)?.let(::select)
    }

    private fun copyToCache(uri: Uri): Pair<String, File> {
        val resolver = getApplication<Application>().contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "capture"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        val dir = File(getApplication<Application>().cacheDir, "pcap").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "open.cap")
        resolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it, 256 * 1024) } }
            ?: throw PcapFormatException("Could not read the file")
        return name to file
    }

    override fun onCleared() {
        close()
    }
}
