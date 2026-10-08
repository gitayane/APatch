package me.bmax.apatch.util

import android.util.Log
import me.bmax.apatch.apApp
import org.ini4j.Ini
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object KernelPatchStore {

    private const val TAG = "KernelPatchStore"
    private const val API_URL =
        "https://api.github.com/repos/gitayane/KernelPatch/releases?per_page=30"
    private const val STORE_DIR = "kernelpatch"
    private const val ACTIVE_DIR = "active"
    private const val META_FILE = "metadata.json"

    enum class Channel {
        STABLE,
        TEST
    }

    data class Asset(
        val name: String,
        val url: String,
        val sha256: String,
        val size: Long
    )

    data class Release(
        val tag: String,
        val name: String,
        val prerelease: Boolean,
        val publishedAt: String,
        val kpimg: Asset,
        val kptools: Asset
    )

    data class Active(
        val channel: Channel,
        val tag: String,
        val name: String,
        val kpVersion: String,
        val compileTime: String,
        val kpimgSha256: String,
        val kptoolsSha256: String
    )

    private val rootDir: File
        get() = File(apApp.filesDir, STORE_DIR)

    private val activeDir: File
        get() = File(rootDir, ACTIVE_DIR)

    private val metadataFile: File
        get() = File(activeDir, META_FILE)

    fun activeKpimgFile(): File? =
        File(activeDir, "kpimg").takeIf { it.isFile && it.length() > 0L }

    fun activeKptoolsFile(): File? =
        File(activeDir, "kptools").takeIf { it.isFile && it.length() > 0L }

    fun active(): Active? {
        if (!metadataFile.isFile) return null
        return runCatching {
            val json = JSONObject(metadataFile.readText())
            Active(
                channel = Channel.valueOf(json.getString("channel")),
                tag = json.getString("tag"),
                name = json.optString("name", json.getString("tag")),
                kpVersion = json.optString("kpVersion", ""),
                compileTime = json.optString("compileTime", ""),
                kpimgSha256 = json.getString("kpimgSha256"),
                kptoolsSha256 = json.getString("kptoolsSha256"),
            )
        }.getOrNull()
    }

    fun activeCompileTime(): String? =
        active()?.compileTime?.takeIf { it.isNotBlank() }

    suspend fun fetch(channel: Channel): List<Release> = withContext(Dispatchers.IO) {
        val response = apApp.okhttpClient.newCall(
            okhttp3.Request.Builder()
                .url(API_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
        ).execute()

        response.use {
            if (!it.isSuccessful) {
                throw IllegalStateException("GitHub API returned HTTP ${it.code}")
            }

            val body = it.body?.string().orEmpty()
            val releases = JSONArray(body)
            val result = ArrayList<Release>(releases.length())

            for (i in 0 until releases.length()) {
                val json = releases.getJSONObject(i)
                if (json.optBoolean("draft", false)) continue

                val prerelease = json.optBoolean("prerelease", false)
                if (channel == Channel.STABLE && prerelease) continue
                if (channel == Channel.TEST && !prerelease) continue

                val assets = parseAssets(json.optJSONArray("assets") ?: JSONArray())
                val kpimg = assets["kpimg-android"] ?: continue
                val kptools = assets["kptools-android"] ?: continue

                result += Release(
                    tag = json.optString("tag_name"),
                    name = json.optString("name").ifBlank { json.optString("tag_name") },
                    prerelease = prerelease,
                    publishedAt = json.optString("published_at"),
                    kpimg = kpimg,
                    kptools = kptools,
                )
            }

            result.sortedByDescending { it.publishedAt }
        }
    }

    suspend fun activate(release: Release, channel: Channel): Active =
        withContext(Dispatchers.IO) {
            rootDir.mkdirs()
            val staging = File(rootDir, ".staging-${System.currentTimeMillis()}")
            staging.deleteRecursively()
            staging.mkdirs()

            try {
                val kpimg = File(staging, "kpimg")
                val kptools = File(staging, "kptools")

                downloadAndVerify(release.kpimg, kpimg)
                downloadAndVerify(release.kptools, kptools)

                kpimg.setExecutable(true, false)
                kptools.setExecutable(true, false)

                val info = readKpimgInfo(kpimg, kptools)
                val next = File(rootDir, "active-next")
                next.deleteRecursively()
                next.mkdirs()
                kpimg.copyTo(File(next, "kpimg"), overwrite = true)
                kptools.copyTo(File(next, "kptools"), overwrite = true)

                val active = Active(
                    channel = channel,
                    tag = release.tag,
                    name = release.name,
                    kpVersion = info.version,
                    compileTime = info.compileTime,
                    kpimgSha256 = release.kpimg.sha256,
                    kptoolsSha256 = release.kptools.sha256,
                )

                File(next, META_FILE).writeText(
                    JSONObject()
                        .put("channel", active.channel.name)
                        .put("tag", active.tag)
                        .put("name", active.name)
                        .put("kpVersion", active.kpVersion)
                        .put("compileTime", active.compileTime)
                        .put("kpimgSha256", active.kpimgSha256)
                        .put("kptoolsSha256", active.kptoolsSha256)
                        .toString(2)
                )

                activeDir.deleteRecursively()
                if (!next.renameTo(activeDir)) {
                    throw IllegalStateException("Failed to activate KernelPatch assets")
                }

                Log.i(TAG, "activated ${active.tag} (${active.compileTime})")
                active
            } finally {
                staging.deleteRecursively()
            }
        }

    fun clearActive() {
        activeDir.deleteRecursively()
    }

    private fun parseAssets(array: JSONArray): Map<String, Asset> {
        val result = HashMap<String, Asset>()
        for (i in 0 until array.length()) {
            val json = array.getJSONObject(i)
            val name = json.optString("name")
            val digest = json.optString("digest")
            val url = json.optString("browser_download_url")
            if (name.isBlank() || url.isBlank() || !digest.startsWith("sha256:")) continue
            result[name] = Asset(
                name = name,
                url = url,
                sha256 = digest.removePrefix("sha256:"),
                size = json.optLong("size", -1L),
            )
        }
        return result
    }

    private fun downloadAndVerify(asset: Asset, destination: File) {
        val request = okhttp3.Request.Builder()
            .url(asset.url)
            .header("Accept", "application/octet-stream")
            .build()

        apApp.okhttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Download ${asset.name} failed: HTTP ${response.code}")
            }

            val body = response.body ?: throw IllegalStateException("Empty ${asset.name}")
            val digest = MessageDigest.getInstance("SHA-256")

            body.byteStream().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(asset.sha256, ignoreCase = true)) {
                destination.delete()
                throw SecurityException(
                    "SHA-256 mismatch for ${asset.name}: expected ${asset.sha256}, got $actual"
                )
            }

            if (asset.size >= 0L && destination.length() != asset.size) {
                destination.delete()
                throw SecurityException(
                    "Size mismatch for ${asset.name}: expected ${asset.size}, got ${destination.length()}"
                )
            }
        }
    }

    private data class KpimgInfo(
        val version: String,
        val compileTime: String
    )

    private fun readKpimgInfo(kpimg: File, kptools: File): KpimgInfo {
        val result = rootShellForResult(
            "\"${kptools.absolutePath}\" -l -k \"${kpimg.absolutePath}\""
        )
        if (!result.isSuccess) {
            throw IllegalStateException(
                "Failed to inspect kpimg: ${result.err.joinToString("\n")}"
            )
        }

        val ini = Ini(StringReader(result.out.joinToString("\n")))
        val section = ini["kpimg"]
            ?: throw IllegalStateException("Downloaded kpimg has no [kpimg] section")

        return KpimgInfo(
            version = section["version"]?.toString().orEmpty(),
            compileTime = section["compile_time"]?.toString().orEmpty()
        )
    }
}
