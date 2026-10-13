package me.bmax.apatch.util

import android.util.Log
import me.bmax.apatch.apApp
import org.ini4j.Ini
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object KernelPatchStore {

    private const val TAG = "KernelPatchStore"
    private const val API_URL =
        "https://api.github.com/repos/gitayane/KernelPatch/releases"
    private const val RELEASES_PER_PAGE = 100
    private const val MAX_RELEASE_PAGES = 5
    private const val MAX_RELEASES_PER_CHANNEL = 3
    private const val STORE_DIR = "kernelpatch"
    private const val ACTIVE_DIR = "active"
    private const val META_FILE = "metadata.json"

    // Prevent concurrent selections from cleaning or replacing each other's
    // staging/activation directories.
    private val activationMutex = Mutex()

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
        // GitHub's release endpoint isn't guaranteed to be ordered by published_at.
        // Scan bounded pages, then sort locally so channel filtering doesn't let a
        // busy Test channel hide older-page Stable releases (or vice versa).
        val result = ArrayList<Release>()
        var page = 1
        var pageSize: Int

        while (page <= MAX_RELEASE_PAGES) {
            val response = apApp.okhttpClient.newCall(
                okhttp3.Request.Builder()
                    .url("$API_URL?per_page=$RELEASES_PER_PAGE&page=$page")
                    .header("Accept", "application/vnd.github+json")
                    .build()
            ).execute()

            response.use {
                if (!it.isSuccessful) {
                    throw IllegalStateException("GitHub API returned HTTP ${it.code}")
                }

                val body = it.body?.string().orEmpty()
                val releases = JSONArray(body)
                pageSize = releases.length()

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
            }

            // A short page means there are no more releases. Otherwise continue
            // within MAX_RELEASE_PAGES, even if three matches have been found,
            // because the API page order does not reliably reflect published_at.
            if (pageSize < RELEASES_PER_PAGE) break
            page++
        }

        result.sortedByDescending { it.publishedAt }.take(MAX_RELEASES_PER_CHANNEL)
    }

    suspend fun activate(release: Release, channel: Channel): Active =
        withContext(Dispatchers.IO) {
            activationMutex.withLock {
                ensureDirectory(rootDir, "KernelPatch store")

                // A timestamp is not unique: two fast taps can start in the same
                // millisecond and one activation may delete the other's directory.
                val staging = File(rootDir, ".staging-${UUID.randomUUID()}")
                ensureDirectory(staging, "KernelPatch staging")

                // Keep the current active KP until the complete new release has been
                // downloaded, verified, inspected, and staged successfully.
                val previous = File(rootDir, ".active-old-${UUID.randomUUID()}")

                try {
                    val kpimg = File(staging, "kpimg")
                    val kptools = File(staging, "kptools")

                    downloadAndVerify(release.kpimg, kpimg)
                    downloadAndVerify(release.kptools, kptools)

                    if (!kpimg.setExecutable(true, false)) {
                        throw IOException("Failed to mark downloaded kpimg executable: ${kpimg.absolutePath}")
                    }
                    if (!kptools.setExecutable(true, false)) {
                        throw IOException("Failed to mark downloaded kptools executable: ${kptools.absolutePath}")
                    }

                    val info = readKpimgInfo(kpimg, kptools)
                    val active = Active(
                        channel = channel,
                        tag = release.tag,
                        name = release.name,
                        kpVersion = info.version,
                        compileTime = info.compileTime,
                        kpimgSha256 = release.kpimg.sha256,
                        kptoolsSha256 = release.kptools.sha256,
                    )

                    File(staging, META_FILE).writeText(
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

                    var movedPrevious = false
                    if (activeDir.exists()) {
                        if (!activeDir.renameTo(previous)) {
                            throw IOException("Failed to preserve the currently active KernelPatch")
                        }
                        movedPrevious = true
                    }

                    if (!staging.renameTo(activeDir)) {
                        if (movedPrevious && !previous.renameTo(activeDir)) {
                            throw IOException(
                                "Failed to activate KernelPatch and failed to restore the previous KP"
                            )
                        }
                        throw IOException("Failed to activate KernelPatch assets")
                    }

                    previous.deleteRecursively()
                    Log.i(TAG, "activated ${active.tag} (${active.compileTime})")
                    active
                } finally {
                    staging.deleteRecursively()
                    // If restoring the previous active KP failed, leave the backup
                    // directory in place for recovery instead of deleting it here.
                }
            }
        }

    private fun ensureDirectory(directory: File, purpose: String) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Failed to create $purpose directory: ${directory.absolutePath}")
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
        val parent = destination.parentFile
            ?: throw IOException("No parent directory for download target: ${destination.absolutePath}")
        ensureDirectory(parent, "download target for ${asset.name}")

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

            val target = try {
                destination.outputStream().buffered()
            } catch (e: IOException) {
                throw IOException(
                    "Cannot create download target ${destination.absolutePath}; " +
                        "parent exists=${parent.isDirectory}",
                    e
                )
            }

            target.use { output ->
                body.byteStream().use { input ->
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

            val actualSize = destination.length()
            if (asset.size >= 0L && actualSize != asset.size) {
                destination.delete()
                throw SecurityException(
                    "Size mismatch for ${asset.name}: expected ${asset.size}, got $actualSize"
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
