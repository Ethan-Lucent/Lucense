package com.exo.musicplayer.desktop.update

import com.exo.musicplayer.desktop.system.Platform
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstallLayoutTest {
    private val dir = UpdateTestKit.tempDir("layout")

    @AfterTest
    fun tearDown() = UpdateTestKit.cleanUp(dir)

    private fun linuxInstall(): File {
        val root = File(dir, "opt/lucense")
        UpdateTestKit.write(File(root, "bin/Lucense"), "#!/bin/sh\n".toByteArray(), exec = true)
        UpdateTestKit.write(File(root, "lib/app/Lucense.cfg"), "[Application]\n".toByteArray())
        File(root, "lib/app/resources").mkdirs()
        return root
    }

    private fun windowsInstall(): File {
        val root = File(dir, "Program Files/Lucense")
        UpdateTestKit.write(File(root, "Lucense.exe"), "MZ".toByteArray())
        UpdateTestKit.write(File(root, "app/Lucense.cfg"), "[Application]\n".toByteArray())
        File(root, "app/resources").mkdirs()
        return root
    }

    @Test
    fun `a Linux package install is found from its resources folder`() {
        val root = linuxInstall()
        val layout = assertNotNull(InstallLayout.detect(File(root, "lib/app/resources").path, "1.57.1", "Linux", "amd64"))
        assertEquals(root.absoluteFile, layout.root)
        assertEquals(File(root, "bin/Lucense").absoluteFile, layout.launcher.absoluteFile)
        assertEquals("lib/app", layout.appDir)
        assertEquals("linux-x64", layout.platform)
        assertEquals("bin/Lucense", layout.launcherPath)
        assertFalse(layout.windows)
    }

    @Test
    fun `a Windows install is found from its resources folder`() {
        val root = windowsInstall()
        val layout = assertNotNull(InstallLayout.detect(File(root, "app/resources").path, "1.57.1", "Windows 11", "amd64"))
        assertEquals(root.absoluteFile, layout.root)
        assertEquals("app", layout.appDir)
        assertEquals("windows-x64", layout.platform)
        assertEquals("Lucense.exe", layout.launcherPath)
        assertTrue(layout.windows)
    }

    @Test
    fun `anything else turns updating in place off`() {
        val linux = File(linuxInstall(), "lib/app/resources").path
        // A run from Gradle: no property at all.
        assertNull(InstallLayout.detect(null, "1.57.1", "Linux", "amd64"))
        assertNull(InstallLayout.detect("", "1.57.1", "Linux", "amd64"))
        // A developer build's version.
        assertNull(InstallLayout.detect(linux, "dev", "Linux", "amd64"))
        assertNull(InstallLayout.detect(linux, null, "Linux", "amd64"))
        // No build is released for these.
        assertNull(InstallLayout.detect(linux, "1.57.1", "Linux", "aarch64"))
        assertNull(InstallLayout.detect(linux, "1.57.1", "Mac OS X", "x86_64"))
        // The Linux layout read as Windows' finds no Lucense.exe.
        assertNull(InstallLayout.detect(linux, "1.57.1", "Windows 11", "amd64"))
        // A folder that only looks like it.
        val odd = File(dir, "odd/app/resources").apply { mkdirs() }
        assertNull(InstallLayout.detect(odd.path, "1.57.1", "Linux", "amd64"))
        // The launcher gone.
        File(dir, "opt/lucense/bin/Lucense").delete()
        assertNull(InstallLayout.detect(linux, "1.57.1", "Linux", "amd64"))
    }

    @Test
    fun `writability is tried for real, folder by folder`() {
        assumeTrue(Platform.isLinux && System.getProperty("user.name") != "root")
        val root = linuxInstall()
        val layout = assertNotNull(InstallLayout.detect(File(root, "lib/app/resources").path, "1.57.1", "Linux", "amd64"))
        assertTrue(layout.isWritable(listOf("lib/app/Lucense.cfg", "lib/app/new/folder/x.jar")))
        File(root, "lib/app").setWritable(false, false)
        try {
            assertFalse(layout.isWritable(listOf("lib/app/Lucense.cfg")))
            // A path whose own folder can't be written, even if the root can.
            assertTrue(layout.isWritable(listOf("bin/Lucense")))
            assertFalse(layout.isWritable(listOf("bin/Lucense", "lib/app/x.jar")))
        } finally {
            File(root, "lib/app").setWritable(true, true)
        }
        // Nothing is left behind by the probing.
        assertTrue(root.walkTopDown().none { it.name.startsWith(".lucense-probe") })
    }
}
