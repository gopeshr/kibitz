import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/**
 * Stockfish's neural networks, which the engine cannot evaluate without.
 *
 * They are not committed: the big one is 104 MB and GitHub rejects any single file above
 * 100 MB. They are fetched instead, from the same server Stockfish's own Makefile uses, and
 * verified on arrival — Stockfish names each network after the first 12 hex digits of its
 * SHA-256, so the filename is the checksum and nothing extra has to be kept in sync.
 */
val stockfishNetworks = listOf(
    "nn-c288c895ea92.nnue",
    "nn-37f18f62d772.nnue",
)

fun sha256Prefix(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }.take(12)
}

val fetchStockfishNetworks by tasks.registering {
    group = "build setup"
    description = "Downloads and verifies the Stockfish NNUE networks into src/main/assets."

    val assetsDirectory = layout.projectDirectory.dir("src/main/assets").asFile
    // Skipped entirely once both files are present, so this costs nothing on a normal build.
    outputs.upToDateWhen { stockfishNetworks.all { File(assetsDirectory, it).exists() } }

    doLast {
        assetsDirectory.mkdirs()
        for (name in stockfishNetworks) {
            val target = File(assetsDirectory, name)
            val expected = name.removePrefix("nn-").removeSuffix(".nnue")
            if (target.exists() && sha256Prefix(target) == expected) continue

            logger.lifecycle("Fetching Stockfish network $name …")
            URI("https://tests.stockfishchess.org/api/nn/$name").toURL().openStream()
                .use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }

            val actual = sha256Prefix(target)
            if (actual != expected) {
                target.delete()
                throw GradleException(
                    "$name failed verification: expected sha256 prefix $expected, got $actual"
                )
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(fetchStockfishNetworks) }

android {
    namespace = "gopesh.kibitz"
    compileSdk = 34
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "gopesh.kibitz"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        externalNativeBuild {
            cmake {
                // Stockfish picks its instruction set at compile time, so the flags live in
                // CMakeLists per ABI rather than here.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }

        ndk {
            // arm64 covers every current Android phone; x86_64 keeps Intel emulators usable.
            //
            // armeabi-v7a is deliberately absent even though CMakeLists can build it and does
            // so cleanly. Stockfish selects its instruction set at compile time with no runtime
            // dispatch, so a wrong flag is a SIGILL rather than a slow search — and the Kotlin
            // fallback only catches System.loadLibrary failing, not a native crash. There is no
            // 32-bit ARM hardware or emulator here to verify it on, and Play's ABI targeting
            // means 32-bit-only devices are simply not offered the app, which is better than
            // being offered one that dies. Add "armeabi-v7a" here to enable it once it can
            // actually be tested on a device.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // The NNUE assets are deliberately left compressed. They were assumed to be high-entropy
    // and excluded from compression; measured, the big net deflates 104 MB -> 70 MB, so storing
    // it uncompressed cost ~34 MB of download — enough to push the APK over Play's 100 MB limit
    // — to save inflating it once, during a copy the app performs anyway.

    testOptions {
        // Robolectric runs the Room database in JVM unit tests, so the history layer is
        // testable without a connected device.
        unitTests.isIncludeAndroidResources = true
    }

    signingConfigs {
        create("release") {
            // Signs only when a keystore has been placed here. No key material is generated,
            // committed or referenced by default; both files are gitignored.
            val keystore = rootProject.file("release.keystore")
            val properties = rootProject.file("keystore.properties")
            if (keystore.exists() && properties.exists()) {
                val loaded = Properties().apply {
                    properties.inputStream().use { load(it) }
                }
                storeFile = keystore
                storePassword = loaded.getProperty("storePassword")
                keyAlias = loaded.getProperty("keyAlias")
                keyPassword = loaded.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Null when no keystore has been supplied, which leaves the artifact unsigned
            // rather than failing the build. Assigning the config unconditionally breaks
            // `assembleRelease` for anyone who has not set up signing, including CI.
            signingConfig = signingConfigs.findByName("release")?.takeIf { it.storeFile != null }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    // Game and move history. Room rather than SharedPreferences because coaching asks real
    // questions of this data ("which mistakes recur?"), which wants SQL, not a blob.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Chess rules live in gopesh.kibitz.chess — no third-party rules engine.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}
