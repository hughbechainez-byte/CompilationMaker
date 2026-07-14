plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.compilationmaker"
    compileSdk = 35

    signingConfigs {
        create("release") {
            val keystorePath = providers.environmentVariable("COMPILATIONMAKER_KEYSTORE_PATH").orNull
            val storePasswordValue = providers.environmentVariable("COMPILATIONMAKER_STORE_PASSWORD").orNull
            val keyAliasValue = providers.environmentVariable("COMPILATIONMAKER_KEY_ALIAS").orNull
            val keyPasswordValue = providers.environmentVariable("COMPILATIONMAKER_KEY_PASSWORD").orNull
            if (!keystorePath.isNullOrBlank() && !storePasswordValue.isNullOrBlank() &&
                !keyAliasValue.isNullOrBlank() && !keyPasswordValue.isNullOrBlank()
            ) {
                storeFile = file(keystorePath)
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    defaultConfig {
        applicationId = "com.hughbechainez.compilationmaker"
        minSdk = 24
        targetSdk = 35
        versionCode = 52
        versionName = "0.17.20"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testBuildType = "release"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        getByName("debug") {
        }

        release {
            signingConfig = signingConfigs.getByName("release")
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
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.6.1")
}

val releaseSigningVariables = listOf(
    "COMPILATIONMAKER_KEYSTORE_PATH",
    "COMPILATIONMAKER_STORE_PASSWORD",
    "COMPILATIONMAKER_KEY_ALIAS",
    "COMPILATIONMAKER_KEY_PASSWORD"
)
tasks.configureEach {
    if (name.contains("Release", ignoreCase = true)) {
        doFirst {
            check(releaseSigningVariables.all { !providers.environmentVariable(it).orNull.isNullOrBlank() }) {
                "Release signing requires ${releaseSigningVariables.joinToString()}."
            }
        }
    }
}
