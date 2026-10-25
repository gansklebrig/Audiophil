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
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Zum einfachen Installieren mit dem Debug-Schlüssel signieren
            signingConfig = signingConfigs.getByName("debug")
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
