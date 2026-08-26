import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.services)
}

android {
    namespace = "com.ejemplo.locksuite"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ejemplo.locksuite"
        minSdk = 24
        targetSdk = 34
        versionCode = 95
        versionName = "0.6.32"

        ndk {
            // Antes solo arm64-v8a: la app no se podía instalar en NINGÚN equipo de 32
            // bits (armeabi-v7a), que sigue siendo común en celulares Android más
            // viejos/económicos — justo el perfil que muchas veces se reutiliza como
            // "celular kosher". Restaurado armeabi-v7a.
            // 🛑 Requiere una compilación de prueba: si la versión de MediaPipe
            // Tasks-Vision usada no publica binarios de 32 bits, el build puede fallar
            // o la Capa 2 (IA) puede fallar solo en esos equipos — en ese caso, la
            // alternativa es mantener solo arm64-v8a pero documentarlo como requisito
            // mínimo del producto en vez de que sea una limitación accidental.
            abiFilters.addAll(setOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    signingConfigs {
        create("release") {
            val properties = Properties()
            val localPropertiesFile = project.rootProject.file("local.properties")
            if (localPropertiesFile.exists()) {
                localPropertiesFile.inputStream().use { properties.load(it) }
            }
            keyAlias = properties.getProperty("RELEASE_KEY_ALIAS")
            keyPassword = properties.getProperty("RELEASE_KEY_PASSWORD")
            val storeFilePath = properties.getProperty("RELEASE_STORE_FILE")
                ?: throw GradleException("RELEASE_STORE_FILE debe configurarse en local.properties para compilar release")
            storeFile = file(storeFilePath)
            storePassword = properties.getProperty("RELEASE_STORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            // 🛑 Antes false: sin R8, decompilar el .apk devuelve nombres de clases,
            // métodos y campos EXACTAMENTE como en el código fuente (así se encontró
            // en segundos el problema de la contraseña maestra, ver informe de
            // auditoría §1.1/§1.6). Activado junto con reglas conservadoras en
            // proguard-rules.pro. IMPORTANTE: no se pudo compilar/probar un build de
            // Android en el entorno donde se hizo este cambio — antes de publicar,
            // generá un build de release real y probá TODAS las funciones a mano
            // (VPN, accesibilidad, FCM, actualización OTA, exportar/importar
            // presets), no solo que compile.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = false
        buildConfig = false
        shaders = false
    }

    androidResources {
        noCompress += "tflite"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Arch Components
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation("androidx.compose.material:material-icons-extended")
    // Tooling
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Instrumented tests
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Local tests: jUnit, coroutines, Android runner
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests: jUnit rules and runners
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)

    // Navigation
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    // Firebase (BoM)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.database)
    implementation(libs.firebase.messaging)
    implementation("com.google.firebase:firebase-auth")

    // Security (EncryptedSharedPreferences)
    implementation(libs.androidx.security.crypto)

    // WorkManager (Watchdog)
    implementation(libs.androidx.work.runtime.ktx)

    // MediaPipe Tasks-Vision (AI Image Blocker)
    implementation(libs.mediapipe.tasks.vision)
}






































