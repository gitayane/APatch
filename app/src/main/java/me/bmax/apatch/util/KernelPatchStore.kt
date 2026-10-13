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
        // GitHub caps a release page at 100 entries. Keep paging only until we
        // have enough valid releases for this channel, with a hard page limit
        // to avoid excessive requests if one channel has very few releases.
        val result = ArrayList<Release>(MAX_RELEASES_PER_CHANNEL)
        var page = 1

        while (page <= MAX_RELEASE_PAGES && result.size < MAX_RELEASES_PER_CHANNEL) {
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
                if (releases.length() == 0) {
                    return@withContext result.sortedByDescending { release -> release.publishedAt }
                        .take(MAX_RELEASES_PER_CHANNEL)
                }

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

            page++
        }

        result.sortedByDescending { it.publishedAt }.take(MAX_RELEASES_PER_CHANNEL)
    }


