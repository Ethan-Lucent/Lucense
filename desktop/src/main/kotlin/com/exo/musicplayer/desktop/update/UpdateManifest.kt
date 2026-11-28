package com.exo.musicplayer.desktop.update

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One file of the app image, as the release lists it. */
data class ManifestFile(
    /** Relative to the install root, forward slashes; checked by [UpdateManifest.isSafePath]. */
    val path: String,
    val size: Long,
    /** Lower-case hex. */
    val sha256: String,
    /** Must be executable on Linux (the launcher, the JVM's own programs). */
    val exec: Boolean
)

class ManifestException(message: String) : Exception(message)

/**
 * The list of every file a release's app image holds, with sizes and SHA-256s
 * (`Lucense-X.Y.Z-<platform>-manifest.json`, written by the release build).
 *
 * Integrity: the manifest and the zip come from the same GitHub release over
 * HTTPS, so what is trusted here is GitHub's HTTPS plus the hashes in that
 * release. There is no signature yet; anyone who can publish a release on the
 * repository can publish an update, exactly as they can publish an installer.
 */
data class UpdateManifest(
    val version: String,
    val platform: String,
    val zip: String,
    val prefix: String,
    val files: List<ManifestFile>
) {
    fun toJson(): String = JSONObject()
        .put("version", version)
        .put("platform", platform)
        .put("zip", zip)
        .put("prefix", prefix)
        .put("files", JSONArray().also { array ->
            files.forEach {
                array.put(JSONObject().put("path", it.path).put("size", it.size).put("sha256", it.sha256).put("exec", it.exec))
            }
        })
        .toString()

    companion object {
        /** A real manifest is a few tens of KB; anything this big is not one, and isn't parsed. */
        const val MAX_BYTES = 2 * 1024 * 1024

        /** The app image has about 200 files; this leaves plenty of room and still bounds the work. */
        const val MAX_FILES = 5_000

        val PLATFORMS = setOf("linux-x64", "windows-x64")

        private val VERSION = Regex("[0-9]+(\\.[0-9]+){1,3}")
        private val HEX64 = Regex("[0-9a-f]{64}")

        // Characters Windows refuses in names, plus the ones the helper scripts
        // and the plan file treat as separators. A name with any of them is not
        // something the release build would ever produce.
        private const val FORBIDDEN = "\\:*?\"<>|"

        /**
         * Whether [path] can only ever point inside the install root: relative,
         * forward slashes only, no `.` or `..` segment, no drive letter, no
         * control characters (the plan file is tab- and line-separated, so a tab
         * or newline in a name could smuggle in a second entry).
         */
        fun isSafePath(path: String): Boolean {
            if (path.isEmpty() || path.length > 1024) return false
            if (path.startsWith("/") || path.endsWith("/")) return false
            if (path.any { it < ' ' || it == '\u007f' || it in FORBIDDEN }) return false
            return path.split('/').all { part ->
                part.isNotEmpty() && part != "." && part != ".." && part.length <= 255 &&
                    // Windows quietly drops trailing dots and spaces, so "a.jar." would land on "a.jar".
                    !part.endsWith(".") && !part.endsWith(" ")
            }
        }

        fun isVersion(text: String): Boolean = VERSION.matches(text)

        /** Parses and checks [text]; throws [ManifestException] saying what is wrong. */
        fun fromJson(text: String): UpdateManifest {
            if (text.length > MAX_BYTES) throw ManifestException("The update list is too large.")
            val root = try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw ManifestException("The update list isn't valid: ${e.message}")
            }
            val version = root.optString("version")
            if (!isVersion(version)) throw ManifestException("The update list has no proper version number.")
            val platform = root.optString("platform")
            if (platform !in PLATFORMS) throw ManifestException("The update list is for an unknown system: $platform")
            val zip = root.optString("zip")
            // A file name only: it is looked up among the release's assets, never used as a path.
            if (zip.isEmpty() || !isSafePath(zip) || '/' in zip || !zip.endsWith(".zip")) {
                throw ManifestException("The update list names no proper zip.")
            }
            val prefix = root.optString("prefix", "")
            if (prefix.isNotEmpty() && !(prefix.endsWith("/") && isSafePath(prefix.dropLast(1)))) {
                throw ManifestException("The update list has a bad folder prefix.")
            }
            val array = root.optJSONArray("files") ?: throw ManifestException("The update list has no files.")
            if (array.length() == 0) throw ManifestException("The update list has no files.")
            if (array.length() > MAX_FILES) throw ManifestException("The update list has too many files.")
            val seen = HashSet<String>()
            val files = (0 until array.length()).map { index ->
                val entry = array.optJSONObject(index) ?: throw ManifestException("Entry $index of the update list is broken.")
                val path = entry.optString("path")
                if (!isSafePath(path)) throw ManifestException("The update list has an unsafe path: ${path.take(80)}")
                // Compared without case too: on Windows "A.jar" and "a.jar" are one file.
                if (!seen.add(path.lowercase())) throw ManifestException("The update list names $path twice.")
                val size = entry.optLong("size", -1)
                if (size < 0 || size > 4L * 1024 * 1024 * 1024) throw ManifestException("The update list has a bad size for $path.")
                val sha = entry.optString("sha256").lowercase()
                if (!HEX64.matches(sha)) throw ManifestException("The update list has a bad checksum for $path.")
                ManifestFile(path, size, sha, entry.optBoolean("exec", false))
            }
            return UpdateManifest(version, platform, zip, prefix, files)
        }
    }
}
