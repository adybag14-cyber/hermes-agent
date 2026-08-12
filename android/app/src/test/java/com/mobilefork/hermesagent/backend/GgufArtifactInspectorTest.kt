package com.mobilefork.hermesagent.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory

class GgufArtifactInspectorTest {
    @Test
    fun acceptsV3GgufWithArchitectureAndChatTemplate() {
        val file = writeGguf(
            metadata = listOf(
                stringMetadata("general.architecture", "qwen35"),
                stringMetadata("tokenizer.chat_template", "{{ messages }}"),
            ),
        )

        val result = GgufArtifactInspector.inspect(file)

        assertTrue(result.error, result.valid)
        assertEquals(3, result.version)
        assertEquals("qwen35", result.architecture)
        assertTrue(result.chatTemplatePresent)
        assertTrue(result.summary, result.summary.contains("GGUF v3"))
    }

    @Test
    fun rejectsExtensionOnlyFileWithoutGgufMagic() {
        val file = tempFile("broken.gguf", ByteArray(64) { 1 })

        val result = GgufArtifactInspector.inspect(file)

        assertFalse(result.valid)
        assertTrue(result.error, result.error.contains("GGUF magic header"))
    }

    @Test
    fun rejectsChatModelWithoutEmbeddedChatTemplate() {
        val file = writeGguf(metadata = listOf(stringMetadata("general.architecture", "llama")))

        val result = GgufArtifactInspector.inspect(file)

        assertFalse(result.valid)
        assertTrue(result.error, result.error.contains("tokenizer.chat_template"))
    }

    @Test
    fun rejectsIncompleteSplitGguf() {
        val file = writeGguf(
            metadata = listOf(
                stringMetadata("general.architecture", "qwen35"),
                stringMetadata("tokenizer.chat_template", "{{ messages }}"),
                uint32Metadata("split.count", 2),
            ),
        )

        val result = GgufArtifactInspector.inspect(file)

        assertFalse(result.valid)
        assertTrue(result.error, result.error.contains("one shard of a 2-file split GGUF"))
    }

    private fun writeGguf(metadata: List<ByteArray>): File {
        val output = ByteArrayOutputStream()
        output.write("GGUF".toByteArray())
        output.write(u32(3))
        output.write(u64(1))
        output.write(u64(metadata.size.toLong()))
        metadata.forEach(output::write)
        return tempFile("model.gguf", output.toByteArray())
    }

    private fun stringMetadata(key: String, value: String): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(string(key))
        output.write(u32(8))
        output.write(string(value))
        return output.toByteArray()
    }

    private fun uint32Metadata(key: String, value: Int): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(string(key))
        output.write(u32(4))
        output.write(u32(value))
        return output.toByteArray()
    }

    private fun string(value: String): ByteArray = u64(value.toByteArray().size.toLong()) + value.toByteArray()

    private fun u32(value: Int): ByteArray = ByteArray(4) { index -> (value ushr (index * 8)).toByte() }

    private fun u64(value: Long): ByteArray = ByteArray(8) { index -> (value ushr (index * 8)).toByte() }

    private fun tempFile(name: String, bytes: ByteArray): File {
        val directory = createTempDirectory("hermes-gguf-test-").toFile().apply { deleteOnExit() }
        return File(directory, name).apply {
            writeBytes(bytes)
            deleteOnExit()
        }
    }
}
