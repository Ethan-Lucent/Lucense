package com.exo.musicplayer.desktop.update

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateManifestTest {
    private val dir = UpdateTestKit.tempDir("manifest")

    @AfterTest
    fun tearDown() = UpdateTestKit.cleanUp(dir)

    private val sha = "ab".repeat(32)

    private fun json(vararg files: Pair<String, Long>, version: String = "1.58.0", platform: String = "linux-x64"): String =
        JSONObject()
            .put("version", version)
            .put("platform", platform)
            .put("zip", "Lucense-$version-$platform.zip")
            .put("prefix", "Lucense/")
            .put("files", JSONArray().also { array ->
                files.forEach { (path, size) -> array.put(JSONObject().put("path", path).put("size", size).put("sha256", sha).put("exec", false)) }
            })
            .toString()

    @Test
    fun `parses the release side's format`() {
        val text = """{"version":"1.58.0","platform":"linux-x64","zip":"Lucense-1.58.0-linux-x64.zip","prefix":"Lucense/",
"files":[
{"path":"bin/Lucense","size":22008,"sha256":"b6c11c88fd797451e6c81f075fef8d4d044a1187eb91698ecf932914f57e4654","exec":true},
{"path":"lib/app/desktop-ab12.jar","size":4400000,"sha256":"$sha","exec":false}
]}
"""
        val manifest = UpdateManifest.fromJson(text)
        assertEquals("1.58.0", manifest.version)
        assertEquals("Lucense/", manifest.prefix)
        assertEquals(2, manifest.files.size)
        assertTrue(manifest.files[0].exec)
        assertEquals(4_400_000, manifest.files[1].size)
        assertEquals(manifest, UpdateManifest.fromJson(manifest.toJson()))
    }

    @Test
    fun `unsafe paths are refused`() {
        val bad = listOf(
            "/etc/passwd", "../outside", "lib/../../x", "lib/./x", "lib\\app\\x.jar", "C:/Windows/x", "c:x",
            "lib//x", "lib/", "", "a\tb", "a\nb", "lib/x.jar.", "lib/x ", "a|b", "a\"b"
        )
        for (path in bad) {
            assertFalse(UpdateManifest.isSafePath(path), "accepted: $path")
            assertFailsWith<ManifestException>(path) { UpdateManifest.fromJson(json(path to 1)) }
        }
        assertTrue(UpdateManifest.isSafePath("lib/app/desktop-ab12.jar"))
        assertTrue(UpdateManifest.isSafePath("runtime/bin/server/jvm.dll"))
        assertTrue(UpdateManifest.isSafePath("lib/app/resources/..hidden-name"))
    }

    @Test
    fun `duplicates, also differing only in case, are refused`() {
        assertFailsWith<ManifestException> { UpdateManifest.fromJson(json("a.jar" to 1, "a.jar" to 1)) }
        assertFailsWith<ManifestException> { UpdateManifest.fromJson(json("app/A.jar" to 1, "app/a.jar" to 1)) }
    }

    @Test
    fun `bounds and fields are checked`() {
        assertFailsWith<ManifestException> { UpdateManifest.fromJson("x".repeat(UpdateManifest.MAX_BYTES + 1)) }
        val many = (0..UpdateManifest.MAX_FILES).map { "f/$it" to 1L }.toTypedArray()
        assertFailsWith<ManifestException> { UpdateManifest.fromJson(json(*many)) }
        assertFailsWith<ManifestException> { UpdateManifest.fromJson(json("a" to 1, version = "dev")) }
        assertFailsWith<ManifestException> { UpdateManifest.fromJson(json("a" to 1, platform = "macos-arm64")) }
        assertFailsWith<ManifestException> { UpdateManifest.fromJson(json("a" to -1)) }
        assertFailsWith<ManifestException> { UpdateManifest.fromJson(json()) }
        assertFailsWith<ManifestException> { UpdateManifest.fromJson("not json") }
        assertFailsWith<ManifestException> { UpdateManifest.fromJson(json("a" to 1).replace(sha, "xyz")) }
        assertFailsWith<ManifestException> { UpdateManifest.fromJson(json("a" to 1).replace("Lucense-1.58.0-linux-x64.zip", "../x.zip")) }
        assertFailsWith<ManifestException> { UpdateManifest.fromJson(json("a" to 1).replace("\"Lucense/\"", "\"../\"")) }
    }

    @Test
    fun `a manifest built from a folder lists every file with its hash and exec bit`() {
        val image = UpdateTestKit.linuxImage(File(dir, "image"), "1.0.0", File(dir, "log"))
        val manifest = UpdateTestKit.manifestOf(image, "1.0.0")
        val parsed = UpdateManifest.fromJson(manifest.toJson())
        assertEquals(image.walkTopDown().count { it.isFile }, parsed.files.size)
        assertEquals(parsed.files.map { it.path }.sorted(), parsed.files.map { it.path })
        assertTrue(parsed.files.first { it.path == "bin/Lucense" }.exec)
        assertFalse(parsed.files.first { it.path == "lib/app/Lucense.cfg" }.exec)
        assertEquals(UpdatePlanner.sha256(File(image, "lib/app/Lucense.cfg")), parsed.files.first { it.path == "lib/app/Lucense.cfg" }.sha256)
    }
}
