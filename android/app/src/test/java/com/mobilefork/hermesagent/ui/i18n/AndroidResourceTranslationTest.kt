package com.mobilefork.hermesagent.ui.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AndroidResourceTranslationTest {
    @Test
    fun everySupportedAndroidResourceLocaleCoversTheBaseStringContract() {
        val resourceRoot = locateResourceRoot()
        val baseNames = stringNames(File(resourceRoot, "values/strings.xml"))
        listOf("values-zh", "values-es", "values-de", "values-pt", "values-fr").forEach { qualifier ->
            val localized = File(resourceRoot, "$qualifier/strings.xml")
            assertTrue("Missing $localized", localized.isFile)
            assertEquals("Resource names differ for $qualifier", baseNames, stringNames(localized))
        }
    }

    private fun locateResourceRoot(): File {
        val candidates = listOf(
            File("src/main/res"),
            File("android/app/src/main/res"),
        )
        return candidates.firstOrNull { File(it, "values/strings.xml").isFile }
            ?: error("Could not locate Android resources from ${File(".").absolutePath}")
    }

    private fun stringNames(file: File): Set<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return buildSet {
            repeat(nodes.length) { index ->
                val name = nodes.item(index).attributes?.getNamedItem("name")?.nodeValue.orEmpty()
                if (name.isNotBlank()) add(name)
            }
        }
    }
}
