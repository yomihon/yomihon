package mihon.data.dictionary

import java.io.Writer

internal class SimpleJsonWriter(
    private val out: Writer,
) {
    private data class Scope(
        val type: Type,
        var elementCount: Int = 0,
    )

    private enum class Type {
        ARRAY,
        OBJECT,
    }

    private val stack = mutableListOf<Scope>()
    private var pendingName: String? = null

    fun beginObject(): SimpleJsonWriter {
        beforeValue()
        out.write("{")
        stack += Scope(Type.OBJECT)
        return this
    }

    fun endObject(): SimpleJsonWriter {
        require(pendingName == null) { "Dangling object name" }
        require(stack.lastOrNull()?.type == Type.OBJECT) { "Not currently in an object" }
        out.write("}")
        stack.removeAt(stack.lastIndex)
        return this
    }

    fun beginArray(): SimpleJsonWriter {
        beforeValue()
        out.write("[")
        stack += Scope(Type.ARRAY)
        return this
    }

    fun endArray(): SimpleJsonWriter {
        require(stack.lastOrNull()?.type == Type.ARRAY) { "Not currently in an array" }
        out.write("]")
        stack.removeAt(stack.lastIndex)
        return this
    }

    fun name(name: String): SimpleJsonWriter {
        val scope = stack.lastOrNull()
        require(scope?.type == Type.OBJECT) { "Names can only be written inside objects" }
        require(pendingName == null) { "Name already pending" }
        pendingName = name
        return this
    }

    fun value(value: String?): SimpleJsonWriter {
        if (value == null) return nullValue()
        beforeValue()
        writeQuoted(value)
        return this
    }

    fun value(value: Long): SimpleJsonWriter {
        beforeValue()
        out.write(value.toString())
        return this
    }

    fun value(value: Boolean): SimpleJsonWriter {
        beforeValue()
        out.write(value.toString())
        return this
    }

    fun value(value: Double): SimpleJsonWriter {
        beforeValue()
        out.write(value.toString())
        return this
    }

    fun nullValue(): SimpleJsonWriter {
        beforeValue()
        out.write("null")
        return this
    }

    fun rawValue(value: String): SimpleJsonWriter {
        beforeValue()
        out.write(value)
        return this
    }

    fun flush() {
        out.flush()
    }

    private fun beforeValue() {
        val scope = stack.lastOrNull()
        when (scope?.type) {
            Type.ARRAY -> {
                if (scope.elementCount > 0) out.write(",")
                scope.elementCount += 1
            }
            Type.OBJECT -> {
                val name = pendingName ?: error("Object value must have a name")
                if (scope.elementCount > 0) out.write(",")
                writeQuoted(name)
                out.write(":")
                scope.elementCount += 1
                pendingName = null
            }
            null -> Unit
        }
    }

    private fun writeQuoted(value: String) {
        out.write("\"")
        value.forEach { char ->
            when (char) {
                '\\' -> out.write("\\\\")
                '"' -> out.write("\\\"")
                '\b' -> out.write("\\b")
                '\u000C' -> out.write("\\f")
                '\n' -> out.write("\\n")
                '\r' -> out.write("\\r")
                '\t' -> out.write("\\t")
                else -> {
                    if (char.code < 0x20) {
                        out.write("\\u%04x".format(char.code))
                    } else {
                        out.write(char.code)
                    }
                }
            }
        }
        out.write("\"")
    }
}
