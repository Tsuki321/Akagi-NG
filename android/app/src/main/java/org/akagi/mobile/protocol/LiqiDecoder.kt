package org.akagi.mobile.protocol

import com.google.protobuf.ByteString
import org.json.JSONObject
import java.util.Base64

internal class ProtocolException(message: String) : IllegalArgumentException(message)

internal data class LiqiMessage(val kind: Int, val method: String, val data: JSONObject, val id: Int = -1)
internal data class LiqiAction(val name: String, val data: JSONObject, val step: Long) {
    // JSON key order is irrelevant when deciding whether a replay repeats a captured action.
    fun sameAs(other: LiqiAction): Boolean = name == other.name && step == other.step && jsonEquivalent(data, other.data)
}

/** Request identifiers are 16-bit and only have meaning within this websocket. */
internal class LiqiDecoder(private val schema: LiqiSchema) {
    private data class Pending(val method: String, val response: String)
    private val pending = hashMapOf<Int, Pending>()

    fun decode(bytes: ByteArray, outbound: Boolean): LiqiMessage {
        requireProtocol(bytes.isNotEmpty(), "Empty websocket message")
        val kind = bytes[0].toInt() and 255
        requireProtocol(kind in 1..3, "Unknown Liqi frame type")
        requireProtocol(if (outbound) kind == 2 else kind != 2, "Liqi direction does not match frame type")
        val offset = if (kind == 1) 1 else 3
        requireProtocol(bytes.size >= offset, "Truncated Liqi header")
        val id = if (kind == 1) -1 else (bytes[1].toInt() and 255) or ((bytes[2].toInt() and 255) shl 8)
        val wrapper = schema.decode("Wrapper", bytes.copyOfRange(offset, bytes.size))
        val descriptor = wrapper.descriptorForType
        val name = wrapper.getField(descriptor.findFieldByName("name")) as String
        val payload = (wrapper.getField(descriptor.findFieldByName("data")) as ByteString).toByteArray()
        return when (kind) {
            1 -> {
                requireProtocol(name.startsWith(".lq."), "Invalid Liqi notification name")
                val decoded = schema.json(schema.decode(name, payload))
                LiqiMessage(kind, name, if (name == ".lq.ActionPrototype") {
                    val action = action(decoded, useXor = true)
                    JSONObject().put("name", action.name).put("data", action.data).put("step", action.step)
                } else decoded)
            }
            2 -> {
                val rpc = schema.rpc(name)
                requireProtocol(!pending.containsKey(id), "Request identifier reused before its response")
                val decoded = schema.json(schema.decode(rpc.request, payload))
                pending[id] = Pending(name, rpc.response)
                LiqiMessage(kind, name, decoded, id)
            }
            else -> {
                requireProtocol(name.isEmpty(), "Response wrapper has a method name")
                val request = pending.remove(id) ?: throw ProtocolException("Response has no matching request")
                LiqiMessage(kind, request.method, schema.json(schema.decode(request.response, payload)), id)
            }
        }
    }

    fun action(wrapper: JSONObject, useXor: Boolean): LiqiAction {
        val name = wrapper.getString("name")
        val raw = Base64.getDecoder().decode(wrapper.getString("data"))
        val payload = if (useXor) xor(raw) else raw
        return LiqiAction(name, schema.json(schema.decode(name, payload)), wrapper.getLong("step"))
    }

    companion object {
        fun xor(bytes: ByteArray): ByteArray = ByteArray(bytes.size) { index ->
            val mask = ((23 xor bytes.size) + 5 * index + KEYS[index % KEYS.size]) and 255
            ((bytes[index].toInt() and 255) xor mask).toByte()
        }
        private val KEYS = intArrayOf(0x84, 0x5e, 0x4e, 0x42, 0x39, 0xa2, 0x1f, 0x60, 0x1c)
    }
}

internal fun requireProtocol(condition: Boolean, message: String) {
    if (!condition) throw ProtocolException(message)
}

internal fun JSONObject.strings(name: String): List<String> = optJSONArray(name)?.let { array ->
    List(array.length()) { array.getString(it) }
} ?: emptyList()

internal fun JSONObject.ints(name: String): List<Int> = optJSONArray(name)?.let { array ->
    List(array.length()) { array.getInt(it) }
} ?: emptyList()

internal fun jsonEquivalent(left: Any?, right: Any?): Boolean = when {
    left is JSONObject && right is JSONObject -> {
        val keys = left.keys().asSequence().toSet()
        keys == right.keys().asSequence().toSet() && keys.all { jsonEquivalent(left.get(it), right.get(it)) }
    }
    left is org.json.JSONArray && right is org.json.JSONArray ->
        left.length() == right.length() && (0 until left.length()).all { jsonEquivalent(left.get(it), right.get(it)) }
    left is Number && right is Number -> left.toString() == right.toString()
    else -> left == right
}
