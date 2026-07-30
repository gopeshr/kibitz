import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
            // Each extra ABI is another full Stockfish compile, so the list stays short.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    androidResources {
        // NNUE weights are high-entropy; deflating 104 MB buys almost nothing and costs
        // build time on every assemble.
        noCompress += "nnue"
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

    // Chess rules live in gopesh.kibitz.chess — no third-party rules engine.
    testImplementation("junit:junit:4.13.2")
}
