package org.akagi.mobile.engine

import android.content.Context
import android.os.Looper
import android.util.AtomicFile
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

data class ModelChoice(val players: Int, val name: String, val custom: Boolean, val notice: String? = null)

internal data class ModelSource(
    val players: Int,
    val name: String,
    val checksum: String,
    val directory: File? = null,
    val notice: String? = null,
) {
    val identity: String get() = "${players}:${directory?.name ?: "bundled"}:$checksum"
    fun choice() = ModelChoice(players, name, directory != null, notice)
}

/** Each mode owns an atomic selection and immutable model files in app-private storage. */
class ModelRepository(context: Context, private val root: File = File(context.filesDir, "mortal-models")) {
    private val assets = context.applicationContext.assets
    private val sources = mutableMapOf<Int, ModelSource>()
    private val pending = mutableSetOf<File>()

    class PreparedModel internal constructor(internal val source: ModelSource, private val owner: ModelRepository) : Closeable {
        internal var committed = false
        internal var closed = false
        val choice: ModelChoice get() = source.choice()
        override fun close() {
            synchronized(owner) {
                if (!closed) {
                    closed = true
                    owner.pending.remove(source.directory)
                    if (!committed) source.directory?.deleteRecursively()
                }
            }
        }
    }

    @Synchronized
    fun choice(players: Int): ModelChoice = source(players).choice()

    @Synchronized
    internal fun source(players: Int): ModelSource = sources.getOrPut(players) {
        val bundled = bundled(players)
        val selection = selectionFile(players)
        if (!selection.baseFile.exists() && !File(selection.baseFile.path + ".bak").exists()) bundled
        else try {
            val id = JSONObject(String(selection.readFully(), Charsets.UTF_8)).optString("id")
            if (id.isEmpty()) bundled else {
                require(UUID.fromString(id).toString() == id) { "Invalid saved model selection" }
                imported(players, File(slot(players), id))
            }
        } catch (_: Exception) {
            bundled.copy(notice = "The saved model could not be read. Using the bundled model for this mode.")
        }
    }

    internal fun checkNativeCompatibility(players: Int) { bundled(players) }

    private fun bundled(players: Int): ModelSource {
        require(players == 4 || players == 3) { "Choose four-player or three-player games" }
        val manifest = assets.open("models/mortal${players}p.json").bufferedReader().use { JSONObject(it.readText()) }
        validateDescription(manifest, players)
        require(manifest.getString("file") == "mortal${players}p.onnx") { "Unexpected bundled model file" }
        return ModelSource(players, "Bundled Mortal", manifest.getString("sha256"))
    }

    private fun imported(players: Int, directory: File): ModelSource {
        val manifest = readJson(File(directory, "manifest.json"), 64L * 1024)
        require(manifest.getString("format") == "akagi-mortal-model" && manifest.getInt("format_version") == 1) {
            "Choose an Akagi model bundle produced by the checkpoint converter"
        }
        validateDescription(manifest, players)
        require(manifest.getString("encoder") == encoder(players)) { "This model uses a different observation encoder" }
        require(manifest.getString("file") == "model.onnx") { "The bundle must contain model.onnx" }
        val name = manifest.getString("name").trim()
        require(name.isNotEmpty() && name.length <= 100 && name.none { it.isISOControl() }) { "Invalid model name" }
        val graph = File(directory, "model.onnx")
        require(graph.isFile && graph.length() in 1..MAX_MODEL_BYTES) { "Missing model or model exceeds 512 MiB" }
        val references = File(directory, "reference.json")
        require(references.isFile && references.length() in 1..MAX_REFERENCE_BYTES) { "Missing model reference scores" }
        require(sha256(references.readBytes()) == manifest.getString("reference_sha256")) { "Model reference checksum does not match" }
        return ModelSource(players, name, manifest.getString("sha256"), directory)
    }

    private fun validateDescription(manifest: JSONObject, players: Int) {
        require(manifest.getInt("players") == players) {
            "This is a ${manifest.optInt("players")}-player model. Choose a $players-player model for this slot."
        }
        require(manifest.getInt("version") == 4 && manifest.getString("score_semantics") == "legal_masked_dueling_q") {
            "This app supports Mortal v4 DQN checkpoints"
        }
        require(manifest.getBoolean("native_compatible")) { "The model has not passed native encoder validation" }
        require(manifest.getJSONArray("observation_shape").longs().contentEquals(longArrayOf(1, channels(players).toLong(), 34))) {
            "The model observation dimensions do not match this mode"
        }
        require(manifest.getJSONArray("mask_shape").longs().contentEquals(longArrayOf(1, actions(players).toLong()))) {
            "The model legal-action dimensions do not match this mode"
        }
        require(manifest.getString("sha256").matches(Regex("[0-9a-f]{64}"))) { "Invalid model checksum" }
    }

    /** Copy and run the candidate before touching either selection. May run beside live inference. */
    fun prepareImport(players: Int, input: InputStream): PreparedModel {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Model import must run on a worker thread" }
        require(players == 4 || players == 3)
        val directory = synchronized(this) {
            val parent = slot(players)
            check(parent.isDirectory || parent.mkdirs()) { "Cannot create model storage" }
            File(parent, UUID.randomUUID().toString()).also {
                check(it.mkdir()) { "Cannot create model import directory" }
                pending.add(it)
            }
        }
        try {
            val seen = mutableSetOf<String>()
            ZipInputStream(input.buffered()).use { archive ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val entry = archive.nextEntry ?: break
                    val limit = when (entry.name) {
                        "model.onnx" -> MAX_MODEL_BYTES
                        "reference.json" -> MAX_REFERENCE_BYTES
                        "manifest.json", "LICENSE.txt" -> 64L * 1024
                        else -> throw IllegalArgumentException("Choose a converted .akagimodel bundle. Convert .pth checkpoints with the Android model workflow first.")
                    }
                    require(!entry.isDirectory && seen.add(entry.name)) { "Duplicate or invalid bundle entry" }
                    require(entry.size <= limit) { "The model bundle entry is too large" }
                    File(directory, entry.name).outputStream().use { output ->
                        var length = 0L
                        while (true) {
                            check(!Thread.currentThread().isInterrupted) { "Model import cancelled" }
                            val count = archive.read(buffer)
                            if (count < 0) break
                            length += count
                            require(length <= limit) { "The model bundle entry is too large" }
                            output.write(buffer, 0, count)
                        }
                    }
                    archive.closeEntry()
                }
            }
            require(seen.containsAll(listOf("model.onnx", "manifest.json", "reference.json"))) {
                "Choose a converted .akagimodel bundle containing a model and its validation data"
            }
            val candidate = imported(players, directory)
            ModelRuntime.open(this, candidate).use { validateReferences(candidate, it) }
            return PreparedModel(candidate, this)
        } catch (failure: Throwable) {
            synchronized(this) { pending.remove(directory) }
            directory.deleteRecursively()
            throw failure
        }
    }

    /** The coordinator calls this on its ordered worker after validation finishes. */
    @Synchronized
    fun apply(prepared: PreparedModel): ModelChoice {
        check(!prepared.closed && !prepared.committed && prepared.source.directory in pending) { "Model import is no longer available" }
        val source = prepared.source
        writeSelection(source.players, source.directory!!.name)
        sources[source.players] = source
        prepared.committed = true
        return source.choice()
    }

    @Synchronized
    fun useBundled(players: Int): ModelChoice {
        val source = bundled(players)
        writeSelection(players, "")
        sources[players] = source
        return source.choice()
    }

    /** Call after closing the affected inference session; retain any in-flight import. */
    @Synchronized
    fun prune(players: Int) {
        val retained = source(players).directory
        slot(players).listFiles()?.filter { it.isDirectory && it != retained && it !in pending }?.forEach { directory ->
            if (runCatching { UUID.fromString(directory.name).toString() == directory.name }.getOrDefault(false)) {
                directory.deleteRecursively()
            }
        }
    }

    private fun slot(players: Int) = File(root, "${players}p")
    private fun selectionFile(players: Int) = AtomicFile(File(slot(players), "active.json"))

    private fun writeSelection(players: Int, id: String) {
        check(slot(players).isDirectory || slot(players).mkdirs()) { "Cannot write model selection" }
        val atomic = selectionFile(players)
        val output = atomic.startWrite()
        try {
            output.write(JSONObject().put("id", id).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
        }
    }

    internal fun graph(source: ModelSource): ByteBuffer {
        val buffer = if (source.directory == null) {
            val bytes = assets.open("models/mortal${source.players}p.onnx").use { it.readBytes() }
            ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }
        } else {
            FileInputStream(File(source.directory, "model.onnx")).channel.use { channel ->
                require(channel.size() in 1..MAX_MODEL_BYTES) { "Invalid model size" }
                channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            }
        }
        val hash = MessageDigest.getInstance("SHA-256").apply { update(buffer.duplicate()) }.digest()
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        require(hash == source.checksum) { "Model checksum does not match" }
        return buffer
    }

    /** Custom weights use their own exported scores, evaluated on this app's trusted observations. */
    internal fun validateReferences(source: ModelSource, runtime: ModelRuntime): Int {
        val trusted = assets.open("models/reference.json").bufferedReader().use { JSONObject(it.readText()) }
            .getJSONArray("cases").objects().filter { it.getInt("players") == source.players }.associateBy { it.getString("name") }
        val cases = if (source.directory == null) trusted.values.toList()
        else readJson(File(source.directory, "reference.json"), MAX_REFERENCE_BYTES).getJSONArray("cases").objects()
        require(trusted.size >= 2 && cases.size == trusted.size && cases.map { it.getString("name") }.toSet() == trusted.keys) {
            "The model reference cases do not match this app. Convert the checkpoint with the current workflow."
        }
        for (case in cases) {
            check(!Thread.currentThread().isInterrupted) { "Model check cancelled" }
            val reference = trusted.getValue(case.getString("name"))
            val bits = reference.getLong("mask_bits")
            require(case.getInt("players") == source.players && case.getLong("mask_bits") == bits &&
                case.getString("observation") == reference.getString("observation")) { "Model reference observation does not match the encoder" }
            val raw = assets.open("models/" + reference.getString("observation")).use { it.readBytes() }
            require(raw.size == channels(source.players) * 34 * 4) { "Invalid reference observation" }
            val floats = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val scores = runtime.infer(FloatArray(floats.remaining()).also { floats.get(it) }, bits)
            val expected = case.getJSONArray("q_values")
            require(expected.length() == scores.size) { "Invalid reference score count" }
            var best = -1
            for (index in scores.indices) {
                if (bits and (1L shl index) == 0L) {
                    require(expected.isNull(index)) { "Reference contains an illegal action score" }
                    continue
                }
                val value = expected.getDouble(index).toFloat()
                check(value.isFinite() && abs(scores[index] - value) <= 0.0003f + 0.0002f * abs(value)) {
                    "Model parity failed for ${case.getString("name")}, action $index"
                }
                if (best < 0 || scores[index] > scores[best]) best = index
            }
            check(best == case.getInt("argmax")) { "Model decision differs from its checkpoint reference" }
        }
        return cases.size
    }

    private fun readJson(file: File, limit: Long): JSONObject {
        require(file.isFile && file.length() in 1..limit) { "Missing or oversized model metadata" }
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    private fun JSONArray.longs() = LongArray(length()) { getLong(it) }
    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

    companion object {
        const val MAX_MODEL_BYTES = 512L * 1024 * 1024
        private const val MAX_REFERENCE_BYTES = 1024L * 1024
        internal fun channels(players: Int) = if (players == 4) 1012 else 775
        internal fun actions(players: Int) = if (players == 4) 46 else 44
        internal fun encoder(players: Int) = if (players == 4) "akagi-mortal-4p-v4" else "akagi-mortal-3p-v4-legacy"
        internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
