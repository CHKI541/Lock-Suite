import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.ejemplo.locksuite.admin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ejemplo.locksuite.admin"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
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
                ?: throw GradleException("RELEASE_STORE_FILE debe configurarse en local.properties")
            storeFile = file(storeFilePath)
            storePassword = properties.getProperty("RELEASE_STORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            // 🛑 Antes estaba en false. Sin R8, decompilar el .apk devuelve los nombres
            // de clases y métodos tal cual el código fuente — incluidas las listas
            // blancas de dominios, que es justo el mapa que necesitaría alguien que
            // quiera saber por dónde escaparse. Reglas conservadoras en
            // proguard-rules.pro (esta app no tiene reflexión ni puentes JS).
            //
            // Si el build falla o la app arranca en negro después de este cambio:
            // poner las dos líneas en false y avisar — no es un cambio funcional,
            // se puede revertir sin tocar nada más.
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

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
