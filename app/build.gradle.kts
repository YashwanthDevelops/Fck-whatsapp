import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val pushEnabled = providers.gradleProperty("privateMessengerPushEnabled")
    .map { it.toBooleanStrict() }
    .getOrElse(false)
val pushMatrixDomain = providers.gradleProperty("privateMessengerMatrixDomain")
    .orNull
    ?.trim()
    .orEmpty()
val validPushMatrixDomain = pushMatrixDomain.isNotEmpty() && runCatching {
    val uri = URI("https://$pushMatrixDomain")
    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.isNullOrEmpty()
}.getOrDefault(false)
require(!pushEnabled || validPushMatrixDomain) {
    "privateMessengerPushEnabled requires privateMessengerMatrixDomain to be a DNS name (without scheme or path)."
}

// A missing local Firebase config leaves push unavailable without affecting ordinary builds.
val hasGoogleServicesConfig = file("google-services.json").isFile ||
    fileTree("src").matching { include("**/google-services.json") }.files.isNotEmpty()
val effectivePushEnabled = pushEnabled && validPushMatrixDomain && hasGoogleServicesConfig
if (effectivePushEnabled) {
    apply(plugin = "com.google.gms.google-services")
}
val splitApksForDistribution = providers.gradleProperty("privateMessengerSplitApks")
    .map { it.toBooleanStrict() }
    .getOrElse(false)

val localDebugKeystore = providers.environmentVariable("PRIVATE_MESSENGER_DEBUG_KEYSTORE").orNull
val localDebugKeystorePassword = providers.environmentVariable("PRIVATE_MESSENGER_DEBUG_KEYSTORE_PASSWORD").orNull ?: "android"
val privateReleaseKeystore = providers.environmentVariable("PRIVATE_MESSENGER_RELEASE_KEYSTORE").orNull
val privateReleaseKeystorePassword = providers.environmentVariable("PRIVATE_MESSENGER_RELEASE_KEYSTORE_PASSWORD").orNull
val privateReleaseKeyAlias = providers.environmentVariable("PRIVATE_MESSENGER_RELEASE_KEY_ALIAS").orNull
val privateReleaseKeyPassword = providers.environmentVariable("PRIVATE_MESSENGER_RELEASE_KEY_PASSWORD").orNull
val privateReleaseSigningValues = listOf(
    privateReleaseKeystore,
    privateReleaseKeystorePassword,
    privateReleaseKeyAlias,
    privateReleaseKeyPassword,
)
val privateReleaseSigningConfigured = privateReleaseSigningValues.all { !it.isNullOrBlank() }
require(privateReleaseSigningConfigured || privateReleaseSigningValues.all { it.isNullOrBlank() }) {
    "Private release signing requires the keystore path, store password, key alias, and key password."
}

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "dev.friendline.messenger"
    compileSdk = 37

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "dev.friendline.messenger"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "PUSH_ENABLED", effectivePushEnabled.toString())
        buildConfigField("String", "PUSH_MATRIX_DOMAIN", buildConfigString(pushMatrixDomain))
    }

    flavorDimensions += "lane"
    productFlavors {
        create("standard") {
            dimension = "lane"
        }
        create("outboxDiag") {
            dimension = "lane"
            applicationIdSuffix = ".outboxdiag"
        }
    }

    signingConfigs {
        if (localDebugKeystore != null) {
            create("localDebug") {
                storeFile = file(localDebugKeystore)
                storePassword = localDebugKeystorePassword
                keyAlias = "androiddebugkey"
                keyPassword = localDebugKeystorePassword
            }
        }
        if (privateReleaseSigningConfigured) {
            create("privateRelease") {
                storeFile = file(checkNotNull(privateReleaseKeystore))
                storePassword = checkNotNull(privateReleaseKeystorePassword)
                keyAlias = checkNotNull(privateReleaseKeyAlias)
                keyPassword = checkNotNull(privateReleaseKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (localDebugKeystore != null) {
                signingConfig = signingConfigs.getByName("localDebug")
            }
        }
        release {
            isMinifyEnabled = true
            if (privateReleaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("privateRelease")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    splits {
        abi {
            isEnable = splitApksForDistribution
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        // The Matrix Rust FFI is stripped with the pinned NDK before packaging; AGP's
        // host stripper cannot handle the GNU-built ELF produced on this Windows host.
        jniLibs.keepDebugSymbols += "**/libmatrix_sdk_ffi.so"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.firebase:firebase-messaging")
    // Locally generated from Matrix Rust SDK revision 2a3db80e with the
    // verified per-device call-key query patch. See ops/sdk-build/README.md.
    implementation(files("libs/sdk-android-private-2a3db80.aar"))
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("io.livekit:livekit-android:2.29.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json implementation is backed by mock methods in local JVM tests.
    // Use the compatible JSON.org implementation so protocol tests exercise real parsing.
    testImplementation("org.json:json:20250517")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
