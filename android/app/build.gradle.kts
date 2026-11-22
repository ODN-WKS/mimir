import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The app carries Mimir's own version (the repository's VERSION file, e.g. 0.01).
val versionText = rootProject.file("../VERSION").readLines().first().trim()
val versionParts = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?$""").find(versionText)?.groupValues
    ?: error("VERSION must look like 0.01, not \"$versionText\"")

val buildTime: String = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())

fun sha256Of(x: File): String = MessageDigest.getInstance("SHA-256").let { md ->
    x.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
    md.digest().joinToString("") { "%02x".format(it) }
}

// Ingrid, the read-aloud voice, ships inside the app (owner, 2026-11-19): no download on the
// phone. THE ONE PLACE that says which model file ships: an https address (fetched once, kept in
// ~/.cache) or a file in the repository ("repo:path/from/the/repository/root"), and its SHA-256.
// To ship another copy (an int8 one, say), change these two lines; the app needs nothing else.
val ingridModel = "https://huggingface.co/csukuangfj/vits-piper-en_GB-alba-medium/resolve/7e469a54c7a4f5bd191cae788e197266e2c9c5ac/en_GB-alba-medium.onnx"
val ingridModelSha = "c904d007a8047ab13628b021351b983d0a2627c0d7a81c64a6fe9ad661adb1cf"
// Built once into assets/voices/ingrid-model.ort: the model above, pre-optimised for ARM phones
// by tools/ingrid-ort.sh (checked to sound exactly the same), so the phone loads her faster. Only
// the current files are kept in ~/.cache/mimir-voices.
val voiceAssets: File = run {
    val home = File(System.getProperty("user.home"), ".cache/mimir-voices")
    val src = File(home, "source/ingrid-model.onnx")
    if (!src.exists() || sha256Of(src) != ingridModelSha) {
        src.parentFile.deleteRecursively()
        src.parentFile.mkdirs()
        val tmp = File(src.path + ".part")
        if (ingridModel.startsWith("repo:")) rootProject.file("../" + ingridModel.removePrefix("repo:")).copyTo(tmp, overwrite = true)
        else URI(ingridModel).toURL().openStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
        check(sha256Of(tmp) == ingridModelSha) { "Ingrid's model didn't match its checksum" }
        tmp.renameTo(src)
    }
    val root = File(home, "assets")
    val out = File(root, "voices/ingrid-model.ort")
    val script = rootProject.file("../tools/ingrid-ort.sh")
    val stamp = File(home, "ingrid-model.ort.made-from")
    val want = "$ingridModelSha ${sha256Of(script)} ${sha256Of(rootProject.file("../tools/ingrid-ort-check.py"))}"
    if (!out.exists() || !stamp.exists() || stamp.readText() != want) {
        root.deleteRecursively()
        val p = ProcessBuilder("bash", script.path, src.path, out.path).inheritIO().start()
        check(p.waitFor() == 0 && out.exists()) { "Ingrid's model couldn't be pre-built (tools/ingrid-ort.sh)" }
        stamp.writeText(want)
    }
    // Anything else there (the old copy of the model, half-done downloads) goes.
    home.listFiles()?.filter { it.name !in setOf("source", "assets", "ortvenv", "work", stamp.name) }?.forEach { it.deleteRecursively() }
    root
}

android {
    namespace = "io.github.odnwks.mimir"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.odnwks.mimir"
        minSdk = 28
        targetSdk = 36
        versionCode = versionParts[1].toInt() * 1_000_000 + versionParts[2].toInt() * 1000 + (versionParts[3].toIntOrNull() ?: 0)
        versionName = versionText
        // When this build was made, for the developer log's header.
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        // The phone core is built for 64-bit ARM only (every phone it's for).
        ndk { abiFilters += "arm64-v8a" }
    }

    // "real" is the owner's app; "dev" is the check build, installed beside it with its own id and data,
    // so checks on the test phones never touch the real app.
    flavorDimensions += "use"
    productFlavors {
        create("real") {
            dimension = "use"
            resValue("string", "app_name", "Mímir")
        }
        create("dev") {
            dimension = "use"
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "Mímir dev")
        }
    }

    signingConfigs {
        // The app's own key, kept outside the repository and separate from any other app's key.
        create("personal") {
            val ks = file("${System.getProperty("user.home")}/.config/mimir/android-signing/release.jks")
            if (ks.exists()) {
                // tools/release.sh passes a short-lived file with -PmimirPassFile=...
                val pass = file(
                    (findProperty("mimirPassFile") as String?)
                        ?: "${System.getProperty("user.home")}/.config/mimir/android-signing.pass"
                )
                    .takeIf { it.exists() }?.readText()?.trim().orEmpty()
                storeFile = ks
                storePassword = pass
                keyAlias = "mimir-release"
                keyPassword = pass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingConfigs.getByName("personal").storeFile != null) {
                signingConfig = signingConfigs.getByName("personal")
            }
        }
        // The release code, profileable and signed with the debug key: what the frame-timing
        // check (the benchmark module) measures.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        // The same, with its names kept, so the baseline profile can be walked on it and then
        // matches the real release (tools/baseline-profile.sh).
        create("nonMinified") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
        resValues = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Screenshot tests (Roborazzi on Robolectric, no device): record every run, into one folder.
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty(
                    "roborazzi.outputDir",
                    layout.buildDirectory.dir("outputs/roborazzi").get().asFile.absolutePath,
                )
                it.maxHeapSize = "3g"
                it.maxParallelForks = 2
            }
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets["main"].assets.srcDir(voiceAssets)
    // The voice model is read straight from the app (not unpacked first), so it isn't compressed.
    androidResources { noCompress += listOf("onnx", "ort") }
}

// The natural voices' engine: sherpa-onnx 1.13.8 (the PC's version), its official Android
// library with onnxruntime built in. Fetched once from its GitHub release, checked, kept in
// ~/.cache; only its arm64 part ships (abiFilters above).
val sherpaAar: File = run {
    val name = "sherpa-onnx-static-link-onnxruntime-1.13.8.aar"
    val sha = "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"
    val f = File(System.getProperty("user.home"), ".cache/mimir-sherpa/$name")
    fun hash(x: File) = sha256Of(x)
    if (!f.exists() || hash(f) != sha) {
        f.parentFile.mkdirs()
        val tmp = File(f.path + ".part")
        URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/$name").toURL().openStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
        check(hash(tmp) == sha) { "$name didn't match its checksum" }
        tmp.renameTo(f)
    }
    f
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation(files(sherpaAar))
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // Installs the baseline profile (src/main/baseline-prof.txt) on phones the app is sideloaded to.
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")
    // The app lock (fingerprint or the phone's PIN) and the pairing code scanner.
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Staying in touch: the check job.
    implementation("androidx.work:work-runtime:2.10.0")
    // Playing updates (Ogg Opus) with a media session: lock-screen and headset controls.
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.77.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.77.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.77.0")
}

// The phone core (Rust, over JNI) is built before every app build, so an APK
// can never carry a core older than the Kotlin that calls it.
val buildPhoneCore by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir.parentFile
    commandLine("bash", "tools/build-phone-core.sh")
}
// Standalone mode's model runner (llama.cpp), built the same way.
val buildStandalone by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir.parentFile
    commandLine("bash", "tools/build-standalone.sh")
}
tasks.named("preBuild") { dependsOn(buildPhoneCore, buildStandalone) }
