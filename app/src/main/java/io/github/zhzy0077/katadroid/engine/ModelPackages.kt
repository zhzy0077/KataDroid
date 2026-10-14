package io.github.zhzy0077.katadroid.engine

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import io.github.zhzy0077.katadroid.R
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Optional, self-contained portable packages. No model weights are downloaded implicitly. */
object ModelPackages {
    private val limits = mapOf("manifest.json" to 65536L, "fixtures.json" to 32L * 1024 * 1024,
        "model.tflite" to 512L * 1024 * 1024, "descriptor.bin" to 512L * 1024 * 1024)
    private fun root(context: Context) = File(context.noBackupFilesDir, "optional-models").apply { mkdirs() }

    internal fun hash(input: InputStream): String = input.use {
        val digest = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(65536)
        while (true) {
            val count = it.read(block)
            if (count < 0) break
            digest.update(block, 0, count)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
    }

    internal fun read(directory: File): KataGoModel {
        val manifest = File(directory, "manifest.json")
        require(manifest.length() in 1..limits.getValue("manifest.json"))
        val json = JSONObject(manifest.readText())
        require(json.getInt("schemaVersion") == 1) { "Unsupported package version" }
        val contract = ModelContract.fromId(json.getString("contract"))
        require(json.getInt("modelVersion") == contract.modelVersion)
        val source = json.getString("sourceName")
        val name = json.getString("descriptorName")
        require(source.matches(Regex("[A-Za-z0-9_.-]{1,120}")) && name.length in 1..256)
        fun sha(key: String) = json.getString(key).also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        val graphHash = sha("tfliteSha256")
        val descriptorHash = sha("descriptorSha256")
        val fixturesHash = sha("fixturesSha256")
        val identity = hash("$graphHash:$descriptorHash:$fixturesHash:${contract.id}:$name:$source".byteInputStream())
        return KataGoModel("imported-$identity", R.string.model_imported_title, R.string.model_imported_description,
            source, name, graphHash, descriptorHash, contract, directory, fixturesHash)
    }

    fun installed(context: Context): List<KataGoModel> = root(context).listFiles().orEmpty()
        .filter { it.isDirectory && it.name.matches(Regex("imported-[0-9a-f]{64}")) }
        .mapNotNull { directory -> runCatching { read(directory).also { require(it.id == directory.name) } }.getOrNull() }
        .sortedBy { it.sourceName }

    internal fun extract(input: InputStream, directory: File, cancelled: () -> Unit = {}) {
        val seen = mutableSetOf<String>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                cancelled()
                val entry = zip.nextEntry ?: break
                val limit = limits[entry.name] ?: error("Unexpected package entry")
                require(!entry.isDirectory && seen.add(entry.name)) { "Duplicate package entry" }
                var count = 0L
                File(directory, entry.name).outputStream().use { output ->
                    val block = ByteArray(65536)
                    while (true) {
                        cancelled()
                        val size = zip.read(block)
                        if (size < 0) break
                        count += size
                        total += size
                        require(count <= limit && total <= 1024L * 1024 * 1024) { "Package exceeds size limit" }
                        output.write(block, 0, size)
                    }
                }
                require(count > 0) { "Empty package entry" }
                zip.closeEntry()
            }
        }
        require(seen == limits.keys) { "Incomplete model package" }
    }

    suspend fun install(context: Context, input: InputStream): KataGoModel = withContext(Dispatchers.IO) {
        val root = root(context)
        val staging = File(root, ".pending-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            extract(input, staging) { coroutineContext.ensureActive() }
            val spec = read(staging)
            // Validate CPU numerics and graph boundaries before any catalog publication.
            LiteRtNetwork.open(context, Accelerator.CPU, spec).use { runtime ->
                ModelValidation.verify(runtime, ModelValidation.fixtures(context, spec)) { coroutineContext.ensureActive() }
            }
            coroutineContext.ensureActive()
            // Official parser validates descriptor architecture and head channel counts.
            KataGoSession.open(context, Accelerator.CPU, spec).use { }
            coroutineContext.ensureActive()
            val destination = File(root, spec.id)
            if (!destination.exists()) check(staging.renameTo(destination)) { "Unable to publish model package" }
            val installed = read(destination)
            require(installed.copy(directory = staging) == spec) { "Installed package identity mismatch" }
            require(hash(installed.openGraph(context)) == spec.sha256 &&
                hash(installed.openDescriptor(context)) == spec.descriptorSha256 &&
                hash(installed.openFixtures(context)) == spec.fixturesSha256) { "Installed package checksum mismatch" }
            KataGoModel.refresh(context)
            installed
        } finally { staging.deleteRecursively() }
    }
}
