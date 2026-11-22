plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// साइनिंग की चाबी रिपॉज़िटरी में नहीं रहती। GitHub Secrets से आए तो इस्तेमाल होती है,
// वरना सामान्य debug चाबी से APK बनता है (तब हर नए वर्ज़न पर पुराना ऐप हटाना पड़ेगा)।
val ksPath: String? = System.getenv("JARVIS_KEYSTORE_FILE")
val hasKs = !ksPath.isNullOrEmpty() && file(ksPath).exists()

android {
    namespace = "com.yesmin.jarvis"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yesmin.jarvis"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasKs) {
            create("jarvis") {
                storeFile = file(ksPath!!)
                storePassword = System.getenv("JARVIS_KEYSTORE_PASS")
                keyAlias = System.getenv("JARVIS_KEY_ALIAS")
                keyPassword = System.getenv("JARVIS_KEY_PASS")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasKs) signingConfig = signingConfigs.getByName("jarvis")
        }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
