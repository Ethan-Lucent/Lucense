import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// Read from gradle.properties rather than written here, so the desktop
// installer and the APK can never disagree about which release they are.
val lucenseVersion = providers.gradleProperty("lucenseVersion").get()

// Targets Java 17 bytecode using whichever JDK is running, rather than
// demanding a JDK 17 toolchain be installed. :app consumes this module and
// Android rejects class files newer than 17.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Room is Android-only; the desktop build talks to the same SQLite file
    // shape through JDBC so a library can be moved between the two.
    implementation(libs.sqlite.jdbc)
    implementation(libs.haze)
    implementation(libs.jaudiotagger)
    implementation(libs.org.json)

    // Java Sound service providers. Adding these to the classpath teaches
    // AudioSystem to open MP3, OGG Vorbis and FLAC; WAV and AIFF are built in.
    // Roughly 1 MB in total, versus ~90 MB for a bundled ffmpeg.
    implementation(libs.mp3spi)
    implementation(libs.vorbisspi)
    implementation(libs.jflac)

    // Win32 access for the global duck hotkey. RegisterHotKey is the whole
    // reason: it asks the OS to forward one specific combination and nothing
    // else, unlike a low-level keyboard hook, which sees every keystroke on the
    // machine and is indistinguishable from a keylogger to antivirus software.
    implementation(libs.jna)
    implementation(libs.jna.platform)

    // MPRIS on Linux: how the desktop hands a player its media keys and shows
    // it in its media controls. Pure Java over the session bus's Unix socket.
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport)

    testImplementation(kotlin("test-junit5"))
    // Drives pages with a pretend mouse: the album page's drag to reorder.
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}

tasks.test {
    useJUnitPlatform()
    // Linux's data folder, kept inside the build, so tests never touch anyone's own Lucense.
    environment("XDG_DATA_HOME", layout.buildDirectory.dir("test-data").get().asFile.absolutePath)
    // And its cache folder (the covers kept on disk), likewise.
    environment("XDG_CACHE_HOME", layout.buildDirectory.dir("test-cache").get().asFile.absolutePath)
    // Dragged songs are linked into ~/Downloads/.lucense-share on Linux; never from a test.
    systemProperty("lucense.share.dir", "off")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

/** The Linux build is made on Linux; jpackage only packages for the system it runs on. */
val buildingOnLinux = System.getProperty("os.name").startsWith("Linux")

compose.desktop {
    application {
        mainClass = "com.exo.musicplayer.desktop.MainKt"

        // Java reads neither HTTP_PROXY nor the Windows proxy settings on its
        // own, so on a machine behind a proxy every provider only reachable
        // through it silently returns nothing — Deezer and NetEase among them.
        // This makes the JVM honour the system configuration; where no proxy is
        // set, or traffic is already routed at the adapter, it changes nothing.
        jvmArgs("-Djava.net.useSystemProxies=true")

        // The About panel reads this back. Baking it in at package time means
        // the number on screen is the number the installer registered with
        // Windows, rather than a second copy that can drift.
        jvmArgs("-Dlucense.version=$lucenseVersion")

        // The window's X11 class is named after the menu entry (see Main.kt), which
        // means reaching into AWT's X11 toolkit.
        if (buildingOnLinux) jvmArgs("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")
        // So is asking the window manager to move and size it (LinuxFrame), which
        // has to hold AWT's lock on that toolkit's connection.
        if (buildingOnLinux) jvmArgs("--add-exports=java.desktop/sun.awt=ALL-UNNAMED")

        // Memory behaviour, deliberately without -Xmx.
        //
        // A hard heap cap is the usual way to make a JVM look small, and it is
        // also how a JVM starts throwing OutOfMemoryError at whatever the author
        // failed to anticipate — a 900-track bulk job, an enormous cover. These
        // flags instead let the heap grow as far as it needs and make the JVM
        // give the memory back afterwards, which the defaults never do: stock
        // MaxHeapFreeRatio is 70 and periodic GC is off entirely, so the heap
        // ratchets upward and stays there for the life of the process.
        // -Xms is a floor, not a ceiling: it cannot cause an OutOfMemoryError,
        // it only stops the JVM committing a large heap it has no use for. The
        // ergonomic default is 1/64 of physical RAM, which on a 32 GB machine
        // means half a gigabyte reserved at startup for an app whose live set is
        // around 15 MB. It grows on demand from here.
        jvmArgs("-Xms32m")
        jvmArgs("-XX:MinHeapFreeRatio=10")
        jvmArgs("-XX:MaxHeapFreeRatio=25")
        jvmArgs("-XX:G1PeriodicGCInterval=20000")
        jvmArgs("-XX:G1PeriodicGCSystemLoadThreshold=0")

        // Dispatchers.IO defaults to 64 threads. Nothing here needs that much
        // concurrency — the widest fan-out is seven catalogue lookups — and
        // every thread costs committed stack.
        jvmArgs("-Dkotlinx.coroutines.io.parallelism=12")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Lucense"
            // The publisher Windows lists under Apps, and the .deb's maintainer.
            vendor = "Lucense"

            windows {
                // Shown by the installer, the Start menu, the taskbar and the
                // shortcut. jpackage wants a real .ico; a PNG is silently ignored.
                iconFile.set(project.layout.projectDirectory.file("icons/lucense.ico"))
            }

            // yt-dlp, ffmpeg and spotdl ship inside the app image rather than
            // being fetched on first use. They are the bulk of the installer,
            // but a music downloader that cannot download until you have found
            // and installed two other programs is not really a downloader.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            // The packaged runtime is a trimmed jlink image, so anything outside
            // java.base has to be asked for by name. Leaving these out builds
            // and runs fine under `gradle run` — which uses the full JDK — and
            // then fails only in the installed app, which is the worst possible
            // place to find out.
            modules(
                "java.sql",         // JDBC, for the library database
                "java.naming",      // required by java.sql
                "java.prefs",       // settings live in the registry via Preferences
                "jdk.crypto.ec",    // elliptic-curve TLS: most of the APIs need it
                "java.net.http",    // WebSocket, to read the sign-in window's cookies
                "jdk.unsupported"   // sun.misc.Unsafe, used by the audio SPIs
            )
            if (buildingOnLinux) {
                // D-Bus for MPRIS: its introspection is XML, it authenticates as
                // the Unix user, and it reads the peer's credentials off the socket.
                modules("java.xml", "jdk.security.auth", "jdk.net")
            }
            // Drives the MSI ProductVersion, which is the whole of Windows'
            // upgrade logic: it has to increase for an installer to replace an
            // existing install instead of offering to remove it. jpackage
            // derives a fresh ProductCode from name plus version, so bumping
            // this is also what makes the new build a distinct product.
            packageVersion = lucenseVersion
            windows {
                menuGroup = "Lucense"
                shortcut = true
                // Stable UUID so upgrades replace rather than stack.
                upgradeUuid = "6E3F1C42-6B7B-4C3E-9A1F-2B7D5E0A9C11"
            }
            linux {
                // The package's name, and the start of its menu entry's:
                // lucense-Lucense.desktop, which MPRIS names as the DesktopEntry.
                packageName = "lucense"
                iconFile.set(project.layout.projectDirectory.file("icons/lucense.png"))
                shortcut = true
                menuGroup = "AudioVideo;Audio;Player"
                appCategory = "sound"
                debMaintainer = "lucense@users.noreply.github.com"
                rpmLicenseType = "MIT"
                // In the "Open with" list for music files; the desktop asks before making it the default.
                for ((extension, mime) in listOf(
                    "mp3" to "audio/mpeg", "flac" to "audio/flac", "ogg" to "audio/ogg", "opus" to "audio/opus",
                    "m4a" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/x-wav", "aiff" to "audio/x-aiff"
                )) {
                    fileAssociation(mimeType = mime, extension = extension, description = "Music")
                }
            }
        }
    }
}

// ---- MSI upgrade repair ----------------------------------------------------
//
// jpackage sequences RemoveExistingProducts at 798, which WiX's own validator
// rejects (ICE27) because that is the search phase, not the execution phase.
// Left alone, the previous version is uninstalled outside the install
// transaction: nothing rolls it back if the new install then fails. The fix is
// one integer in the sequence table, applied to whatever jpackage just wrote.
//
// Wired with finalizedBy rather than folded into tools/build.sh so that a
// plain `gradle packageMsi` produces a correct installer too - an installer
// that is only correct when built through one particular script is a trap.
val fixMsiUpgradeSequence by tasks.registering(Exec::class) {
    description = "Moves RemoveExistingProducts into the MSI execution phase (ICE27)."
    // The MSI is rewritten in place, so there is nothing to be up to date about.
    outputs.upToDateWhen { false }

    // Both the debug and release packaging tasks are swept; the script ignores
    // directories that do not exist, so one call covers whichever ran.
    val msiDirs = listOf("main", "main-release").map {
        layout.buildDirectory.dir("compose/binaries/$it/msi").get().asFile.absolutePath
    }
    commandLine(
        listOf(
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
            rootProject.layout.projectDirectory.file("tools/fix-msi-upgrade.ps1")
                .asFile.absolutePath,
            "-MsiDir", msiDirs.joinToString(";")
        )
    )
}

listOf("packageMsi", "packageReleaseMsi").forEach { name ->
    tasks.matching { it.name == name }.configureEach { finalizedBy(fixMsiUpgradeSequence) }
}

// ---- The installer ---------------------------------------------------------
//
// Lucense-<version>-Setup.exe: Lucense's own installer window (installer/),
// carrying the MSI and running it with Windows Installer's dialogs switched
// off. The MSI is still made, and ships beside it for anyone who wants one.
val packageSetup by tasks.registering(Exec::class) {
    description = "Builds Lucense-<version>-Setup.exe: the installer with the MSI inside it."
    group = "compose desktop"
    dependsOn("packageMsi")
    // After the MSI's upgrade repair, which finalises packageMsi.
    mustRunAfter(fixMsiUpgradeSequence)
    outputs.upToDateWhen { false }
    val binaries = layout.buildDirectory.dir("compose/binaries/main").get().asFile
    commandLine(
        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        rootProject.layout.projectDirectory.file("tools/build-setup.ps1").asFile.absolutePath,
        "-Msi", File(binaries, "msi/Lucense-$lucenseVersion.msi").absolutePath,
        "-Out", File(binaries, "setup/Lucense-$lucenseVersion-Setup.exe").absolutePath
    )
}

// The bundled tools are programs: on Linux they need permission to run, and
// Compose's copy of them into the app image leaves it behind (measured:
// rw-r--r-- in the .deb and .rpm, and "Permission denied" running them).
// Install scripts can't put it back: Compose hands jpackage a resource folder
// of its own after any given here, so jpackage never saw ours (the .deb's
// postinst was jpackage's own, byte for byte). Instead the finished app image
// gets the permission back, and the .deb and .rpm are packaged from that image
// rather than built from scratch again, so they carry it as it is.
tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
    val image = (this as org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask).destinationDir
    // Kept inside the task: a value of the script itself can't go in the configuration cache.
    val linuxTools = listOf("yt-dlp", "ffmpeg", "qjs", "spotdl")
    doLast {
        if (!System.getProperty("os.name").lowercase().contains("linux")) return@doLast
        val resources = image.get().asFile.resolve("Lucense/lib/app/resources")
        linuxTools.map { resources.resolve(it) }.filter { it.isFile }.forEach { it.setExecutable(true, false) }

        // A faster start: a class archive for the packaged runtime.
        //
        // jpackage's trimmed runtime has no class archive, so every launch loads and
        // checks some ten thousand classes - Java's own, Compose's, Skiko's and ours -
        // from the jars before the first window can open. Measured here: the window
        // opens at 2.45 s without an archive and 1.65 s with one, whether the archive
        // was made from an empty library or a full one, and from the folder it was
        // made in or a copy moved elsewhere (an install under /opt).
        //
        // The runtime has no `java` to dump one with, so the app's own launcher does
        // it, told what to do through JAVA_TOOL_OPTIONS: once to note which classes a
        // run really loads, once to write them into the archive the runtime looks for
        // by itself (lib/server/classes.jsa), so the launch needs no flag for it. It
        // needs a display, because the first run is the real window; without one, or
        // if anything goes wrong, the build carries on and the app starts as slowly as
        // before. A mismatched archive (the jars changed) is skipped by the JVM, never
        // an error, so a stale one costs speed and nothing else.
        val app = image.get().asFile.resolve("Lucense")
        val launcher = app.resolve("bin/Lucense")
        val archive = app.resolve("lib/runtime/lib/server/classes.jsa")
        if (!launcher.isFile) return@doLast
        if (System.getenv("DISPLAY").isNullOrBlank()) {
            logger.warn("No display, so no class archive: the app will start more slowly. Run under xvfb-run to make one.")
            return@doLast
        }
        val scratch = Files.createTempDirectory("lucense-cds").toFile()
        try {
            fun launch(options: String): Process = ProcessBuilder(launcher.absolutePath)
                .redirectErrorStream(true)
                .redirectOutput(File(scratch, "output-${System.nanoTime()}.txt"))
                .apply {
                    // Its own data folder, so it is never the running Lucense and touches nobody's library.
                    environment()["XDG_DATA_HOME"] = scratch.absolutePath
                    environment()["JAVA_TOOL_OPTIONS"] = options
                }
                .start()
            val classes = File(scratch, "classes.lst")
            val training = launch("-XX:DumpLoadedClassList=${classes.absolutePath}")
            // Long enough to open the window and settle; if it ends sooner it didn't work.
            val endedEarly = training.waitFor(12, TimeUnit.SECONDS)
            training.destroy()
            if (!training.waitFor(15, TimeUnit.SECONDS)) training.destroyForcibly()
            if (endedEarly || !classes.isFile || classes.length() == 0L) {
                logger.warn("The training run didn't stay up, so no class archive: the app will start more slowly.")
                return@doLast
            }
            val dump = launch(
                "-Xshare:dump -XX:SharedClassListFile=${classes.absolutePath} -XX:SharedArchiveFile=${archive.absolutePath}"
            )
            if (!dump.waitFor(180, TimeUnit.SECONDS)) dump.destroyForcibly()
            if (dump.exitValue() != 0 || !archive.isFile) {
                archive.delete()
                logger.warn("The class archive wasn't written, so the app will start more slowly.")
            }
        } finally {
            scratch.deleteRecursively()
        }
    }
}
listOf("", "Release").forEach { variant ->
    tasks.matching { it.name == "package${variant}Deb" || it.name == "package${variant}Rpm" }.configureEach {
        val image = tasks.named<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>("create${variant}Distributable")
        (this as org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask).apply {
            dependsOn(image)
            appImage.set(image.flatMap { it.destinationDir.dir("Lucense") })
            // Compose leaves --icon out when packaging an existing app image, and jpackage
            // then gives the menu entry - and so the dock and Alt+Tab - Java's own Duke
            // icon. jpackage takes --icon fine alongside --app-image.
            freeArgs.addAll("--icon", project.layout.projectDirectory.file("icons/lucense.png").asFile.absolutePath)
        }
    }
}

// ---- Release files for in-app updates --------------------------------------
//
// The updater downloads only the files that changed. To do that it needs, for
// each release and platform, a manifest naming every file of the app image with
// its hash, and a zip of that same image whose entries it can fetch one by one
// (HTTP range requests on the central directory and then on single entries).
// Both come from the app image that createDistributable leaves behind, so the
// class archive made there is in both. See docs/UPDATES.md.

/** The platform name used in release file names, or a clear failure on anything else. */
fun releasePlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    if (arch != "amd64" && arch != "x86_64") {
        throw GradleException("Release files are only made for x64 machines; this one is '$arch'.")
    }
    return when {
        os.contains("linux") -> "linux-x64"
        os.contains("windows") -> "windows-x64"
        else -> throw GradleException("Release files are only made on Linux or Windows; this is '${System.getProperty("os.name")}'.")
    }
}

/** Writes Lucense-<version>-<platform>-manifest.json: every regular file of the app image, hashed. */
abstract class AppManifest : DefaultTask() {
    /** The app image folder (the one holding Lucense.exe or bin/Lucense): the install root. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val image: DirectoryProperty

    @get:Input
    abstract val version: Property<String>

    /** Read lazily, so a machine that can't make a manifest only fails when asked for one. */
    @get:Input
    abstract val platform: Property<String>

    @get:OutputFile
    abstract val manifest: RegularFileProperty

    @TaskAction
    fun write() {
        val root = image.get().asFile.toPath()
        val platformName = platform.get()
        val versionName = version.get()
        class Entry(val path: String, val file: Path, val size: Long, val exec: Boolean)
        val entries = ArrayList<Entry>()
        var skipped = 0
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                // Symlinks and other oddities are not listed: the updater only deals in regular files.
                if (!attrs.isRegularFile) {
                    skipped++
                    logger.warn("Not listed (not a regular file): ${root.relativize(file)}")
                    return FileVisitResult.CONTINUE
                }
                val exec = platformName == "linux-x64" &&
                    PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(file)
                entries += Entry(root.relativize(file).joinToString("/"), file, attrs.size(), exec)
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = throw exc
        })
        // Plain character order, so the same image always gives the same manifest.
        entries.sortBy { it.path }

        fun quoted(text: String): String {
            val out = StringBuilder("\"")
            for (c in text) when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c < ' ' -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
            return out.append('"').toString()
        }

        val buffer = ByteArray(1 shl 20)
        val target = manifest.get().asFile
        target.parentFile.mkdirs()
        target.bufferedWriter(Charsets.UTF_8).use { json ->
            json.write("{\"version\":${quoted(versionName)},\"platform\":${quoted(platformName)},")
            json.write("\"zip\":${quoted("Lucense-$versionName-$platformName.zip")},\"prefix\":\"Lucense/\",\n\"files\":[\n")
            entries.forEachIndexed { index, entry ->
                // Streamed: the bundled ffmpeg alone is 179 MB.
                val digest = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(entry.file).use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                    }
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                json.write("{\"path\":${quoted(entry.path)},\"size\":${entry.size},\"sha256\":\"$sha\",\"exec\":${entry.exec}}")
                json.write(if (index < entries.size - 1) ",\n" else "\n")
            }
            json.write("]}\n")
        }
        logger.lifecycle("${target.name}: ${entries.size} files" + if (skipped > 0) ", $skipped not listed" else "")
    }
}

tasks.register<AppManifest>("appManifest") {
    description = "Writes the per-file manifest of the app image that the in-app updater reads."
    group = "distribution"
    dependsOn("createDistributable")
    image.set(layout.buildDirectory.dir("compose/binaries/main/app/Lucense"))
    version.set(lucenseVersion)
    platform.set(providers.provider { releasePlatform() })
    manifest.set(layout.buildDirectory.file(platform.map { "release/Lucense-$lucenseVersion-$it-manifest.json" }))
}

// The zip the updater reads single files out of. Gradle's Zip deflates every
// file as an entry of its own and keeps the files' permissions (the exec bits).
// On Windows the zip is made by the workflow (Compress-Archive), so there is no task.
if (buildingOnLinux) {
    tasks.register<Zip>("appZip") {
        description = "Zips the app image as Lucense-<version>-linux-x64.zip, one entry per file under Lucense/."
        group = "distribution"
        dependsOn("createDistributable")
        archiveFileName.set("Lucense-$lucenseVersion-linux-x64.zip")
        destinationDirectory.set(layout.buildDirectory.dir("release"))
        entryCompression = ZipEntryCompression.DEFLATED
        // Same image, same bytes: no timestamps, a fixed order.
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        from(layout.buildDirectory.dir("compose/binaries/main/app/Lucense")) { into("Lucense") }
        // Files only, as the manifest lists them: no entries for folders.
        // No entries for empty folders. (Folders that hold files still get one, which readers ignore.)
        includeEmptyDirs = false
    }
}
