package com.exo.musicplayer.desktop.update

import com.exo.musicplayer.data.update.Updates
import org.json.JSONArray

/** One file attached to a release. */
data class ReleaseAsset(val name: String, val url: String, val size: Long)

/** The newest release, with what it carries for an in-place update. */
data class UpdateRelease(
    val version: String,
    val page: String,
    val assets: List<ReleaseAsset>
) {
    val legacy: Updates.Release get() = Updates.Release(version, page)

    fun asset(name: String): ReleaseAsset? = assets.firstOrNull { it.name == name }

    /** `Lucense-X.Y.Z-<platform>-manifest.json`; other systems' files are ignored. */
    fun manifestFor(platform: String): ReleaseAsset? = asset("Lucense-$version-$platform-manifest.json")

    companion object {
        private val TAG = Regex("v?([0-9]+(?:\\.[0-9]+)*)")

        /**
         * The first (newest) release in GitHub's list, which includes
         * pre-releases (these builds go out as such), or null if the answer
         * isn't a list with a numbered release first.
         */
        fun parse(body: String): UpdateRelease? = runCatching {
            val release = JSONArray(body).optJSONObject(0) ?: return null
            val version = TAG.matchEntire(release.optString("tag_name"))?.groupValues?.get(1) ?: return null
            val page = release.optString("html_url").takeIf { it.startsWith("https://github.com/") }
                ?: "https://github.com/${Updates.REPOSITORY}/releases"
            val list = release.optJSONArray("assets") ?: JSONArray()
            val assets = (0 until list.length()).mapNotNull { index ->
                val asset = list.optJSONObject(index) ?: return@mapNotNull null
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (name.isEmpty() || url.isEmpty()) null else ReleaseAsset(name, url, asset.optLong("size", -1))
            }
            UpdateRelease(version, page, assets)
        }.getOrNull()
    }
}
