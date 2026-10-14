package io.github.zhzy0077.katadroid

import io.github.zhzy0077.katadroid.engine.ModelPackages
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelPackageExtractionTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> entries.forEach { (name, data) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
        } }
    }.toByteArray()
    private val entries get() = listOf("model.tflite", "descriptor.bin", "manifest.json", "fixtures.json")
        .associateWith { byteArrayOf(1, 2, 3) }

    @Test fun onlyFourExactTopLevelEntriesAreExtracted() {
        val folder = temporary.newFolder()
        ModelPackages.extract(zip(entries).inputStream(), folder)
        assertEquals(entries.keys, folder.listFiles()!!.map { it.name }.toSet())
        folder.listFiles()!!.forEach { assertArrayEquals(byteArrayOf(1, 2, 3), it.readBytes()) }
    }

    @Test fun traversalExtraEntriesEmptyEntriesAndIncompletePackagesAreRejected() {
        for (invalid in listOf(entries + ("../escape" to byteArrayOf(1)),
                entries + ("extra.txt" to byteArrayOf(1)), entries - "descriptor.bin",
                entries + ("model.tflite" to byteArrayOf()))) {
            assertThrows(Exception::class.java) { ModelPackages.extract(zip(invalid).inputStream(), temporary.newFolder()) }
        }
    }

    @Test fun expansionLimitsAndCancellationAreEnforced() {
        val tooLarge = entries + ("manifest.json" to ByteArray(65537))
        assertThrows(IllegalArgumentException::class.java) { ModelPackages.extract(zip(tooLarge).inputStream(), temporary.newFolder()) }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            ModelPackages.extract(zip(entries).inputStream(), temporary.newFolder()) { throw java.util.concurrent.CancellationException() }
        }
    }
}
