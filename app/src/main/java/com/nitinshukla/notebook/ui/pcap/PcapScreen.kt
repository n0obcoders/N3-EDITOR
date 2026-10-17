package com.nitinshukla.notebook.ui.pcap

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nitinshukla.notebook.R
import com.nitinshukla.notebook.core.pcap.CaptureSummary
import com.nitinshukla.notebook.core.pcap.HexDump
import com.nitinshukla.notebook.core.pcap.PacketRecord
import com.nitinshukla.notebook.core.pcap.PcapTime
import com.nitinshukla.notebook.core.pcap.TransportFilter
import com.nitinshukla.notebook.ui.common.formatBytes
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcapScreen(onBack: () -> Unit, viewModel: PcapViewModel = hiltViewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::open) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let(viewModel::exportText) }
    val index = viewModel.index
    val detail = viewModel.detail
    var tab by rememberSaveable { mutableIntStateOf(1) }
    val errorTemplate = stringResource(R.string.pcap_open_error, "%s")

    viewModel.error?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(errorTemplate.format(message))
            viewModel.consumeError()
        }
    }
    viewModel.message?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }
    BackHandler(enabled = viewModel.stream != null) { viewModel.closeStream() }
    BackHandler(enabled = detail != null && viewModel.stream == null) { viewModel.clearSelection() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.nav_pcap_reader), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        viewModel.fileName?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                },
                navigationIcon = { IconButton(onClick = { if (viewModel.stream != null) viewModel.closeStream() else if (detail != null) viewModel.clearSelection() else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    if (index != null) {
                        IconButton(onClick = { exporter.launch(viewModel.exportFileName()) }, enabled = viewModel.exportProgress < 0) { Icon(Icons.Default.Description, stringResource(R.string.pcap_export_text)) }
                        IconButton(onClick = viewModel::toggleRelativeTime) { Icon(Icons.Default.Schedule, stringResource(R.string.pcap_relative_time)) }
                        IconButton(onClick = viewModel::close) { Icon(Icons.Default.Close, stringResource(R.string.pcap_close)) }
                    }
                    IconButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FolderOpen, stringResource(R.string.pcap_open_capture)) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                viewModel.loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.pcap_indexing, viewModel.progressPackets))
                }
                index == null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.pcap_empty_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.pcap_empty_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    androidx.compose.material3.Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.pcap_open_capture)) }
                }
                viewModel.stream != null || viewModel.streamLoading -> StreamView(viewModel.stream, detail?.record)
                detail != null -> PacketDetailView(
                    detail = detail,
                    onPrevious = { viewModel.step(-1) },
                    onNext = { viewModel.step(1) },
                    onFollowStream = viewModel::followStream,
                    onCopied = { scope.launch { snackbar.showSnackbar(it) } },
                )
                else -> Column(Modifier.fillMaxSize()) {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.pcap_summary)) })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.pcap_packets)) })
                    }
                    if (tab == 0) SummaryView(index.summary) else PacketList(viewModel)
                }
            }
            if (viewModel.exportProgress >= 0) {
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    val total = viewModel.exportTotal.coerceAtLeast(1)
                    androidx.compose.material3.LinearProgressIndicator(progress = { viewModel.exportProgress / total.toFloat() }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.pcap_exporting, viewModel.exportProgress, viewModel.exportTotal), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SummaryView(s: CaptureSummary) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyValue(stringResource(R.string.pcap_format), s.format.name.replace('_', ' '))
                    KeyValue(stringResource(R.string.pcap_file_size), formatBytes(s.fileSize))
                    KeyValue(stringResource(R.string.pcap_packet_count), s.packetCount.toString())
                    KeyValue(stringResource(R.string.pcap_bytes), "${formatBytes(s.totalCapturedBytes)} / ${formatBytes(s.totalOriginalBytes)}")
                    KeyValue(stringResource(R.string.pcap_duration), PcapTime.formatRelative(s.durationMicros, 0) + " s")
                    s.firstTimestampMicros?.let { KeyValue(stringResource(R.string.pcap_first_packet), PcapTime.formatUtc(it)) }
                    s.lastTimestampMicros?.let { KeyValue(stringResource(R.string.pcap_last_packet), PcapTime.formatUtc(it)) }
                    if (s.truncated) Text(stringResource(R.string.pcap_truncated, s.packetCount), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (s.interfaces.isNotEmpty()) item {
            Section(stringResource(R.string.pcap_interfaces)) {
                s.interfaces.forEach { i ->
                    KeyValue("#${i.id} ${i.name ?: i.linkTypeName}", "${i.linkTypeName} · snaplen ${i.snapLen}")
                }
            }
        }
        if (s.protocolCounts.isNotEmpty()) item {
            Section(stringResource(R.string.pcap_protocols)) { s.protocolCounts.forEach { (k, v) -> KeyValue(k, v.toString()) } }
        }
        if (s.topTalkers.isNotEmpty()) item {
            Section(stringResource(R.string.pcap_top_talkers)) { s.topTalkers.forEach { (k, v) -> KeyValue(k, formatBytes(v)) } }
        }
        if (s.topConversations.isNotEmpty()) item {
            Section(stringResource(R.string.pcap_conversations)) { s.topConversations.forEach { (k, v) -> KeyValue(k, formatBytes(v)) } }
        }
        if (s.warnings.isNotEmpty()) item {
            Section(stringResource(R.string.pcap_warnings)) { s.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1.3f))
    }
}

@Composable
private fun PacketList(viewModel: PcapViewModel) {
    val index = viewModel.index ?: return
    val first = index.summary.firstTimestampMicros ?: 0L
    val rows = viewModel.filtered
    Column(Modifier.fillMaxSize()) {
        FilterBuilder(viewModel, shown = rows.size, total = index.packets.size)
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.pcap_no_matches)) }
            return
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.index }) { p ->
                PacketRow(p, if (viewModel.relativeTime) PcapTime.formatRelative(p.timestampMicros, first) else PcapTime.formatUtc(p.timestampMicros)) { viewModel.select(p) }
                HorizontalDivider()
            }
        }
    }
}

/**
 * The combined filter: IP address, TCP / UDP and port, plus a typed expression. Every part that
 * is filled in must match, so "10.0.0.5" + TCP + "443" shows that host's HTTPS traffic only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBuilder(viewModel: PcapViewModel, shown: Int, total: Int) {
    val f = viewModel.filter
    var expanded by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = f.expression,
                onValueChange = { v -> viewModel.updateFilter { it.copy(expression = v) } },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.pcap_filter_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { expanded = !expanded }) { Icon(Icons.Default.FilterList, stringResource(R.string.pcap_filter_toggle)) }
            if (!f.isEmpty) IconButton(onClick = viewModel::clearFilter) { Icon(Icons.Default.Close, stringResource(R.string.pcap_filter_clear)) }
        }
        if (expanded) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = f.ip,
                    onValueChange = { v -> viewModel.updateFilter { it.copy(ip = v) } },
                    singleLine = true,
                    label = { Text(stringResource(R.string.pcap_filter_ip)) },
                    placeholder = { Text(stringResource(R.string.pcap_filter_ip_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = f.port,
                    onValueChange = { v -> viewModel.updateFilter { it.copy(port = v.filter { c -> c.isDigit() || c == '-' }) } },
                    singleLine = true,
                    label = { Text(stringResource(R.string.pcap_filter_port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(104.dp),
                )
            }
            val options = listOf(
                TransportFilter.ANY to R.string.pcap_filter_any,
                TransportFilter.TCP to R.string.pcap_filter_tcp,
                TransportFilter.UDP to R.string.pcap_filter_udp,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                options.forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = f.transport == value,
                        onClick = { viewModel.updateFilter { it.copy(transport = value) } },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(stringResource(label)) }
                }
            }
        }
        Text(
            stringResource(R.string.pcap_shown_of, shown, total),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
        )
    }
}

@Composable
private fun PacketRow(p: PacketRecord, time: String, onClick: () -> Unit) {
    val tint = protocolColor(p.protocol)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = "Packet ${p.index}, ${p.protocol}, ${p.source} to ${p.destination}, ${p.info}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(p.index.toString(), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(44.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = tint.copy(alpha = 0.18f), shape = MaterialTheme.shapes.extraSmall) {
                    Text(p.protocol, style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                if (p.transport.isNotEmpty() && p.transport != p.protocol) {
                    Spacer(Modifier.width(4.dp))
                    Text(p.transport, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Text(time, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("${p.capturedLength} B", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (p.source.isNotEmpty() || p.destination.isNotEmpty()) {
                Text("${p.source} → ${p.destination}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(p.info, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun protocolColor(protocol: String) = when (protocol) {
    "TCP" -> MaterialTheme.colorScheme.primary
    "UDP" -> MaterialTheme.colorScheme.tertiary
    "DNS", "HTTP", "TLS" -> MaterialTheme.colorScheme.secondary
    "ARP", "ICMP", "ICMPv6" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun PacketDetailView(detail: PacketDetail, onPrevious: () -> Unit, onNext: () -> Unit, onFollowStream: () -> Unit, onCopied: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val copied = stringResource(R.string.pcap_copied)
    var tab by rememberSaveable { mutableIntStateOf(1) }
    val p = detail.record
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.pcap_packet_title, p.index) + " · " + p.protocol, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.pcap_captured_of, p.capturedLength, p.originalLength) + " · " + PcapTime.formatUtc(p.timestampMicros), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onPrevious) { Icon(Icons.Default.KeyboardArrowUp, "Previous packet") }
            IconButton(onClick = onNext) { Icon(Icons.Default.KeyboardArrowDown, "Next packet") }
            if (p.srcPort >= 0) IconButton(onClick = onFollowStream) { Icon(Icons.AutoMirrored.Filled.CompareArrows, stringResource(R.string.pcap_follow_stream)) }
            IconButton(onClick = { clipboard.setText(AnnotatedString(HexDump.format(detail.bytes))); onCopied(copied) }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.pcap_copy_hex)) }
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.pcap_details)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.pcap_hex_dump)) })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.pcap_payload)) })
        }
        if (tab == 2) {
            PayloadView(detail, onCopy = { clipboard.setText(AnnotatedString(detail.payloadText)); onCopied(copied) })
        } else if (tab == 0) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(detail.decoded.layers) { layer ->
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Text(layer.name, style = MaterialTheme.typography.titleSmall)
                            Text(layer.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("offset ${layer.offset}, ${layer.length} bytes", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            layer.fields.forEach { f -> KeyValue(f.name, f.value) }
                        }
                    }
                }
            }
        } else {
            val scroll = rememberScrollState()
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).horizontalScroll(scroll), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                    items(detail.hex, key = { it.offset }) { line ->
                        Row {
                            Text(line.offset.toString(16).padStart(8, '0'), fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(line.hex, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            Spacer(Modifier.width(12.dp))
                            Text(line.ascii, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PayloadView(detail: PacketDetail, onCopy: () -> Unit) {
    if (detail.payloadText.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.pcap_no_payload)) }
        return
    }
    // Lines are composed lazily so a 64 KB payload does not lay out as one huge text.
    val lines = remember(detail) { detail.payloadText.lines() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${detail.decoded.appPayloadLength} bytes" + if (detail.payloadBinary) " · " + stringResource(R.string.pcap_binary_payload) else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, stringResource(R.string.pcap_copy_payload)) }
        }
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                items(lines.size) { i -> Text(lines[i], fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun StreamView(text: String?, record: PacketRecord?) {
    Column(Modifier.fillMaxSize()) {
        record?.let {
            Text(
                stringResource(R.string.pcap_stream_title, "${it.transport} ${it.source} \u21c4 ${it.destination}"),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        when {
            text == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            text.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.pcap_stream_empty)) }
            else -> {
                val lines = remember(text) { text.lines() }
                SelectionContainer {
                    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                        items(lines.size) { i ->
                            val line = lines[i]
                            Text(
                                line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                fontWeight = if (line.startsWith(">>> ") || line.startsWith("<<< ")) FontWeight.SemiBold else FontWeight.Normal,
                                color = when {
                                    line.startsWith(">>> ") -> MaterialTheme.colorScheme.primary
                                    line.startsWith("<<< ") -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
