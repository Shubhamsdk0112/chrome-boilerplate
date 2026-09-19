/*
 * :ytdlp — yt-dlp powered YouTube -> local library importer for Gramophone.
 *
 * Kept as a separate module so that upstream Gramophone can be updated with a
 * plain `git pull` without this code ever conflicting: the only things the app
 * module knows about us are the two entry points in DownloaderActivity.
 */
plugins {
    id("com.android.library")
    id("com.android.built-in-kotlin")
    kotlin("plugin.compose")
}

android {
    namespace = "org.akanework.gramophone.extras"
    compileSdk = 37

    defaultConfig {
        // youtubedl-android's floor; the app module is raised to match.
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    lint {
        lintConfig = file("../app/lint.xml")
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

dependencies {
    // The yt-dlp runtime. `library` carries CPython + yt-dlp + quickjs, `ffmpeg`
    // carries the ffmpeg/ffprobe binaries yt-dlp shells out to for extraction
    // and tagging. Both are `api` so the app module's packaging rules see them.
    val ytdlpVersion = "0.18.1"
    api("io.github.junkfood02.youtubedl-android:library:$ytdlpVersion")
    api("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdlpVersion")

    // Podcast playback goes through the app's own MediaLibraryService, via
    // a MediaController. Same version string as the app; the root build's
    // dependencySubstitution redirects it to the patched Media3 checkout.
    implementation("androidx.media3:media3-session:1.10.1")
    implementation("androidx.media3:media3-common:1.10.1")

    implementation("androidx.core:core-ktx:1.17.0")
    // FilterStore writes into the app's default SharedPreferences, which the
    // app reads through androidx.preference; same version as the app module.
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")

    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    // Same Coil the app module configures as the singleton loader; the job
    // cards only ever load local files through it, never the network.
    implementation("io.coil-kt.coil3:coil-compose:3.4.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
