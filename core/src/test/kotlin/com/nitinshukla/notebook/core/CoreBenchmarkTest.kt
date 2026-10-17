package com.nitinshukla.notebook.core

import com.nitinshukla.notebook.core.code.CodeChecker
import com.nitinshukla.notebook.core.code.Language
import com.nitinshukla.notebook.core.code.SyntaxHighlighter
import com.nitinshukla.notebook.core.pcap.ByteArraySource
import com.nitinshukla.notebook.core.pcap.PacketDecoder
import com.nitinshukla.notebook.core.pcap.PcapParser
import com.nitinshukla.notebook.core.pcap.PcapBuilder
import com.nitinshukla.notebook.core.pcap.Pk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Throughput of the pure-Kotlin engines on the desktop JVM. These are not device numbers and
 * have no pre-upgrade counterpart (the features are new); they exist so a regression shows up
 * and so the README can quote something measured. Each prints one `BENCH` line.
 */
class CoreBenchmarkTest {

    @Test
    fun highlighterThroughput() {
        val python = buildString {
            repeat(6_000) { i ->
                append("def func_$i(x, y=\"text\"):\n    # comment $i\n    return x + y * $i  # 0x1F\n\n")
            }
        }
        repeat(3) { SyntaxHighlighter.tokenize(python, Language.PYTHON) }
        val (tokens, ns) = timed { SyntaxHighlighter.tokenize(python, Language.PYTHON).size }
        val (issues, checkNs) = timed { CodeChecker.check(python, Language.PYTHON).size }
        println("BENCH highlight language=PYTHON chars=${python.length} tokens=$tokens tokenize_ms=${ns / 1e6} check_ms=${checkNs / 1e6} issues=$issues")
        assertTrue(tokens > 0)
        assertEquals(0, issues)
    }

    @Test
    fun pcapIndexThroughput() {
        val builder = PcapBuilder(little = true)
        val packets = 50_000
        repeat(packets) { i ->
            val payload = if (i % 2 == 0) Pk.dnsQuery(i and 0xFFFF, "host$i.example.com") else ByteArray(600)
            val l4 = if (i % 2 == 0) Pk.udp(40_000 + (i % 1000), 53, payload) else Pk.tcp(sport = 50_000, dport = 443, flags = 0x018, payload = payload)
            val ip = Pk.ipv4(l4, if (i % 2 == 0) 17 else 6, src = "10.0.0.${i % 250 + 1}", dst = "10.0.1.${i % 7 + 1}")
            builder.packet(Pk.eth(ip, 0x0800), tsSec = 1_700_000_000L + i / 100, tsFrac = (i % 100) * 10_000L)
        }
        val bytes = builder.build()
        val source = ByteArraySource(bytes)
        PcapParser.index(source) // warm-up
        val (index, ns) = timed { PcapParser.index(source) }
        val sample = index.packets[12_345]
        val raw = source.read(sample.dataOffset, sample.capturedLength)
        repeat(100) { PacketDecoder.decode(raw, sample.linkType) }
        val (_, decodeNs) = timed { repeat(1_000) { PacketDecoder.decode(raw, sample.linkType) } }
        println("BENCH pcap_index packets=$packets file_bytes=${bytes.size} index_ms=${ns / 1e6} mb_per_s=${bytes.size / 1e6 / (ns / 1e9)} decode_one_packet_us=${decodeNs / 1e3 / 1_000}")
        assertEquals(packets, index.summary.packetCount)
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val result = block()
        return result to (System.nanoTime() - start)
    }
}
