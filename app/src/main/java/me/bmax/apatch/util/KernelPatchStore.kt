package me.bmax.apatch.util

import android.content.Context
import android.util.Log
import me.bmax.apatch.apApp
import org.ini4j.Ini
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.nio.file.Files
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

    // Use Android's named private directory rather than a manually-created child
    // of filesDir. This also avoids any stale non-directory at files/kernelpatch.
    private val rootDir: File
        get() = getStoreRoot()

    private val legacyRootDir: File
        get() = File(apApp.filesDir, STORE_DIR)

    @Volatile
    private var legacyStoreMigrationChecked = false
    private val legacyStoreMigrationLock = Any()

    private val activeDir: File
        get() = File(rootDir, ACTIVE_DIR)

    private val metadataFile: File
        get() = File(activeDir, META_FILE)

    fun activeKpimgFile(): File? =
        File(activeDir, "kpimg").takeIf { it.isFile && it.length() > 0L }

    fun activeKptoolsFile(): File? =
        File(activeDir, "kptools").takeIf { it.isFile && it.length() > 0L }

    fun active(): Active? = runCatching {
        val file = metadataFile
        if (!file.isFile) {
            null
        } else {
            val json = JSONObject(file.readText())
            Active(
                channel = Channel.valueOf(json.getString("channel")),
                tag = json.getString("tag"),
                name = json.optString("name", json.getString("tag")),
                kpVersion = json.optString("kpVersion", ""),
                compileTime = json.optString("compileTime", ""),
                kpimgSha256 = json.getString("kpimgSha256"),
                kptoolsSha256 = json.getString("kptoolsSha256"),
            )
        }
    }.getOrNull()

    fun activeCompileTime(): String? =
        active()?.compileTime?.takeIf { it.isNotBlank() }

    suspend fun fetch(channel: Channel): List<Release> = withContext(Dispatchers.IO) {
        // GitHub's release endpoint isn't guaranteed to be ordered by published_at.
        // Scan bounded pages, then sort locally so channel filtering doesn't let a
        // busy Test channel hide older-page Stable releases (or vice versa).
        val result = ArrayList<Release>()
        var page = 1
        var pageSize = 0

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

    private fun getStoreRoot(): File {
        val directory = try {
            apApp.getDir(STORE_DIR, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            throw IOException(
                "Android failed to initialize KernelPatch store under " +
                    "${apApp.dataDir.absolutePath}; filesDir=${apApp.filesDir.absolutePath}; " +
                    "cause=${e.javaClass.simpleName}: ${e.message}",
                e
            )
        }

        ensureDirectory(directory, "KernelPatch store")
        migrateLegacyStore(directory)
        return directory
    }

    private fun migrateLegacyStore(directory: File) {
        if (legacyStoreMigrationChecked) return

        synchronized(legacyStoreMigrationLock) {
            if (legacyStoreMigrationChecked) return

            val legacy = legacyRootDir
            if (legacy.absolutePath != directory.absolutePath && legacy.exists()) {
                if (!legacy.isDirectory) {
                    // Do not delete an unexpected file: the new app-private directory
                    // is independent, and the legacy path may be useful for diagnosis.
                    Log.w(TAG, "Legacy KernelPatch path is not a directory: ${legacy.absolutePath}")
                } else {
                    legacy.listFiles().orEmpty().forEach { source ->
                        val destination = File(directory, source.name)
                        if (destination.exists()) return@forEach

                        if (!source.renameTo(destination)) {
                            try {
                                source.copyRecursively(destination, overwrite = false)
                                source.deleteRecursively()
                            } catch (e: Exception) {
                                throw IOException(
                                    "Failed to migrate KernelPatch data from " +
                                        "${source.absolutePath} to ${destination.absolutePath}: ${e.message}",
                                    e
                                )
                            }
                        }
                    }
                    if (legacy.listFiles().isNullOrEmpty()) {
                        legacy.delete()
                    }
                }
            }

            legacyStoreMigrationChecked = true
        }
    }

    private fun ensureDirectory(directory: File, purpose: String) {
        val parent = directory.parentFile

        // If a directory was left with stale permissions by an earlier install,
        // repair only the app-owned permission bits before creating a child.
        if (parent?.isDirectory == true) {
            restoreOwnerDirectoryAccess(parent)
            if (!parent.canWrite() || !parent.canExecute()) {
                repairStoreOwnership(parent)
            }
            if (!parent.canWrite() || !parent.canExecute()) {
                throw IOException(
                    "Cannot create $purpose because its parent is not writable/searchable; " +
                        describePath(parent)
                )
            }
        }

        try {
            Files.createDirectories(directory.toPath())
        } catch (e: Exception) {
            throw IOException(
                "Failed to create $purpose directory; target=${describePath(directory)}; " +
                    "parent=${parent?.let { describePath(it) } ?: "<none>"}; " +
                    "cause=${e.javaClass.simpleName}: ${e.message}",
                e
            )
        }

        if (!directory.isDirectory) {
            throw IOException(
                "Failed to create $purpose directory; path exists but is not a directory; " +
                    describePath(directory)
            )
        }

        restoreOwnerDirectoryAccess(directory)
        if (!directory.canWrite() || !directory.canExecute()) {
            repairStoreOwnership(directory)
        }
        if (!directory.canWrite() || !directory.canExecute()) {
            throw IOException(
                "Created $purpose directory but it is not writable/searchable; " +
                    describePath(directory)
            )
        }
    }

    /**
     * A previous root-context operation or data restore can leave this app-private
     * store owned by root. chmod from the app UID cannot repair that ownership.
     * Restrict root-shell repair to the exact getDir() root or one of its direct
     * children; never accept an arbitrary path from callers.
     */
    private fun repairStoreOwnership(directory: File) {
        val expectedRoot = runCatching {
            apApp.getDir(STORE_DIR, Context.MODE_PRIVATE).canonicalFile
        }.getOrNull() ?: return
        val target = runCatching { directory.canonicalFile }.getOrNull() ?: return
        if (target != expectedRoot && target.parentFile != expectedRoot) {
            Log.w(TAG, "Skip ownership repair outside KernelPatch store: ${directory.absolutePath}")
            return
        }
        if (!target.exists() || !target.isDirectory) return

        val uid = apApp.applicationInfo.uid
        val quotedPath = "'" + target.path.replace("'", "'\\''") + "'"
        val command =
            "chown -R $uid:$uid $quotedPath && chmod 700 $quotedPath && " +
                "(restorecon -R $quotedPath >/dev/null 2>&1 || true)"
        val result = runCatching { rootShellForResult(command) }.getOrElse {
            Log.e(TAG, "Root ownership repair failed for ${target.path}", it)
            return
        }

        Log.w(
            TAG,
            "Root ownership repair: path=${target.path}, uid=$uid, success=${result.isSuccess}, " +
                "out=${result.out.joinToString(" ").take(300)}, " +
                "err=${result.err.joinToString(" ").take(500)}"
        )
    }

    private fun restoreOwnerDirectoryAccess(directory: File) {
        if (!directory.isDirectory) return

        if (!directory.canWrite()) {
            val changed = directory.setWritable(true, true)
            Log.w(TAG, "restore owner-write: path=${directory.absolutePath}, changed=$changed, " +
                "canWrite=${directory.canWrite()}")
        }

        if (!directory.canExecute()) {
            val changed = directory.setExecutable(true, true)
            Log.w(TAG, "restore owner-execute: path=${directory.absolutePath}, changed=$changed, " +
                "canExecute=${directory.canExecute()}")
        }
    }

    private fun describePath(file: File): String {
        val owner = runCatching { Files.getOwner(file.toPath()).name }
            .getOrDefault("<unavailable>")
        val permissions = runCatching {
            Files.getPosixFilePermissions(file.toPath())
                .map { it.name }
                .sorted()
                .joinToString(",")
        }.getOrDefault("<unavailable>")

        return "path=${file.absolutePath}, exists=${file.exists()}, " +
            "isDirectory=${file.isDirectory}, canRead=${file.canRead()}, " +
            "canWrite=${file.canWrite()}, canExecute=${file.canExecute()}, " +
            "owner=$owner, posixPermissions=$permissions"
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
