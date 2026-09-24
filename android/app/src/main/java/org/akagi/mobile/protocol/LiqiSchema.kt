package org.akagi.mobile.protocol

import com.google.protobuf.ByteString
import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** The complete, bundled protobuf.js schema, shared by all sockets in one browser. */
internal class LiqiSchema(schemaJson: String) {
    data class Rpc(val request: String, val response: String)

    private val definitions = JSONObject(schemaJson).getJSONObject("nested")
        .getJSONObject("lq").getJSONObject("nested")
    private val types = linkedMapOf<String, Boolean>()
    private val messages = hashMapOf<String, Descriptors.Descriptor>()
    private val rpcs = hashMapOf<String, Rpc>()

    init {
        register(definitions, "lq")
        val file = DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName("liqi.proto").setPackage("lq").setSyntax("proto3")
        definitions.keys().forEach { name ->
            val definition = definitions.getJSONObject(name)
            when {
                definition.has("fields") -> file.addMessageType(message(name, definition, "lq.$name"))
                definition.has("values") -> file.addEnumType(buildEnum(name, definition))
                definition.has("methods") -> {
                    val methods = definition.getJSONObject("methods")
                    methods.keys().forEach { method ->
                        val rpc = methods.getJSONObject(method)
                        rpcs[".lq.$name.$method"] = Rpc(rpc.getString("requestType"), rpc.getString("responseType"))
                    }
                }
            }
        }
        val descriptor = Descriptors.FileDescriptor.buildFrom(file.build(), emptyArray())
        fun index(descriptor: Descriptors.Descriptor) {
            messages[descriptor.fullName] = descriptor
            descriptor.nestedTypes.forEach(::index)
        }
        descriptor.messageTypes.forEach(::index)
    }

    fun rpc(name: String): Rpc = rpcs[name] ?: throw ProtocolException("Unknown Liqi method $name")

    fun decode(name: String, bytes: ByteArray): DynamicMessage {
        val fullName = name.removePrefix(".").let { if (it.startsWith("lq.")) it else "lq.$it" }
        val descriptor = messages[fullName] ?: throw ProtocolException("Unknown Liqi message $name")
        return DynamicMessage.parseFrom(descriptor, bytes)
    }

    /** Match protobuf MessageToDict(always_print_fields_with_no_presence=True). */
    fun json(message: DynamicMessage): JSONObject {
        val result = JSONObject()
        for (field in message.descriptorForType.fields) {
            if (field.isRepeated) {
                val values = JSONArray()
                @Suppress("UNCHECKED_CAST")
                for (value in message.getField(field) as List<Any>) values.put(jsonValue(field, value))
                result.put(field.jsonName, values)
            } else if (!field.hasPresence() || message.hasField(field)) {
                result.put(field.jsonName, jsonValue(field, message.getField(field)))
            }
        }
        return result
    }

    private fun jsonValue(field: Descriptors.FieldDescriptor, value: Any): Any = when (field.type) {
        Descriptors.FieldDescriptor.Type.MESSAGE -> json(value as DynamicMessage)
        Descriptors.FieldDescriptor.Type.BYTES -> Base64.getEncoder().encodeToString((value as ByteString).toByteArray())
        Descriptors.FieldDescriptor.Type.ENUM -> (value as Descriptors.EnumValueDescriptor).name
        Descriptors.FieldDescriptor.Type.UINT32, Descriptors.FieldDescriptor.Type.FIXED32 ->
            Integer.toUnsignedLong(value as Int)
        Descriptors.FieldDescriptor.Type.UINT64, Descriptors.FieldDescriptor.Type.FIXED64 ->
            java.lang.Long.toUnsignedString(value as Long)
        Descriptors.FieldDescriptor.Type.INT64, Descriptors.FieldDescriptor.Type.SINT64,
        Descriptors.FieldDescriptor.Type.SFIXED64 -> value.toString()
        else -> value
    }

    private fun register(nested: JSONObject, prefix: String) {
        nested.keys().forEach { name ->
            val definition = nested.getJSONObject(name)
            val fullName = "$prefix.$name"
            when {
                definition.has("fields") -> {
                    types[fullName] = false
                    definition.optJSONObject("nested")?.let { register(it, fullName) }
                }
                definition.has("values") -> types[fullName] = true
            }
        }
    }

    private fun message(name: String, definition: JSONObject, scope: String): DescriptorProtos.DescriptorProto {
        val result = DescriptorProtos.DescriptorProto.newBuilder().setName(name)
        val fields = definition.getJSONObject("fields")
        fields.keys().forEach { fieldName ->
            val info = fields.getJSONObject(fieldName)
            val field = DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName(fieldName).setNumber(info.getInt("id"))
                .setLabel(if (info.optString("rule") == "repeated") {
                    DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED
                } else {
                    DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL
                })
            val type = info.getString("type")
            val scalar = SCALARS[type]
            if (scalar != null) {
                field.type = scalar
            } else {
                val resolved = resolve(type, scope)
                field.typeName = ".$resolved"
                field.type = if (types[resolved] == true) {
                    DescriptorProtos.FieldDescriptorProto.Type.TYPE_ENUM
                } else {
                    DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE
                }
            }
            result.addField(field)
        }
        definition.optJSONObject("nested")?.let { nested ->
            nested.keys().forEach { child ->
                val info = nested.getJSONObject(child)
                when {
                    info.has("fields") -> result.addNestedType(message(child, info, "$scope.$child"))
                    info.has("values") -> result.addEnumType(buildEnum(child, info))
                }
            }
        }
        return result.build()
    }

    private fun resolve(type: String, scope: String): String {
        val absolute = type.removePrefix(".")
        if (types.containsKey(absolute)) return absolute
        var parent = scope
        while (parent.isNotEmpty()) {
            val candidate = "$parent.$type"
            if (types.containsKey(candidate)) return candidate
            parent = parent.substringBeforeLast('.', "")
        }
        val matches = types.keys.filter { it.endsWith(".$type") }
        if (matches.size == 1) return matches.single()
        throw ProtocolException("Unresolved protobuf type $type in $scope")
    }

    private fun buildEnum(name: String, definition: JSONObject): DescriptorProtos.EnumDescriptorProto {
        val result = DescriptorProtos.EnumDescriptorProto.newBuilder().setName(name)
        val values = definition.getJSONObject("values")
        val numbers = hashSetOf<Int>()
        var aliases = false
        // Proto3 requires the first enum value to be zero. JSONObject does not promise key order.
        val names = values.keys().asSequence().toList().sortedBy { values.getInt(it) != 0 }
        for (key in names) {
            val number = values.getInt(key)
            aliases = !numbers.add(number) || aliases
            result.addValue(DescriptorProtos.EnumValueDescriptorProto.newBuilder().setName(key).setNumber(number))
        }
        if (aliases) result.setOptions(DescriptorProtos.EnumOptions.newBuilder().setAllowAlias(true))
        return result.build()
    }

    companion object {
        private val SCALARS = mapOf(
            "double" to DescriptorProtos.FieldDescriptorProto.Type.TYPE_DOUBLE,
            "float" to DescriptorProtos.FieldDescriptorProto.Type.TYPE_FLOAT,
            "int64" to DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT64,
            "uint64" to DescriptorProtos.FieldDescriptorProto.Type.TYPE_UINT64,
            "int32" to DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32,
            "fixed64" to DescriptorProtos.FieldDescriptorProto.Type.TYPE_FIXED64,
            "fixed32" to DescriptorProtos.FieldDescriptorProto.Type.TYPE_FIXED32,
            "bool" to DescriptorProtos.FieldDescriptorProto.Type.TYPE_BOOL,
            "string" to DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING,
            "bytes" to DescriptorProtos.FieldDescriptorProto.Type.TYPE_BYTES,
            "uint32" to DescriptorProtos.FieldDescriptorProto.Type.TYPE_UINT32,
            "sfixed32" to DescriptorProtos.FieldDescriptorProto.Type.TYPE_SFIXED32,
            "sfixed64" to DescriptorProtos.FieldDescriptorProto.Type.TYPE_SFIXED64,
            "sint32" to DescriptorProtos.FieldDescriptorProto.Type.TYPE_SINT32,
            "sint64" to DescriptorProtos.FieldDescriptorProto.Type.TYPE_SINT64,
        )
    }
}
