plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.taller.otalmacen"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.taller.otalmacen"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        // Datos de Telegram: vienen de los secretos de GitHub al compilar (no están en el código)
        buildConfigField("String", "TG_BOT_TOKEN", "\"${System.getenv("TG_BOT_TOKEN") ?: ""}\"")
        buildConfigField("String", "TG_CHAT_ID", "\"${System.getenv("TG_CHAT_ID") ?: ""}\"")
        buildConfigField("String", "TG_FOTOS_KEY", "\"${System.getenv("TG_FOTOS_KEY") ?: ""}\"")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
