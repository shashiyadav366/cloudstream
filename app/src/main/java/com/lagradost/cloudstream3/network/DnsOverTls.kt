package com.lagradost.cloudstream3.network

import okhttp3.Dns
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.ThreadLocalRandom
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class DnsOverTls internal constructor(
    private val serverName: String,
    private val socketFactory: SSLSocketFactory = defaultSslSocketFactory(),
    private val connectTimeoutMs: Int = 5_000,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.isBlank()) return emptyList()
        return try {
            doLookup(hostname)
        } catch (t: Throwable) {
            try {
                InetAddress.getAllByName(hostname).toList()
            } catch (ex: Throwable) {
                val failure = UnknownHostException("DoT lookup failed for $hostname")
                failure.initCause(ex)
                failure.addSuppressed(t)
                throw failure
            }
        }
    }

    private fun doLookup(hostname: String): List<InetAddress> {
        val serverAddresses = InetAddress.getAllByName(serverName)
        if (serverAddresses.isEmpty()) {
            throw UnknownHostException("Cannot resolve DoT server $serverName")
        }

        var lastError: Throwable? = null
        for (serverAddress in serverAddresses) {
            try {
                return queryServer(serverAddress, hostname)
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: UnknownHostException("No DoT server address for $serverName")
    }

    private fun queryServer(serverAddress: InetAddress, hostname: String): List<InetAddress> {
        val socket = socketFactory.createSocket() as SSLSocket
        try {
            socket.connect(InetSocketAddress(serverAddress, DNS_PORT), connectTimeoutMs)
            socket.sslParameters = SSLParameters().apply {
                serverNames = listOf(SNIHostName(serverName))
                endpointIdentificationAlgorithm = "HTTPS"
            }
            socket.startHandshake()

            val output = socket.getOutputStream()
            val input = socket.getInputStream()
            val addresses = mutableListOf<InetAddress>()
            queryType(output, input, hostname, TYPE_AAAA) { addresses += it }
            queryType(output, input, hostname, TYPE_A) { addresses += it }
            return addresses.distinct()
        } finally {
            socket.close()
        }
    }

    private fun queryType(
        output: OutputStream,
        input: InputStream,
        hostname: String,
        qtype: Int,
        collect: (List<InetAddress>) -> Unit,
    ) {
        val query = buildQuery(ThreadLocalRandom.current().nextInt(0, 0x10000), hostname, qtype)
        output.write(query.size ushr 8)
        output.write(query.size and 0xff)
        output.write(query)
        output.flush()

        val lenHi = input.read()
        val lenLo = input.read()
        if (lenHi < 0 || lenLo < 0) throw IOException("DoT server closed the connection")
        val length = (lenHi shl 8) or lenLo
        if (length <= 0) throw IOException("Invalid DoT response length")

        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val result = input.read(data, read, length - read)
            if (result < 0) throw IOException("Truncated DoT response")
            read += result
        }

        val addresses = mutableListOf<InetAddress>()
        parseResponse(data, qtype, addresses)
        collect(addresses)
    }

    private fun buildQuery(id: Int, hostname: String, qtype: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        fun u16(value: Int) {
            buffer.write(value ushr 8)
            buffer.write(value and 0xff)
        }
        u16(id)
        u16(0x0100)
        u16(1)
        u16(0)
        u16(0)
        u16(0)
        for (label in hostname.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            buffer.write(bytes.size)
            buffer.write(bytes)
        }
        buffer.write(0)
        u16(qtype)
        u16(1)
        return buffer.toByteArray()
    }

    private fun parseResponse(data: ByteArray, qtype: Int, addresses: MutableCollection<InetAddress>) {
        if (data.size < 12) return
        val flags = u16(data, 2)
        if (flags and 0x8000 == 0) return
        if (flags and 0x000F != 0) return

        val questionCount = u16(data, 4)
        val answerCount = u16(data, 6)
        var offset = 12
        repeat(questionCount) {
            offset = readName(data, offset).second + 4
        }
        repeat(answerCount) {
            if (offset >= data.size) return@repeat
            val (_, next) = readName(data, offset)
            offset = next
            if (offset + 10 > data.size) return
            val answerType = u16(data, offset)
            val length = u16(data, offset + 8)
            val rdataStart = offset + 10
            if (answerType == qtype && rdataStart + length <= data.size) {
                if (qtype == TYPE_A && length == 4) {
                    addresses += InetAddress.getByAddress(data.copyOfRange(rdataStart, rdataStart + 4))
                } else if (qtype == TYPE_AAAA && length == 16) {
                    addresses += InetAddress.getByAddress(data.copyOfRange(rdataStart, rdataStart + 16))
                }
            }
            offset = rdataStart + length
        }
    }

    private fun readName(data: ByteArray, start: Int): Pair<String, Int> {
        val labels = mutableListOf<String>()
        var offset = start
        var end = start
        var jumped = false
        var guard = 0
        while (guard < NAME_JUMP_GUARD) {
            guard++
            if (offset >= data.size) break
            val length = data[offset].toInt() and 0xff
            when {
                length == 0 -> {
                    if (!jumped) end = offset + 1
                    return labels.joinToString(".") to end
                }
                length and 0xC0 == 0xC0 -> {
                    if (offset + 1 >= data.size) break
                    val pointer = ((length and 0x3F) shl 8) or (data[offset + 1].toInt() and 0xff)
                    if (!jumped) end = offset + 2
                    offset = pointer
                    jumped = true
                }
                else -> {
                    if (offset + 1 + length > data.size) break
                    labels += String(data, offset + 1, length, Charsets.US_ASCII)
                    offset += 1 + length
                }
            }
        }
        return labels.joinToString(".") to end
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private companion object {
        const val DNS_PORT = 853
        const val TYPE_A = 1
        const val TYPE_AAAA = 28
        const val NAME_JUMP_GUARD = 128

        fun defaultSslSocketFactory(): SSLSocketFactory {
            val context = SSLContext.getInstance("TLS")
            context.init(null, null, null)
            return context.socketFactory
        }
    }
}