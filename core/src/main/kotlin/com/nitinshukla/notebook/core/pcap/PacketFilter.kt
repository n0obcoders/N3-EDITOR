package com.nitinshukla.notebook.core.pcap

/** Transport choice of the filter builder. */
enum class TransportFilter { ANY, TCP, UDP }

/**
 * One filter over the packet list, combining the builder fields (IP, TCP/UDP, port) with a typed
 * expression. Every part that is set must match.
 *
 * Builder fields:
 * - [ip]: an address matched against source or destination: exact (`10.0.0.5`, `fe80::1`), a
 *   prefix (`10.0.` or `10.0.0.*`) or an IPv4 CIDR block (`10.0.0.0/8`).
 * - [transport]: TCP or UDP. DNS over UDP is UDP, HTTP over TCP is TCP.
 * - [port]: a port or range (`443`, `8000-8100`) matched against source or destination port.
 *
 * Expression terms, separated by spaces and all required (`and`/`&&` are accepted and ignored,
 * `not`/`!` negates the next term):
 * - `tcp`, `udp`, `icmp`, `icmpv6`: transport; other protocol names (`dns`, `tls`, `arp`, …)
 *   match the decoded protocol.
 * - `ip X`, `host X`, `ip.addr==X`; `src X`, `ip.src==X`; `dst X`, `ip.dst==X`; a bare address.
 * - `port N`, `tcp.port==N`, `udp.port==N` (the last two also fix the transport); `sport N`,
 *   `dport N`, `tcp.srcport==N`, `tcp.dstport==N`, `udp.srcport==N`, `udp.dstport==N`; `:N`.
 * - anything else: text found in the protocol, addresses or info, or the packet number.
 */
data class PacketFilter(
    val ip: String = "",
    val transport: TransportFilter = TransportFilter.ANY,
    val port: String = "",
    val expression: String = "",
) {
    val isEmpty: Boolean get() = ip.isBlank() && transport == TransportFilter.ANY && port.isBlank() && expression.isBlank()

    /** Compiles the filter once; the result is applied to every packet. */
    fun compile(): (PacketRecord) -> Boolean {
        val parts = ArrayList<(PacketRecord) -> Boolean>()
        if (ip.isNotBlank()) parts += hostTerm(ip.trim(), Direction.EITHER) ?: textTerm(ip.trim())
        when (transport) {
            TransportFilter.TCP -> parts += { it.transport == "TCP" }
            TransportFilter.UDP -> parts += { it.transport == "UDP" }
            TransportFilter.ANY -> Unit
        }
        if (port.isNotBlank()) parts += portTerm(port.trim(), Direction.EITHER) ?: NONE
        parts += parseExpression(expression)
        if (parts.isEmpty()) return { true }
        return { p -> parts.all { it(p) } }
    }

    fun matches(record: PacketRecord): Boolean = compile()(record)

    companion object {
        private enum class Direction { EITHER, SRC, DST }

        private val TRANSPORTS = setOf("tcp", "udp", "icmp", "icmpv6")
        private val NOISE = setOf("and", "&&", "&")
        private val NONE: (PacketRecord) -> Boolean = { false }

        /** Parses the typed expression into predicates, one per term. */
        fun parseExpression(text: String): List<(PacketRecord) -> Boolean> {
            val tokens = text.trim().split(Regex("\\s+"))
                .filter { it.isNotEmpty() && it.lowercase() !in NOISE }
                .flatMap { if (it.length > 1 && it.startsWith("!") && !it.startsWith("!=")) listOf("not", it.substring(1)) else listOf(it) }
            val out = ArrayList<(PacketRecord) -> Boolean>()
            var i = 0
            var negate = false
            fun add(term: (PacketRecord) -> Boolean) {
                out += if (negate) { p -> !term(p) } else term
                negate = false
            }
            fun next(): String? = tokens.getOrNull(i + 1)?.also { i++ }
            while (i < tokens.size) {
                val raw = tokens[i]
                val t = raw.lowercase()
                if (t == "not" || t == "!") { negate = !negate; i++; continue }
                val eq = t.indexOf("==").takeIf { it > 0 }
                if (eq != null) {
                    val key = t.substring(0, eq)
                    val value = raw.substring(eq + 2)
                    add(keyValueTerm(key, value) ?: textTerm(raw))
                    i++
                    continue
                }
                when (t) {
                    in TRANSPORTS -> { val name = t; add { it.transport.equals(name, true) } }
                    "ip", "host", "addr" -> next()?.let { v -> add(hostTerm(v, Direction.EITHER) ?: textTerm(v)) }
                    "src" -> next()?.let { v ->
                        if (v.equals("port", true)) next()?.let { n -> add(portTerm(n, Direction.SRC) ?: NONE) }
                        else add(hostTerm(v, Direction.SRC) ?: textTerm(v))
                    }
                    "dst" -> next()?.let { v ->
                        if (v.equals("port", true)) next()?.let { n -> add(portTerm(n, Direction.DST) ?: NONE) }
                        else add(hostTerm(v, Direction.DST) ?: textTerm(v))
                    }
                    "port" -> next()?.let { v -> add(portTerm(v, Direction.EITHER) ?: NONE) }
                    "sport" -> next()?.let { v -> add(portTerm(v, Direction.SRC) ?: NONE) }
                    "dport" -> next()?.let { v -> add(portTerm(v, Direction.DST) ?: NONE) }
                    else -> when {
                        t.startsWith(":") && t.length > 1 -> add(portTerm(t.substring(1), Direction.EITHER) ?: textTerm(raw))
                        looksLikeAddress(t) -> add(hostTerm(t, Direction.EITHER) ?: textTerm(raw))
                        else -> add(textTerm(raw))
                    }
                }
                i++
            }
            return out
        }

        private fun keyValueTerm(key: String, value: String): ((PacketRecord) -> Boolean)? = when (key) {
            "ip.addr", "ip", "ipv6.addr", "host", "addr" -> hostTerm(value, Direction.EITHER)
            "ip.src", "ipv6.src", "src" -> hostTerm(value, Direction.SRC)
            "ip.dst", "ipv6.dst", "dst" -> hostTerm(value, Direction.DST)
            "port" -> portTerm(value, Direction.EITHER)
            "tcp.port" -> both({ it.transport == "TCP" }, portTerm(value, Direction.EITHER))
            "udp.port" -> both({ it.transport == "UDP" }, portTerm(value, Direction.EITHER))
            "tcp.srcport" -> both({ it.transport == "TCP" }, portTerm(value, Direction.SRC))
            "tcp.dstport" -> both({ it.transport == "TCP" }, portTerm(value, Direction.DST))
            "udp.srcport" -> both({ it.transport == "UDP" }, portTerm(value, Direction.SRC))
            "udp.dstport" -> both({ it.transport == "UDP" }, portTerm(value, Direction.DST))
            "sport", "srcport" -> portTerm(value, Direction.SRC)
            "dport", "dstport" -> portTerm(value, Direction.DST)
            "proto", "protocol" -> { p -> p.protocol.equals(value, true) || p.transport.equals(value, true) }
            else -> null
        }

        private fun both(a: (PacketRecord) -> Boolean, b: ((PacketRecord) -> Boolean)?): ((PacketRecord) -> Boolean)? =
            b?.let { second -> { p -> a(p) && second(p) } }

        private fun textTerm(raw: String): (PacketRecord) -> Boolean {
            val t = raw.lowercase()
            return { p ->
                p.protocol.lowercase() == t || p.transport.lowercase() == t ||
                    p.protocol.contains(t, true) || p.source.contains(t, true) ||
                    p.destination.contains(t, true) || p.info.contains(t, true) || p.index.toString() == t
            }
        }

        private fun looksLikeAddress(t: String): Boolean =
            Regex("^\\d{1,3}(\\.\\d{0,3}){1,3}\\*?(/\\d{1,2})?$").matches(t) ||
                (t.count { it == ':' } >= 2 && Regex("^[0-9a-f:.]+$").matches(t))

        /** Host matcher for an exact address, a prefix ("10.0." / "10.0.0.*") or an IPv4 CIDR block. */
        private fun hostTerm(value: String, dir: Direction): ((PacketRecord) -> Boolean)? {
            val v = value.trim().lowercase().removePrefix("[").removeSuffix("]")
            if (v.isEmpty()) return null
            val single: (String) -> Boolean = when {
                v.contains('/') -> cidr(v) ?: return null
                v.endsWith("*") -> { val prefix = v.removeSuffix("*"); { h -> h.startsWith(prefix, true) } }
                v.endsWith(".") || (v.count { it == '.' } in 1..2 && !v.contains(':')) -> {
                    val prefix = if (v.endsWith(".")) v else "$v.";
                    { h -> h.startsWith(prefix, true) }
                }
                else -> { h -> h.equals(v, true) }
            }
            return when (dir) {
                Direction.EITHER -> { p -> single(p.srcHost) || single(p.dstHost) }
                Direction.SRC -> { p -> single(p.srcHost) }
                Direction.DST -> { p -> single(p.dstHost) }
            }
        }

        private fun cidr(v: String): ((String) -> Boolean)? {
            val (addr, bitsText) = v.split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val bits = bitsText.toIntOrNull()?.takeIf { it in 0..32 } ?: return null
            val base = ipv4ToInt(addr) ?: return null
            val mask = if (bits == 0) 0 else (-1 shl (32 - bits))
            val network = base and mask
            return { h -> ipv4ToInt(h)?.let { (it and mask) == network } == true }
        }

        fun ipv4ToInt(text: String): Int? {
            val parts = text.split('.')
            if (parts.size != 4) return null
            var out = 0
            for (part in parts) {
                val n = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
                out = (out shl 8) or n
            }
            return out
        }

        /** Port matcher for "443" or "8000-8100". */
        private fun portTerm(value: String, dir: Direction): ((PacketRecord) -> Boolean)? {
            val v = value.trim()
            val range = if (v.contains('-')) {
                val (a, b) = v.split('-', limit = 2)
                val lo = a.trim().toIntOrNull() ?: return null
                val hi = b.trim().toIntOrNull() ?: return null
                minOf(lo, hi)..maxOf(lo, hi)
            } else {
                val n = v.toIntOrNull() ?: return null
                n..n
            }
            if (range.last < 0 || range.first > 65535) return null
            return when (dir) {
                Direction.EITHER -> { p -> p.srcPort in range || p.dstPort in range }
                Direction.SRC -> { p -> p.srcPort in range }
                Direction.DST -> { p -> p.dstPort in range }
            }
        }
    }
}
