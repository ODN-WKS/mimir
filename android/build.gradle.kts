// The same plugin versions as the previous phone app (design: "Dependencies").
plugins {
    id("com.android.application") version "9.5.1" apply false
    id("com.android.test") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}

// Build output lives in RAM (/tmp, which is in memory here) to spare the SSDs (owner, 2026-11-21).
// `app/build` stays a link to it, so scripts keep their usual paths. A reboot
// empties it; the next build fills it again.
val ramOut = File("/tmp/mimir-build/android-out/" + rootDir.absolutePath.hashCode().toUInt())
if (File("/tmp").isDirectory) {
    subprojects { layout.buildDirectory.set(File(ramOut, name)) }
    layout.buildDirectory.set(File(ramOut, "root"))
    File(ramOut, "app").mkdirs()
    val link = file("app/build").toPath()
    val isLink = java.nio.file.Files.isSymbolicLink(link)
    if (isLink && java.nio.file.Files.readSymbolicLink(link) != File(ramOut, "app").toPath()) {
        java.nio.file.Files.delete(link)
    }
    if (!isLink && java.nio.file.Files.isDirectory(link)) {
        // An old on-disk build folder: moved aside so no script reads stale files.
        val old = file("app/build-old-disk").toPath()
        if (!java.nio.file.Files.exists(old)) java.nio.file.Files.move(link, old) else file("app/build").deleteRecursively()
    }
    if (!java.nio.file.Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        java.nio.file.Files.createSymbolicLink(link, File(ramOut, "app").toPath())
    }
}
