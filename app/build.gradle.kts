plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.freecaster.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.freecaster.app"
        minSdk = 26
        targetSdk = 35
        // Номер збірки GitHub Actions: по ньому додаток розуміє, що є оновлення
        val build = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.$build"
    }

    // Постійний ключ із секретів GitHub. Без нього оновлення не встановиться поверх старої версії
    val keystore = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        create("release") {
            if (keystore != null) {
                storeFile = file(keystore)
                storeType = "pkcs12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "freecaster"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Без секрету підписуємо debug-ключем, щоб APK усе одно можна було встановити
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.github.mwiede:jsch:0.2.20")
}
