package org.akagi.mobile

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.protobuf.ByteString
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import org.akagi.mobile.browser.DEBUG_FIXTURE_EXTRA
import org.akagi.mobile.browser.DEBUG_PORT_EXTRA
import org.akagi.mobile.engine.EngineAdvice
import org.akagi.mobile.engine.MortalSession
import org.akagi.mobile.protocol.LiqiDecoder
import org.akagi.mobile.protocol.LiqiSchema
import org.akagi.mobile.ui.StatusTone
import org.akagi.mobile.ui.UiState
import org.akagi.mobile.ui.tileDescription
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * A real loopback WebSocket drives MainActivity, document-start capture, Liqi,
 * the ordered analysis worker, JNI rules, ONNX inference, and the compact UI.
 * Expected advice comes from the bundled desktop numerical reference and a
 * separate native replay. This test never injects UiAdvice or calls capture().
 */
@RunWith(AndroidJUnit4::class)
class LivePipelineTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun fourPlayerWireMessagesProduceAndRestoreRealLocalAdvice() = exercisePipeline(4)

    @Test fun threePlayerWireMessagesProduceAndRestoreRealLocalAdvice() = exercisePipeline(3)

    private fun exercisePipeline(players: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = desktopCheckedAdvice(context, players)
        val measurements = JSONArray()
        LiqiGameFixture(context, players).use { fixture ->
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(DEBUG_FIXTURE_EXTRA, "capture").putExtra(DEBUG_PORT_EXTRA, fixture.port)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                lateinit var activity: MainActivity
                lateinit var analysis: AnalysisViewModel
                scenario.onActivity {
                    activity = it
                    analysis = ViewModelProvider(it)[AnalysisViewModel::class.java]
                }
                awaitBrowser(activity, "window.liveFixture && window.liveFixture.ready")
                dismissFullscreenEducation(activity)
                awaitBrowserViewport(activity)
                assertEquals(true, evaluate(activity, "window.liveFixture.hookBeforePageScript"))
                assertEquals(null, analysis.state.value.advice)
                compose.onNodeWithTag("advice_chip").assertIsDisplayed()

                // The page sends a genuine masked, binary authGame request. The
                // server validates it before returning auth + an observed hand.
                evaluate(activity, "window.openGame(false); true")
                awaitState(analysis, fixture, "initial hand") {
                    it.status.detail == "Watching $players-player game" && it.advice == null
                }
                assertEquals(1, fixture.authentications.get())
                compose.onNodeWithText("Akagi").assertIsDisplayed()

                fixture.sendDraw()
                val initial = awaitAdvice(analysis, fixture, players)
                assertAdvice(expected, initial)
                assertCompactUi(expected)
                captureScreenshot(activity, "08_live_${players}p_advice")
                measurements.put(measurement("initial", initial))

                // A binary frame with an unknown Liqi type must remove the old
                // recommendation. No synthetic native failure or direct reset.
                fixture.sendInvalidFrame()
                awaitState(analysis, fixture, "invalid-frame pause") {
                    it.advice == null && it.status.detail.contains("Game protocol changed")
                }
                compose.onNodeWithText(expected.recommended.displayLabel()).assertDoesNotExist()
                compose.onNodeWithText("Akagi").assertIsDisplayed()

                // A complete round is a verified recovery boundary. Advancing
                // only the wire step keeps the canonical MJAI smoke hand exact.
                fixture.sendRecoveryRound()
                awaitState(analysis, fixture, "complete-round recovery") {
                    it.status.detail == "Watching $players-player game" && it.advice == null
                }
                fixture.sendDraw()
                val recovered = awaitAdvice(analysis, fixture, players)
                assertAdvice(expected, recovered)
                assertCompactUi(expected)
                measurements.put(measurement("after_complete_round", recovered))

                fixture.disconnect()
                awaitState(analysis, fixture, "disconnection") {
                    it.advice == null && it.status.detail.contains("Game disconnected")
                }
                awaitBrowser(activity, "window.liveFixture.closed")
                compose.onNodeWithText(expected.recommended.displayLabel()).assertDoesNotExist()

                // A new real socket reuses request ID 1, authenticates, and
                // requests syncGame. Hold its reply to inspect the paused UI.
                evaluate(activity, "window.openGame(true); true")
                awaitState(analysis, fixture, "pending reconnect replay") {
                    it.advice == null && it.status.detail == "Restoring round history" && fixture.syncRequests.get() == 1
                }
                assertEquals(2, fixture.authentications.get())
                assertEquals(1, fixture.syncRequests.get())
                compose.onNodeWithText("Akagi").assertIsDisplayed()
                fixture.releaseReplay()
                val replayed = awaitAdvice(analysis, fixture, players)
                assertAdvice(expected, replayed)
                assertCompactUi(expected)
                captureScreenshot(activity, "09_live_${players}p_reconnected")
                measurements.put(measurement("after_sync_game", replayed))

                fixture.endGame()
                awaitState(analysis, fixture, "game end") { it.advice == null && it.status.detail == "Game ended" }
                compose.onNodeWithText(expected.recommended.displayLabel()).assertDoesNotExist()
                fixture.assertHealthy()
                assertEquals(listOf(".lq.FastTest.authGame", ".lq.FastTest.authGame", ".lq.FastTest.syncGame"),
                    fixture.receivedMethods.toList())

                val report = JSONObject().put("players", players).put("desktopCase", desktopCase(players))
                    .put("expectedAction", JSONObject(expected.recommended.eventJson))
                    .put("expectedLegalMask", expected.legalMask).put("expectedScore", expected.recommended.score)
                    .put("websocketAuthentications", fixture.authentications.get())
                    .put("syncRequests", fixture.syncRequests.get()).put("samples", measurements)
                    .put("passed", true)
                    .put("environment", "Android emulator; real loopback Liqi capture and local models")
                val directory = File(context.getExternalFilesDir(null), "measurements")
                check(directory.isDirectory || directory.mkdirs())
                File(directory, "live-pipeline-${players}p.json").writeText(report.toString(2))
            }
        }
    }

    private fun desktopCheckedAdvice(context: Context, players: Int): EngineAdvice {
        val expected = MortalSession(context).use { it.offlineReplay(players) }
        val reference = context.assets.open("models/reference.json").bufferedReader().use { JSONObject(it.readText()) }
            .getJSONArray("cases")
        val row = (0 until reference.length()).map { reference.getJSONObject(it) }
            .single { it.getString("name") == desktopCase(players) }
        assertEquals(players, expected.playerCount)
        assertEquals(row.getInt("argmax"), expected.recommended.index)
        assertEquals(row.getLong("mask_bits"), expected.legalMask)
        val score = row.getJSONArray("q_values").getDouble(expected.recommended.index).toFloat()
        assertClose(score, expected.recommended.score)
        assertTrue(expected.legalMask and (1L shl expected.recommended.index) != 0L)
        return expected
    }

    private fun awaitAdvice(analysis: AnalysisViewModel, fixture: LiqiGameFixture, players: Int): UiState =
        awaitState(analysis, fixture, "$players-player local recommendation") {
            it.advice != null && it.status.label == "$players-player Mortal"
        }

    private fun awaitState(analysis: AnalysisViewModel, fixture: LiqiGameFixture, phase: String,
                           predicate: (UiState) -> Boolean): UiState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
        while (System.nanoTime() < deadline) {
            fixture.assertHealthy()
            val state = analysis.state.value
            check(state.status.tone != StatusTone.ERROR) { "$phase: analysis failed: ${state.status}" }
            if (predicate(state)) {
                compose.waitForIdle()
                return state
            }
            Thread.sleep(50)
        }
        error("Timed out during $phase: ${analysis.state.value}")
    }

    private fun assertAdvice(expected: EngineAdvice, actual: UiState) {
        val advice = checkNotNull(actual.advice)
        assertEquals(expected.recommended.displayLabel(), advice.action)
        assertEquals(expected.recommended.tile, advice.tile)
        assertTrue(actual.status.captureActive)
        assertTrue(actual.status.engineReady)
        assertEquals(StatusTone.READY, actual.status.tone)
        assertNotNull(advice.latencyMs)
        val alternatives = expected.alternatives.filter { it.index != expected.recommended.index }.take(2)
        assertEquals(alternatives.size, advice.alternatives.size)
        advice.alternatives.zip(alternatives).forEach { (shown, reference) ->
            assertEquals(reference.displayLabel(), shown.action)
            assertEquals(reference.tile, shown.tile)
            assertClose(reference.score, checkNotNull(shown.score))
        }
    }

    private fun assertCompactUi(expected: EngineAdvice) {
        compose.onNodeWithTag("advice_chip").assertIsDisplayed()
        compose.onNodeWithText(expected.recommended.displayLabel(), useUnmergedTree = true).assertIsDisplayed()
        expected.recommended.tile?.let {
            compose.onNodeWithContentDescription(tileDescription(it), useUnmergedTree = true).assertIsDisplayed()
        }
        compose.onNodeWithTag("advice_strip").assertDoesNotExist()
        val chip = compose.onNodeWithTag("advice_chip").fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("game_root").fetchSemanticsNode().boundsInRoot
        assertTrue("Live recommendation remains a compact chip", chip.width * chip.height < root.width * root.height * .04f)
        assertTrue("Live recommendation leaves the hand visible", chip.bottom < root.height * .55f)
    }

    private fun measurement(phase: String, state: UiState): JSONObject {
        val advice = checkNotNull(state.advice)
        return JSONObject().put("phase", phase).put("action", advice.action)
            .put("tile", advice.tile ?: JSONObject.NULL).put("latencyMilliseconds", advice.latencyMs)
    }

    private fun assertClose(expected: Float, actual: Float) {
        assertTrue("Expected $expected, got $actual", actual.isFinite() && abs(expected - actual) <= 0.0003f + 0.0002f * abs(expected))
    }

    private fun desktopCase(players: Int) = if (players == 4) "4p_discard_red_0" else "3p_sanma_kita_0"
}

/** Server-side protocol fixture; all inputs are real wire messages, never advice. */
private class LiqiGameFixture(context: Context, private val players: Int) : Closeable {
    private class Connection(val socket: Socket, val output: OutputStream) {
        val closeSent = AtomicBoolean()
    }

    private val schema = LiqiSchema(context.assets.open("protocol/liqi.json").bufferedReader().use { it.readText() })
    private val smoke = context.assets.open("models/smoke_${players}p.jsonl").bufferedReader().useLines { lines ->
        lines.filter(String::isNotBlank).map(::JSONObject).toList()
    }
    private val round = smoke.single { it.getString("type") == "start_kyoku" }
    private val draw = smoke.single { it.getString("type") == "tsumo" }
    private val gameUuid = "local-pipeline-${players}p"
    private val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    private val clients = CopyOnWriteArrayList<Socket>()
    private val current = AtomicReference<Connection?>()
    private val stopped = AtomicBoolean()
    private val failure = AtomicReference<Throwable?>()
    private val roundStep = AtomicInteger(1)
    private val replayGate = Semaphore(0)
    val port: Int = server.localPort
    val authentications = AtomicInteger()
    val syncRequests = AtomicInteger()
    val receivedMethods = CopyOnWriteArrayList<String>()

    init {
        require(players in 3..4)
        require(smoke.first().getInt("id") == 0 && draw.getInt("actor") == 0)
        require(round.getJSONArray("tehais").getJSONArray(0).length() == 13)
        executor.execute {
            while (!stopped.get()) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                clients += socket
                executor.execute {
                    socket.use {
                        try { serve(it) } catch (error: Throwable) {
                            if (!stopped.get()) failure.compareAndSet(null, error)
                        }
                    }
                    clients.remove(socket)
                }
            }
        }
    }

    fun assertHealthy() {
        failure.get()?.let { throw AssertionError("Loopback Liqi fixture failed", it) }
    }

    fun sendDraw() = sendAction("ActionDealTile", roundStep.get() + 1, drawFields())

    fun sendInvalidFrame() = sendBinary(checkNotNull(current.get()), byteArrayOf(0))

    fun sendRecoveryRound() {
        roundStep.addAndGet(100)
        sendAction("ActionNewRound", roundStep.get(), roundFields())
    }

    fun disconnect() {
        val connection = checkNotNull(current.get())
        connection.closeSent.set(true)
        sendFrame(connection, 8, byteArrayOf(3, 0xe8.toByte()))
    }

    fun releaseReplay() = replayGate.release()

    fun endGame() = sendBinary(checkNotNull(current.get()),
        byteArrayOf(1) + wrapper(".lq.NotifyGameTerminate", encode("NotifyGameTerminate", emptyMap())))

    private fun serve(socket: Socket) {
        socket.soTimeout = 60_000
        socket.tcpNoDelay = true
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        val request = readLine(input)
        require(request.startsWith("GET "))
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            require(split > 0)
            headers[line.take(split).lowercase()] = line.drop(split + 1).trim()
        }
        if (!headers["upgrade"].equals("websocket", ignoreCase = true)) {
            val page = html().toByteArray(Charsets.UTF_8)
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${page.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").toByteArray())
            output.write(page)
            output.flush()
            return
        }
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(
            (requireNotNull(headers["sec-websocket-key"]) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
        output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
        output.flush()
        val connection = Connection(socket, output)
        current.set(connection)
        try {
            while (!socket.isClosed) {
                val first = input.readUnsignedByte()
                val second = input.readUnsignedByte()
                require(first and 0x80 != 0) { "Fixture requests must be complete WebSocket messages" }
                require(second and 0x80 != 0) { "Browser client frames must be masked" }
                var length = (second and 0x7f).toLong()
                if (length == 126L) length = input.readUnsignedShort().toLong()
                else if (length == 127L) length = input.readLong()
                require(length in 0..65_536)
                val mask = ByteArray(4).also(input::readFully)
                val payload = ByteArray(length.toInt()).also(input::readFully)
                payload.indices.forEach { payload[it] = (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
                when (first and 0x0f) {
                    2 -> receive(connection, payload)
                    8 -> {
                        if (!connection.closeSent.getAndSet(true)) sendFrame(connection, 8, payload)
                        return
                    }
                    9 -> sendFrame(connection, 10, payload)
                    10 -> Unit
                    else -> error("The test page sent a nonbinary game request")
                }
            }
        } catch (error: EOFException) {
            if (!connection.closeSent.get() && !stopped.get()) throw error
        } finally {
            current.compareAndSet(connection, null)
        }
    }

    private fun receive(connection: Connection, bytes: ByteArray) {
        require(bytes.size >= 3 && bytes[0].toInt() == 2)
        val id = (bytes[1].toInt() and 255) or ((bytes[2].toInt() and 255) shl 8)
        val envelope = schema.json(schema.decode("Wrapper", bytes.copyOfRange(3, bytes.size)))
        val method = envelope.getString("name")
        val payload = Base64.getDecoder().decode(envelope.getString("data"))
        receivedMethods += method
        when (method) {
            ".lq.FastTest.authGame" -> {
                require(id == 1)
                val request = schema.json(schema.decode("ReqAuthGame", payload))
                require(request.getLong("accountId") == 12345L && request.getString("gameUuid") == gameUuid)
                val ordinal = authentications.incrementAndGet()
                val seats = List(players) { if (it == 0) 12345 else 20000 + it }
                respond(connection, id, "ResAuthGame", mapOf("seat_list" to seats, "is_game_start" to true))
                if (ordinal == 1) {
                    sendAction("ActionMJStart", 0, emptyMap())
                    sendAction("ActionNewRound", roundStep.get(), roundFields())
                }
            }
            ".lq.FastTest.syncGame" -> {
                require(id == 2 && authentications.get() == 2)
                schema.decode("ReqSyncGame", payload)
                syncRequests.incrementAndGet()
                check(replayGate.tryAcquire(40, TimeUnit.SECONDS)) { "Reconnect response was never released" }
                val fields = mapOf<String, Any>("step" to (roundStep.get() + 1), "game_restore" to mapOf(
                    "snapshot" to mapOf("players" to List(players) { index ->
                        mapOf("score" to round.getJSONArray("scores").getInt(index), "tilenum" to if (index == 0) 14 else 13)
                    }),
                    "actions" to listOf(
                        action("ActionMJStart", 0, emptyMap(), xor = false),
                        action("ActionNewRound", roundStep.get(), roundFields(), xor = false),
                        action("ActionDealTile", roundStep.get() + 1, drawFields(), xor = false),
                    ),
                ))
                respond(connection, id, "ResSyncGame", fields)
            }
            else -> error("Unexpected request $method")
        }
    }

    private fun roundFields(): Map<String, Any> = mapOf(
        "chang" to listOf("E", "S", "W", "N").indexOf(round.getString("bakaze")),
        "ju" to round.getInt("oya"), "ben" to round.getInt("honba"), "liqibang" to round.getInt("kyotaku"),
        "doras" to listOf(msTile(round.getString("dora_marker"))),
        "scores" to List(players) { round.getJSONArray("scores").getInt(it) },
        "tiles" to round.getJSONArray("tehais").getJSONArray(0).let { tiles ->
            List(tiles.length()) { msTile(tiles.getString(it)) }
        },
    )

    private fun drawFields(): Map<String, Any> = mapOf(
        "seat" to 0, "tile" to msTile(draw.getString("pai")),
        "operation" to mapOf("seat" to 0,
            "operation_list" to (if (players == 3) listOf(mapOf("type" to 1), mapOf("type" to 11))
                else listOf(mapOf("type" to 1)))),
    )

    private fun action(name: String, step: Int, data: Map<String, Any>, xor: Boolean): Map<String, Any> {
        val bytes = encode(name, data)
        return mapOf("name" to name, "step" to step, "data" to if (xor) LiqiDecoder.xor(bytes) else bytes)
    }

    private fun sendAction(name: String, step: Int, data: Map<String, Any>) = sendBinary(checkNotNull(current.get()),
        byteArrayOf(1) + wrapper(".lq.ActionPrototype", encode("ActionPrototype", action(name, step, data, xor = true))))

    private fun respond(connection: Connection, id: Int, type: String, fields: Map<String, Any>) =
        sendBinary(connection, byteArrayOf(3, id.toByte(), (id shr 8).toByte()) + wrapper("", encode(type, fields)))

    private fun request(method: String, id: Int, type: String, fields: Map<String, Any>): String =
        Base64.getEncoder().encodeToString(byteArrayOf(2, id.toByte(), (id shr 8).toByte()) + wrapper(method, encode(type, fields)))

    private fun encode(name: String, fields: Map<String, Any>): ByteArray =
        buildMessage(schema.decode(name, byteArrayOf()).descriptorForType, fields).toByteArray()

    private fun buildMessage(descriptor: Descriptors.Descriptor, fields: Map<String, Any>): DynamicMessage {
        val builder = DynamicMessage.newBuilder(descriptor)
        for ((name, value) in fields) {
            val field = checkNotNull(descriptor.findFieldByName(name)) { "$name is absent from ${descriptor.fullName}" }
            fun converted(value: Any): Any = when (field.javaType) {
                Descriptors.FieldDescriptor.JavaType.BYTE_STRING -> ByteString.copyFrom(value as ByteArray)
                Descriptors.FieldDescriptor.JavaType.MESSAGE -> {
                    @Suppress("UNCHECKED_CAST")
                    buildMessage(field.messageType, value as Map<String, Any>)
                }
                else -> value
            }
            if (field.isRepeated) (value as List<*>).forEach { builder.addRepeatedField(field, converted(checkNotNull(it))) }
            else builder.setField(field, converted(value))
        }
        return builder.build()
    }

    private fun wrapper(name: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        val nameBytes = name.toByteArray()
        output.write(10)
        writeVarint(output, nameBytes.size)
        output.write(nameBytes)
        output.write(18)
        writeVarint(output, payload.size)
        output.write(payload)
        output.toByteArray()
    }

    private fun writeVarint(output: OutputStream, value: Int) {
        var remaining = value
        while (remaining > 127) {
            output.write((remaining and 127) or 128)
            remaining = remaining ushr 7
        }
        output.write(remaining)
    }

    private fun sendBinary(connection: Connection, payload: ByteArray) = sendFrame(connection, 2, payload)

    private fun sendFrame(connection: Connection, opcode: Int, payload: ByteArray) = synchronized(connection) {
        require(payload.size <= 65_535)
        connection.output.write(0x80 or opcode)
        if (payload.size < 126) connection.output.write(payload.size) else {
            connection.output.write(126)
            connection.output.write(payload.size shr 8)
            connection.output.write(payload.size and 255)
        }
        connection.output.write(payload)
        connection.output.flush()
    }

    private fun readLine(input: DataInputStream): String {
        val output = ByteArrayOutputStream()
        while (output.size() < 8192) {
            val value = input.read()
            if (value < 0) throw EOFException()
            if (value == 10) return output.toString("US-ASCII").trimEnd('\r')
            output.write(value)
        }
        error("Oversized HTTP header")
    }

    private fun html(): String {
        val auth = request(".lq.FastTest.authGame", 1, "ReqAuthGame",
            mapOf("account_id" to 12345, "game_uuid" to gameUuid))
        val sync = request(".lq.FastTest.syncGame", 2, "ReqSyncGame", mapOf("step" to 0, "round_id" to "local-round"))
        return """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Local Liqi integration fixture</title>
            <style>html,body{height:100%;margin:0}body{background:#153f37;color:#deecdf;font:18px system-ui}
            main{box-sizing:border-box;min-height:100%;display:grid;place-content:center;text-align:center;padding:60px}
            p{font-size:13px;color:#a4c5b4}</style>
            <script>
            window.liveFixture={ready:true,hookBeforePageScript:typeof window.__akagiCaptureInstalled==='string',
                received:0,connections:0,closed:false};
            const binary=value=>Uint8Array.from(atob(value),c=>c.charCodeAt(0));
            window.openGame=restore=>{
                const socket=new WebSocket('ws://'+location.host+'/socket');
                window.liveSocket=socket;
                window.liveFixture.connections++;
                window.liveFixture.closed=false;
                socket.binaryType='blob';
                socket.addEventListener('open',()=>socket.send(binary('$auth')));
                socket.addEventListener('message',async event=>{
                    const bytes=new Uint8Array(await event.data.arrayBuffer());
                    window.liveFixture.received++;
                    if(restore&&bytes[0]===3&&bytes[1]===1&&bytes[2]===0)socket.send(binary('$sync'));
                });
                socket.addEventListener('close',()=>{window.liveFixture.closed=true;});
            };
            </script></head><body><main><div>$players-player local wire fixture</div>
            <p>Live WebSocket messages · bundled Mortal model · real native advice</p>
            </main></body></html>
        """.trimIndent()
    }

    private fun msTile(tile: String): String {
        val honors = listOf("E", "S", "W", "N", "P", "F", "C")
        val honor = honors.indexOf(tile)
        return when {
            honor >= 0 -> "${honor + 1}z"
            tile.endsWith("r") -> "0${tile[1]}"
            else -> tile
        }
    }

    override fun close() {
        stopped.set(true)
        server.close()
        clients.forEach { runCatching { it.close() } }
        executor.shutdownNow()
        executor.awaitTermination(3, TimeUnit.SECONDS)
    }
}
