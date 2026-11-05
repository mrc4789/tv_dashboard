plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional signing: keep passwords and the existing keystore outside the repository.
val releaseSigningValues = listOf("TV_RELEASE_STORE_FILE", "TV_RELEASE_STORE_PASSWORD", "TV_RELEASE_KEY_ALIAS", "TV_RELEASE_KEY_PASSWORD")
    .associateWith { System.getenv(it).orEmpty() }
val hasReleaseSigning = releaseSigningValues.values.all { it.isNotBlank() }
require(hasReleaseSigning || releaseSigningValues.values.all { it.isBlank() }) {
    "Supply all four TV_RELEASE_* signing variables, or use Android Studio signing wizard."
}
android {
    namespace = "com.example.tvdashboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.tvdashboard"
        minSdk = 26
        targetSdk = 34
        versionCode = 17
        versionName = "0.5.9-cloud-pair"
        buildConfigField("String", "CLOUD_API_URL", "\"https://tv-dashboard-bwmp.onrender.com\"")

        buildConfigField("boolean", "ALLOW_CLEARTEXT_HTTP", "true")
        buildConfigField("boolean", "HOME_ASSIST_AVAILABLE", "false")

        // URL mặc định của dashboard. Đổi ở đây (hoặc cấu hình trong app từ Phase 2).
        buildConfigField("String", "DEFAULT_URL", "\"https://example.com\"")
    }

    signingConfigs {
        if (hasReleaseSigning) create("lanRelease") {
            storeFile = file(releaseSigningValues.getValue("TV_RELEASE_STORE_FILE"))
            storePassword = releaseSigningValues.getValue("TV_RELEASE_STORE_PASSWORD")
            keyAlias = releaseSigningValues.getValue("TV_RELEASE_KEY_ALIAS")
            keyPassword = releaseSigningValues.getValue("TV_RELEASE_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("lanRelease")
            isMinifyEnabled = false // sẽ bật ở Phase 6
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Optional build; standard release does not declare an accessibility service.
    buildTypes.create("homeAssistRelease") {
        initWith(buildTypes.getByName("release"))
        matchingFallbacks += listOf("release")
        buildConfigField("boolean", "HOME_ASSIST_AVAILABLE", "true")
        versionNameSuffix = "-home-assist"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // Real JSON for local JVM discovery tests
}
