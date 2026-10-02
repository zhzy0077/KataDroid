package io.github.zhzy0077.katadroid

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class NpuRuntimeDirectoryTest {
    @Test fun combinedApkExposesOnlySelectedDispatchAndCompiler() {
        val root = Files.createTempDirectory("npu-vendors").toFile()
        try {
            val native = File(root, "native").apply { mkdirs() }
            val names = listOf("libLiteRtCompilerPlugin_Qualcomm.so", "libLiteRtDispatch_Qualcomm.so",
                "libLiteRtCompilerPlugin_MediaTek.so", "libLiteRtDispatch_MediaTek.so",
                "libQnnHtp.so", "libQnnSystem.so", "libkatadroid.so")
            names.forEach { File(native, it).writeText(it) }
            for (vendor in listOf("Qualcomm", "MediaTek")) {
                val directory = NpuRuntimeDirectory.prepare(File(root, vendor), native,
                    "libLiteRtCompilerPlugin_$vendor.so", "libLiteRtDispatch_$vendor.so", vendor == "Qualcomm")
                val actual = directory.listFiles()!!.map { it.name }.toSet()
                assertEquals(setOf("libLiteRtDispatch_$vendor.so"), actual.filter { it.startsWith("libLiteRtDispatch_") }.toSet())
                assertEquals(setOf("libLiteRtCompilerPlugin_$vendor.so"), actual.filter { it.startsWith("libLiteRtCompilerPlugin_") }.toSet())
                assertEquals(vendor == "Qualcomm", "libQnnHtp.so" in actual)
                actual.forEach { assertEquals(it, File(directory, it).readText()) }
            }
            names.forEach { assertEquals(it, File(native, it).readText()) }
        } finally { root.deleteRecursively() }
    }

    @Test fun apkUpdateRepairsLinksAndRemovesStaleVendorWithoutDeletingPackagedFiles() {
        val root = Files.createTempDirectory("npu-update").toFile()
        try {
            val compiler = "libLiteRtCompilerPlugin_Qualcomm.so"
            val dispatch = "libLiteRtDispatch_Qualcomm.so"
            fun native(version: String) = File(root, version).apply {
                mkdirs(); File(this, compiler).writeText(version); File(this, dispatch).writeText(version)
            }
            val before = native("before")
            val runtime = NpuRuntimeDirectory.prepare(File(root, "runtime"), before, compiler, dispatch, true)
            Files.createSymbolicLink(File(runtime, "libLiteRtDispatch_MediaTek.so").toPath(), File(before, dispatch).toPath())
            val after = native("after")
            repeat(2) { NpuRuntimeDirectory.prepare(runtime, after, compiler, dispatch, true) }
            assertEquals("after", File(runtime, dispatch).readText())
            assertFalse(File(runtime, "libLiteRtDispatch_MediaTek.so").exists())
            assertEquals("before", File(before, dispatch).readText())
            assertEquals("after", File(after, dispatch).readText())
        } finally { root.deleteRecursively() }
    }
}
