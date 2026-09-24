package org.akagi.mobile

import android.content.Context
import android.util.Base64
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Small real HTTP/WebSocket echo server confined to the emulator's loopback. */
internal class LoopbackSocketFixture(context: Context) : Closeable {
    private val page = context.assets.open("fixtures/capture.html").use { it.readBytes() }
    private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
    private val clients = CopyOnWriteArrayList<Socket>()
    private val executor = Executors.newCachedThreadPool()
    val port: Int = socket.localPort

    init {
        executor.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                clients.add(client)
                executor.execute {
                    client.use { runCatching { serve(it) } }
                    clients.remove(client)
                }
            }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = 20_000
        val input = DataInputStream(client.getInputStream())
        val output = client.getOutputStream()
        val request = readLine(input)
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split > 0) headers[line.take(split).lowercase()] = line.drop(split + 1).trim()
        }
        if (headers["upgrade"].equals("websocket", ignoreCase = true)) {
            val key = requireNotNull(headers["sec-websocket-key"])
            val accept = Base64.encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                Base64.NO_WRAP,
            )
            output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
            output.flush()
            while (!client.isClosed) {
                val first = input.readUnsignedByte()
                val second = input.readUnsignedByte()
                val opcode = first and 0x0f
                var length = (second and 0x7f).toLong()
                if (length == 126L) length = input.readUnsignedShort().toLong()
                else if (length == 127L) length = input.readLong()
                require(length in 0..65_536)
                val mask = if (second and 0x80 != 0) ByteArray(4).also(input::readFully) else null
                val payload = ByteArray(length.toInt()).also(input::readFully)
                if (mask != null) payload.indices.forEach { payload[it] = (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
                when (opcode) {
                    1, 2 -> sendFrame(output, opcode, payload)
                    8 -> { sendFrame(output, 8, payload); return }
                    9 -> sendFrame(output, 10, payload)
                }
            }
        } else {
            val path = request.split(' ').getOrElse(1) { "/" }
            val body = when {
                path.startsWith("/untrusted") -> """
                    <!doctype html><script>parent.postMessage(window.AkagiCapture ? 'untrusted:has-bridge' : 'untrusted:no-bridge','*');</script>
                """.trimIndent().toByteArray()
                path.startsWith("/child") -> """
                    <!doctype html><script>
                    const socket = new WebSocket('ws://' + location.host + '/socket');
                    socket.onopen = () => socket.send('trusted child');
                    socket.onmessage = () => socket.close(1000, 'child complete');
                    </script>
                """.trimIndent().toByteArray()
                else -> page
            }
            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n".toByteArray())
            output.write(body)
            output.flush()
        }
    }

    private fun sendFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        output.write(0x80 or opcode)
        if (payload.size < 126) output.write(payload.size)
        else {
            output.write(126)
            output.write(payload.size shr 8)
            output.write(payload.size and 0xff)
        }
        output.write(payload)
        output.flush()
    }

    private fun readLine(input: DataInputStream): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 8192) {
            val value = input.read()
            if (value == -1) throw EOFException()
            if (value == 10) return bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
            bytes.add(value.toByte())
        }
        error("HTTP header too long")
    }

    override fun close() {
        socket.close()
        clients.forEach { runCatching { it.close() } }
        executor.shutdownNow()
    }
}
