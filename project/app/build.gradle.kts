import java.util.Properties

plugins { id("com.android.application") }

// Release signing settings are read from signing/release.properties (git-ignored). Without it the release build stays unsigned.
val signingFile = rootDir.resolve("signing/release.properties")
val signing = Properties().apply { if (signingFile.isFile) signingFile.inputStream().use { load(it) } }

android {
    namespace = "io.github.myshihi.pleosproject26.n02vehiclekaraoke"
    compileSdk { version = release(37) }
    useLibrary("android.car")
    defaultConfig {
        applicationId = "io.github.myshihi.pleosproject26.n02vehiclekaraoke"
        minSdk = 30
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
    }
    signingConfigs {
        if (signing.getProperty("storeFile") != null) create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("androidx.media:media:1.6.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
