import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing: ~/.local/android/release-keys/bastion.properties (or the
// file named by BASTION_ANDROID_SIGNING_PROPERTIES), or the same keys as
// environment variables. Without them, release builds use the debug key.
val releaseSigningProperties = Properties()
val releaseSigningPropertiesFile = file(
    System.getenv("BASTION_ANDROID_SIGNING_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.local/android/release-keys/bastion.properties"
)
if (releaseSigningPropertiesFile.isFile) {
    releaseSigningPropertiesFile.inputStream().use(releaseSigningProperties::load)
}

fun signingValue(name: String): String? = System.getenv(name) ?: releaseSigningProperties.getProperty(name)

android {
    namespace = "io.github.carnager.bastion"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "io.github.carnager.bastion"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // termlib ships libvterm for every ABI; phones are arm64.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("release") {
            signingValue("BASTION_ANDROID_STORE_FILE")?.let { storeFile = file(it) }
            storePassword = signingValue("BASTION_ANDROID_STORE_PASSWORD")
            keyAlias = signingValue("BASTION_ANDROID_KEY_ALIAS")
            keyPassword = signingValue("BASTION_ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signingValue("BASTION_ANDROID_STORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core:1.18.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment:1.9.1") // BiometricPrompt needs a FragmentActivity
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")

    // Terminal: Compose component on libvterm (ConnectBot, Apache-2.0).
    implementation("org.connectbot:termlib:0.3.15")
    // SSH: Apache MINA SSHD, with the full BouncyCastle provider and i2p EdDSA for Ed25519 keys.
    implementation("org.apache.sshd:sshd-core:2.20.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.87")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.87")
    implementation("net.i2p.crypto:eddsa:0.3.0")

    testImplementation("junit:junit:4.13.2")
}
