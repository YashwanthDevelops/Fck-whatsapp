plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localDebugKeystore = providers.environmentVariable("PRIVATE_MESSENGER_DEBUG_KEYSTORE").orNull
val localDebugKeystorePassword = providers.environmentVariable("PRIVATE_MESSENGER_DEBUG_KEYSTORE_PASSWORD").orNull ?: "android"

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
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
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
    implementation("org.matrix.rustcomponents:sdk-android:26.09.9")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
