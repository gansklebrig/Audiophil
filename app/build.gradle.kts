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
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
        versionCode = 100 + run
        versionName = "1.$run"
    }

    // Fester Schlüssel aus dem Repo: Builds von GitHub und aus Android Studio
    // lassen sich so gegenseitig als Update drüberinstallieren.
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
            signingConfig = signingConfigs.getByName("eq")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("eq")
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
