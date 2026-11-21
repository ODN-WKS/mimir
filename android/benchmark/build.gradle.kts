// The frame-timing check (redesign proposal, "The frame budget"): five journeys on a real phone,
// measured with Macrobenchmark's FrameTimingMetric on the app's benchmark build (release code,
// profileable). Run it with tools/frame-check.sh; the release gate reads its result.
plugins {
    id("com.android.test")
}

// Its build output goes inside the app's build folder, which each copy of the code already keeps
// out of the repository (and off /tmp).
layout.buildDirectory.set(file("../app/build/benchmark-module"))

android {
    namespace = "io.github.odnwks.mimir.benchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    // Only the check build's app (it can open with example data, no PC needed).
    flavorDimensions += "use"
    productFlavors { create("dev") { dimension = "use" } }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" }
}

dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
    implementation("androidx.test.uiautomator:uiautomator:2.4.1")
    implementation("androidx.test.ext:junit:1.3.0")
}
