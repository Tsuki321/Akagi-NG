package org.akagi.mobile.engine

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.Closeable
import java.nio.FloatBuffer

/** CPU inference shared by live play and pre-install validation. */
internal class ModelRuntime private constructor(private val session: OrtSession, val source: ModelSource) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()

    fun infer(observation: FloatArray, bits: Long): FloatArray {
        val channels = ModelRepository.channels(source.players)
        val actions = ModelRepository.actions(source.players)
        require(observation.size == channels * 34 && observation.all { it.isFinite() }) { "Invalid native observation" }
        require(bits != 0L && bits ushr actions == 0L) { "Invalid native action mask" }
        val mask = BooleanArray(actions) { bits and (1L shl it) != 0L }
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(observation), longArrayOf(1, channels.toLong(), 34)).use { obsTensor ->
            OnnxTensor.createTensor(environment, arrayOf(mask)).use { maskTensor ->
                session.run(mapOf("obs" to obsTensor, "mask" to maskTensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val batch = result.get("q_values").orElseThrow().value as Array<FloatArray>
                    val scores = batch.single()
                    check(scores.size == actions && scores.indices.all { i ->
                        if (mask[i]) scores[i].isFinite() else scores[i] == Float.NEGATIVE_INFINITY
                    }) { "Local model returned invalid legal scores" }
                    return scores
                }
            }
        }
    }

    override fun close() = session.close()

    companion object {
        fun open(repository: ModelRepository, source: ModelSource): ModelRuntime {
            val loaded = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setInterOpNumThreads(1)
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                // A direct buffer avoids copying large replacements onto the Java heap.
                // It also requires a self-contained graph, without external tensor files.
                OrtEnvironment.getEnvironment().createSession(repository.graph(source), options)
            }
            try {
                val channels = ModelRepository.channels(source.players).toLong()
                val actions = ModelRepository.actions(source.players).toLong()
                check(loaded.inputNames == setOf("obs", "mask") && loaded.outputNames == setOf("q_values")) { "Unexpected model inputs or outputs" }
                val obs = loaded.inputInfo.getValue("obs").info as TensorInfo
                val mask = loaded.inputInfo.getValue("mask").info as TensorInfo
                val output = loaded.outputInfo.getValue("q_values").info as TensorInfo
                check(obs.type == OnnxJavaType.FLOAT && obs.shape.contentEquals(longArrayOf(1, channels, 34))) { "Observation dimensions do not match the encoder" }
                check(mask.type == OnnxJavaType.BOOL && mask.shape.contentEquals(longArrayOf(1, actions))) { "Legal-mask dimensions do not match the encoder" }
                check(output.type == OnnxJavaType.FLOAT && output.shape.contentEquals(longArrayOf(1, actions))) { "Unexpected score output" }
                return ModelRuntime(loaded, source)
            } catch (failure: Throwable) {
                loaded.close()
                throw failure
            }
        }
    }
}
