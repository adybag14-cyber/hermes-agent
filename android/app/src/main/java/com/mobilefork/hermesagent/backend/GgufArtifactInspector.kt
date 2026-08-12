package com.mobilefork.hermesagent.backend

import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/** Lightweight, bounded GGUF header/metadata validation before llama-server is spawned. */
object GgufArtifactInspector {
    internal data class Inspection(
        val valid: Boolean,
        val version: Int = 0,
        val tensorCount: Long = 0L,
        val metadataCount: Long = 0L,
        val architecture: String = "",
        val chatTemplatePresent: Boolean = false,
        val splitCount: Long = 1L,
        val error: String = "",
        val warnings: List<String> = emptyList(),
    ) {
        val summary: String
            get() = if (valid) {
                "GGUF v$version, architecture=$architecture, tensors=$tensorCount, metadata=$metadataCount, chat-template=${if (chatTemplatePresent) "present" else "missing"}"
            } else {
                error
            }
    }

    internal fun inspect(file: File): Inspection {
        if (!file.isFile) return Inspection(valid = false, error = "GGUF model is missing: ${file.absolutePath}")
        if (file.length() < MIN_GGUF_BYTES) {
            return Inspection(valid = false, error = "${file.name} is too small to be a GGUF model (${file.length()} bytes)")
        }
        return try {
            RandomAccessFile(file, "r").use { random ->
                val reader = LittleEndianReader(random, file.length())
                val magic = reader.readBytes(4)
                if (!magic.contentEquals(GGUF_MAGIC)) {
                    return Inspection(
                        valid = false,
                        error = "${file.name} does not start with the GGUF magic header",
                    )
                }
                val version = reader.readUInt32().toInt()
                if (version !in SUPPORTED_GGUF_VERSIONS) {
                    return Inspection(
                        valid = false,
                        version = version,
                        error = "${file.name} uses unsupported GGUF version $version; this Hermes llama.cpp runtime accepts GGUF v2-v3",
                    )
                }
                val tensorCount = reader.readUInt64Checked("tensor count")
                val metadataCount = reader.readUInt64Checked("metadata count")
                if (tensorCount <= 0L || tensorCount > MAX_TENSOR_COUNT) {
                    return Inspection(
                        valid = false,
                        version = version,
                        tensorCount = tensorCount,
                        metadataCount = metadataCount,
                        error = "${file.name} has an invalid GGUF tensor count: $tensorCount",
                    )
                }
                if (metadataCount <= 0L || metadataCount > MAX_METADATA_COUNT) {
                    return Inspection(
                        valid = false,
                        version = version,
                        tensorCount = tensorCount,
                        metadataCount = metadataCount,
                        error = "${file.name} has an invalid GGUF metadata count: $metadataCount",
                    )
                }

                var architecture = ""
                var chatTemplatePresent = false
                var splitCount = 1L
                repeat(metadataCount.toInt()) {
                    val key = reader.readString(MAX_KEY_BYTES).lowercase(Locale.US)
                    val valueType = reader.readUInt32().toInt()
                    when (key) {
                        "general.architecture" -> architecture = reader.readValueAsString(valueType).trim()
                        "tokenizer.chat_template" -> {
                            chatTemplatePresent = reader.readValueAsString(valueType).isNotBlank()
                        }
                        "split.count" -> splitCount = reader.readValueAsLong(valueType)
                        else -> reader.skipValue(valueType)
                    }
                }

                if (architecture.isBlank()) {
                    return Inspection(
                        valid = false,
                        version = version,
                        tensorCount = tensorCount,
                        metadataCount = metadataCount,
                        chatTemplatePresent = chatTemplatePresent,
                        splitCount = splitCount,
                        error = "${file.name} is missing required GGUF metadata key general.architecture",
                    )
                }
                if (splitCount > 1L) {
                    return Inspection(
                        valid = false,
                        version = version,
                        tensorCount = tensorCount,
                        metadataCount = metadataCount,
                        architecture = architecture,
                        chatTemplatePresent = chatTemplatePresent,
                        splitCount = splitCount,
                        error = "${file.name} is one shard of a $splitCount-file split GGUF; Hermes currently requires one complete GGUF file",
                    )
                }
                if (!chatTemplatePresent) {
                    return Inspection(
                        valid = false,
                        version = version,
                        tensorCount = tensorCount,
                        metadataCount = metadataCount,
                        architecture = architecture,
                        splitCount = splitCount,
                        error = "${file.name} has no tokenizer.chat_template metadata, so OpenAI-compatible chat completion cannot be verified safely",
                    )
                }
                Inspection(
                    valid = true,
                    version = version,
                    tensorCount = tensorCount,
                    metadataCount = metadataCount,
                    architecture = architecture,
                    chatTemplatePresent = true,
                    splitCount = splitCount,
                )
            }
        } catch (error: Throwable) {
            Inspection(
                valid = false,
                error = "Unable to validate ${file.name} as GGUF: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    private class LittleEndianReader(
        private val file: RandomAccessFile,
        private val fileLength: Long,
    ) {
        fun readBytes(count: Int): ByteArray {
            requireRemaining(count.toLong())
            return ByteArray(count).also(file::readFully)
        }

        fun readUInt32(): Long {
            val bytes = readBytes(4)
            return (bytes[0].toLong() and 0xffL) or
                ((bytes[1].toLong() and 0xffL) shl 8) or
                ((bytes[2].toLong() and 0xffL) shl 16) or
                ((bytes[3].toLong() and 0xffL) shl 24)
        }

        fun readUInt64Checked(label: String): Long {
            val bytes = readBytes(8)
            if ((bytes[7].toInt() and 0x80) != 0) {
                throw IllegalArgumentException("GGUF $label exceeds signed 64-bit bounds")
            }
            var value = 0L
            for (index in 7 downTo 0) {
                value = (value shl 8) or (bytes[index].toLong() and 0xffL)
            }
            return value
        }

        fun readString(maxBytes: Long = MAX_STRING_BYTES): String {
            val byteCount = readUInt64Checked("string length")
            if (byteCount > maxBytes || byteCount > Int.MAX_VALUE) {
                throw IllegalArgumentException("GGUF string length $byteCount exceeds the inspection bound $maxBytes")
            }
            return readBytes(byteCount.toInt()).toString(Charsets.UTF_8)
        }

        fun readValueAsString(type: Int): String {
            return when (type) {
                TYPE_STRING -> readString()
                TYPE_ARRAY -> {
                    val elementType = readUInt32().toInt()
                    val count = boundedArrayCount()
                    if (elementType != TYPE_STRING) {
                        repeat(count.toInt()) { skipValue(elementType) }
                        ""
                    } else {
                        buildString {
                            repeat(count.toInt()) { index ->
                                val value = readString()
                                if (index == 0 || isEmpty()) append(value)
                            }
                        }
                    }
                }
                else -> {
                    val numeric = readScalarLong(type)
                    numeric?.toString().orEmpty()
                }
            }
        }

        fun readValueAsLong(type: Int): Long {
            return readScalarLong(type)
                ?: throw IllegalArgumentException("GGUF metadata value type $type is not an integer")
        }

        fun skipValue(type: Int) {
            when (type) {
                TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> skipBytes(1L)
                TYPE_UINT16, TYPE_INT16 -> skipBytes(2L)
                TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> skipBytes(4L)
                TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> skipBytes(8L)
                TYPE_STRING -> skipString()
                TYPE_ARRAY -> {
                    val elementType = readUInt32().toInt()
                    val count = boundedArrayCount()
                    val fixedSize = fixedValueSize(elementType)
                    if (fixedSize > 0L) {
                        if (count > Long.MAX_VALUE / fixedSize) {
                            throw IllegalArgumentException("GGUF array byte size overflow")
                        }
                        skipBytes(count * fixedSize)
                    } else {
                        repeat(count.toInt()) { skipValue(elementType) }
                    }
                }
                else -> throw IllegalArgumentException("Unknown GGUF metadata value type $type")
            }
        }

        private fun readScalarLong(type: Int): Long? {
            val bytes = when (type) {
                TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> readBytes(1)
                TYPE_UINT16, TYPE_INT16 -> readBytes(2)
                TYPE_UINT32, TYPE_INT32 -> readBytes(4)
                TYPE_UINT64, TYPE_INT64 -> readBytes(8)
                else -> return null
            }
            var value = 0L
            for (index in bytes.indices.reversed()) {
                value = (value shl 8) or (bytes[index].toLong() and 0xffL)
            }
            return value
        }

        private fun boundedArrayCount(): Long {
            val count = readUInt64Checked("array count")
            if (count > MAX_ARRAY_COUNT || count > Int.MAX_VALUE) {
                throw IllegalArgumentException("GGUF array count $count exceeds the inspection bound $MAX_ARRAY_COUNT")
            }
            return count
        }

        private fun skipString() {
            val byteCount = readUInt64Checked("string length")
            if (byteCount > MAX_STRING_BYTES) {
                throw IllegalArgumentException("GGUF string length $byteCount exceeds the inspection bound $MAX_STRING_BYTES")
            }
            skipBytes(byteCount)
        }

        private fun skipBytes(count: Long) {
            requireRemaining(count)
            file.seek(file.filePointer + count)
        }

        private fun requireRemaining(count: Long) {
            if (count < 0L || file.filePointer > fileLength - count) {
                throw EOFException("GGUF metadata ends unexpectedly at byte ${file.filePointer}")
            }
        }

        private fun fixedValueSize(type: Int): Long = when (type) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> 1L
            TYPE_UINT16, TYPE_INT16 -> 2L
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> 4L
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> 8L
            else -> 0L
        }
    }

    private val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
    private val SUPPORTED_GGUF_VERSIONS = 2..3
    private const val MIN_GGUF_BYTES = 32L
    private const val MAX_TENSOR_COUNT = 10_000_000L
    private const val MAX_METADATA_COUNT = 1_000_000L
    private const val MAX_ARRAY_COUNT = 10_000_000L
    private const val MAX_KEY_BYTES = 16_384L
    private const val MAX_STRING_BYTES = 64L * 1024L * 1024L

    private const val TYPE_UINT8 = 0
    private const val TYPE_INT8 = 1
    private const val TYPE_UINT16 = 2
    private const val TYPE_INT16 = 3
    private const val TYPE_UINT32 = 4
    private const val TYPE_INT32 = 5
    private const val TYPE_FLOAT32 = 6
    private const val TYPE_BOOL = 7
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9
    private const val TYPE_UINT64 = 10
    private const val TYPE_INT64 = 11
    private const val TYPE_FLOAT64 = 12
}
