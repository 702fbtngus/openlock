import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
val buildRevision = providers.environmentVariable("GITHUB_SHA").orNull
    ?.takeIf { it.matches(Regex("[0-9a-fA-F]{40,64}")) }?.take(12) ?: "local"
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "dev.abdullah.openlock"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = "29.0.13113456"

    compileOptions {
        // Desugaring lets androidx.security / Tink use newer Java APIs on
        // older Android versions.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_21.toString()
    }

    buildFeatures {
        // BuildConfig.DEBUG gates the native timing logs.
        buildConfig = true
    }

    // The upstream native starter is an executable .so. It must be extracted
    // into nativeLibraryDir, where the ADB startup command can execute it.
    packaging {
        jniLibs.useLegacyPackaging = true
        // Duplicate OSGi bundle metadata in the upstream crypto jars is not
        // used by Android. Keep other resources and service metadata intact.
        resources.excludes += "META-INF/versions/**/OSGI-INF/MANIFEST.MF"
    }

    defaultConfig {
        applicationId = "dev.abdullah.openlock"
        // EncryptedSharedPreferences (AndroidKeyStore AES) needs API 23+.
        minSdk = maxOf(24, flutter.minSdkVersion)
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        buildConfigField("String", "BUILD_REVISION", "\"$buildRevision\"")
        // Opt-in OEM screen diagnostics, also available in preview release APKs.
        buildConfigField("boolean", "GUARD_DIAGNOSTICS",
            (providers.gradleProperty("guardDiagnostics").orNull == "true").toString())
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                keystoreProperties.getProperty("storeType")?.let { storeType = it }
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // Signs with the release keystore when android/key.properties exists
            // (created by the Release workflow from repo secrets); falls back to
            // debug keys otherwise so local `flutter run --release` still works.
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation("dev.abdullah.openlock.shizuku:embedded:1.0")
    // Supply hidden-API signatures to R8 without packaging Android's classes.
    compileOnly("dev.rikka.hidden:stub:4.4.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    // Encrypted config store the native monitor reads (EncryptedSharedPreferences).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Fingerprint / face unlock on the lock screen.
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.core:core-ktx:1.13.1")

    // JVM unit tests for the pure enforcement logic (notification redaction and
    // the uninstall-screen matcher). Run in CI via `gradlew testDebugUnitTest`.
    testImplementation("junit:junit:4.13.2")
    // Real JSON parsing for schedule tests instead of Android's throwing stubs.
    testImplementation("org.json:json:20240303")
}

// Shizuku's original UI uses the Rikka AppCompat fork, which supplies the same
// classes. Exclude the standard artifact across the host's dependency graph.
configurations.configureEach {
    exclude(group = "androidx.appcompat", module = "appcompat")
}
