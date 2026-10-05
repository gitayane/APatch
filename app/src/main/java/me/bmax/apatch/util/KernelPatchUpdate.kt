package me.bmax.apatch.util

import android.util.Log
import androidx.core.content.edit
import me.bmax.apatch.BuildConfig
import me.bmax.apatch.apApp
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties

data class KernelPatchReleaseInfo(
    val tag: String,
    val name: String,
    val changelog: String,
    val kpimgUrl: String,
    val kpimgDigest: String?,
    val kptoolsUrl: String?,
    val kptoolsDigest: String?,
)

object KernelPatchUpdate {
    private const val TAG = "KernelPatchUpdate"
    private const val REPOSITORY = "gitayane/KernelPatch"
    private const val LATEST_URL = "https://api.github.com/repos/${REPOSITORY}/releases/latest"

    private const val PREF_APPLIED_TAG = "kernelpatch_applied_release"
    private const val META_TAG = "tag"
    private const val META_KPTOOLS = "kptools"

    private val cacheDir: File
        get() = File(apApp.filesDir, "kernelpatch")

    private val metadataFile: File
        get() = File(cacheDir, "release.properties")

    private val cachedKpimgFile: File
        get() = File(cacheDir, "kpimg")

    private val cachedKptoolsFile: File
        get() = File(cacheDir, "kptools")

    fun bundledReleaseTag(): String = BuildConfig.kernelPatchReleaseTag

    fun appliedReleaseTag(): String? =
        apApp.sharedPreferences.getString(PREF_APPLIED_TAG, null)?.takeIf { it.isNotBlank() }

    fun markInstalled(tag: String) {
        apApp.sharedPreferences.edit { putString(PREF_APPLIED_TAG, tag) }
        Log.i(TAG, "KernelPatch release marked installed: $tag")
    }

    fun cachedReleaseTag(): String? {
        if (!metadataFile.isFile || !cachedKpimgFile.isFile) return null
        return runCatching {
            Properties().also { props ->
                FileInputStream(metadataFile).use { props.load(it) }
            }[META_TAG]
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun cachedKpimg(): File? =
        cachedReleaseTag()?.let { if (cachedKpimgFile.isFile) cachedKpimgFile else null }

    fun cachedKptools(): File? {
        if (cachedReleaseTag() == null) return null
        return runCatching {
            val props = Properties().also { p ->
                FileInputStream(metadataFile).use { p.load(it) }
            }
            if (props.getProperty(META_KPTOOLS) == "true" && cachedKptoolsFile.isFile) {
                cachedKptoolsFile
            } else {
                null
            }
        }.getOrNull()
    }

    fun currentPatchReleaseTag(): String =
        cachedReleaseTag() ?: bundledReleaseTag()

    fun latestRelease(): KernelPatchReleaseInfo? {
        return runCatching {
            val request = okhttp3.Request.Builder()
                .url(LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .build()

            apApp.okhttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GitHub latest release request failed: HTTP ${response.code}")
                    return@runCatching null
                }

                val body = response.body?.string() ?: return@runCatching null
                val json = JSONObject(body)

                if (json.optBoolean("draft", false) || json.optBoolean("prerelease", false)) {
                    return@runCatching null
                }

                val tag = json.optString("tag_name").trim()
                if (tag.isEmpty()) return@runCatching null

                var kpimgUrl: String? = null
                var kpimgDigest: String? = null
                var kptoolsUrl: String? = null
                var kptoolsDigest: String? = null

                val assets = json.optJSONArray("assets") ?: return@runCatching null
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    when (asset.optString("name")) {
                        "kpimg-android" -> {
                            kpimgUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                            kpimgDigest = asset.optString("digest").takeIf { it.isNotBlank() }
                        }
                        "kptools-android" -> {
                            kptoolsUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                            kptoolsDigest = asset.optString("digest").takeIf { it.isNotBlank() }
                        }
                    }
                }

                kpimgUrl ?: return@runCatching null

                KernelPatchReleaseInfo(
                    tag = tag,
                    name = json.optString("name", tag),
                    changelog = json.optString("body"),
                    kpimgUrl = kpimgUrl,
                    kpimgDigest = kpimgDigest,
                    kptoolsUrl = kptoolsUrl,
                    kptoolsDigest = kptoolsDigest,
                )
            }
        }.onFailure {
            Log.w(TAG, "latest KernelPatch query failed", it)
        }.getOrNull()
    }

    fun needsUpdate(latest: KernelPatchReleaseInfo): Boolean {
        val current = appliedReleaseTag() ?: bundledReleaseTag()
        return current != latest.tag
    }

    @Synchronized
    fun downloadRelease(release: KernelPatchReleaseInfo): Result<Unit> {
        return runCatching {
            cacheDir.mkdirs()

            val kpimgTmp = File(cacheDir, "kpimg.tmp")
            val kptoolsTmp = File(cacheDir, "kptools.tmp")
            val metaTmp = File(cacheDir, "release.properties.tmp")

            kpimgTmp.delete()
            kptoolsTmp.delete()
            metaTmp.delete()

            downloadVerified(release.kpimgUrl, kpimgTmp, release.kpimgDigest)
            if (release.kptoolsUrl != null) {
                downloadVerified(release.kptoolsUrl, kptoolsTmp, release.kptoolsDigest)
            }

            check(cachedKpimgFile.delete() || !cachedKpimgFile.exists()) {
                "Cannot remove previous cached kpimg"
            }
            check(kpimgTmp.renameTo(cachedKpimgFile)) { "Cannot replace cached kpimg" }

            val hasKptools = release.kptoolsUrl != null
            if (hasKptools) {
                check(cachedKptoolsFile.delete() || !cachedKptoolsFile.exists()) {
                    "Cannot remove previous cached kptools"
                }
                check(kptoolsTmp.renameTo(cachedKptoolsFile)) { "Cannot replace cached kptools" }
            } else {
                cachedKptoolsFile.delete()
            }

            val props = Properties().apply {
                setProperty(META_TAG, release.tag)
                setProperty(META_KPTOOLS, hasKptools.toString())
            }
            FileOutputStream(metaTmp).use { props.store(it, "KernelPatch cache") }
            check(metadataFile.delete() || !metadataFile.exists()) {
                "Cannot remove previous KernelPatch metadata"
            }
            check(metaTmp.renameTo(metadataFile)) { "Cannot replace KernelPatch metadata" }

            Log.i(TAG, "Cached KernelPatch release ${release.tag}")
        }.onFailure {
            Log.e(TAG, "Failed to cache KernelPatch ${release.tag}", it)
        }
    }

    fun ensureLatestCached(): Result<KernelPatchReleaseInfo> {
        val latest = latestRelease()
            ?: return Result.failure(IllegalStateException("Cannot query the latest KernelPatch release"))

        if (cachedReleaseTag() != latest.tag) {
            downloadRelease(latest).getOrThrow()
        }

        return Result.success(latest)
    }

    private fun downloadVerified(url: String, destination: File, expectedDigest: String?) {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .build()

        apApp.okhttpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} while downloading $url" }
            val body = response.body ?: error("Empty response body for $url")
            val digest = MessageDigest.getInstance("SHA-256")

            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            val expected = expectedDigest
                ?.removePrefix("sha256:")
                ?.trim()
                ?.lowercase()

            check(expected == null || expected == actual) {
                "SHA-256 mismatch for $url: expected=$expected actual=$actual"
            }
        }
    }
}
