package io.github.zhzy0077.katadroid

import java.io.File
import java.io.IOException
import java.nio.file.Files

/** LiteRT picks the first dispatch library it finds, so expose only one vendor. */
internal object NpuRuntimeDirectory {
    fun prepare(directory: File, nativeDirectory: File, compiler: String, dispatch: String,
                includeQnn: Boolean): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create runtime directory")
        val libraries = nativeDirectory.listFiles()?.filter { file ->
            file.isFile && (file.name == compiler || file.name == dispatch ||
                (includeQnn && file.name.startsWith("libQnn") && file.name.endsWith(".so")))
        } ?: throw IOException("Cannot list packaged runtime libraries")
        val names = libraries.map { it.name }.toSet()
        if (compiler !in names || dispatch !in names) throw IOException("Selected runtime libraries are absent")
        directory.listFiles()?.filter { it.name !in names }?.forEach { Files.delete(it.toPath()) }
        for (library in libraries) {
            val link = File(directory, library.name).toPath()
            val target = library.toPath().toAbsolutePath()
            if (Files.isSymbolicLink(link) && Files.readSymbolicLink(link) == target) continue
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, target)
        }
        return directory
    }
}
