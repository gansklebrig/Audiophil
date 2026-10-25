plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.eq.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.eq.app"
        minSdk = 26
        targetSdk = 34
        // Auf GitHub zählt jeder Build die Versionsnummer hoch, damit Android ihn als Update erkennt
        // (EQ_VERSION setzt der Workflow; lokal 0)
        val run = (System.getenv("EQ_VERSION") ?: "0").toInt()
        versionCode = 100 + run
        versionName = "1.$run"
    }

    // Fester Schlüssel: liegt verschlüsselt im Repo (keystore/equalizer.jks.enc) und wird auf GitHub
    // mit dem Secret KEYSTORE_PASSPHRASE entschlüsselt. Fehlt er (z. B. lokal), wird mit dem
    // Debug-Schlüssel signiert – so gebaute APKs lassen sich nicht über die GitHub-Version installieren.
    val eqKey = rootProject.file("keystore/equalizer.jks")
    signingConfigs {
        create("eq") {
            storeFile = rootProject.file("keystore/equalizer.jks")
            storePassword = "equalizer-gold"
            keyAlias = "equalizer"
            keyPassword = "equalizer-gold"
        }
    }

    buildTypes {
        debug {
            if (eqKey.exists()) signingConfig = signingConfigs.getByName("eq")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (eqKey.exists()) "eq" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // Hinweise anzeigen, aber den Build nicht daran scheitern lassen
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// Keine externen Bibliotheken nötig – alles mit dem Android-Framework.
