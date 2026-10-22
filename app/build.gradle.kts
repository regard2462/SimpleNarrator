import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Signaturschlüssel für Release-Builds liegt außerhalb des Repos (keystore.properties ist ignoriert).
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

android {
    namespace = "app.simplenarrator"
    compileSdk = 35

    // Für Emulator-Tests mit wenig Speicher: ./gradlew -PonlyAbi=x86_64 :app:assembleDebug
    val onlyAbi = project.findProperty("onlyAbi") as String?

    defaultConfig {
        applicationId = "app.simplenarrator"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.6.1"
        // sherpa-onnx liefert native Libs; 64-bit reicht für aktuelle Geräte und hält die APK klein.
        ndk { abiFilters += listOf(onlyAbi ?: "arm64-v8a") }
    }

    androidResources { generateLocaleConfig = true }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Zusätzlich x86_64, damit die Debug-APK auch im Android-Emulator läuft.
            ndk { if (onlyAbi == null) abiFilters += listOf("x86_64") }
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    // Die Sprache lässt sich in der App wechseln; App-Bundles müssen deshalb alle Sprachen enthalten.
    bundle { language { enableSplit = false } }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    // --- Compose ---
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // --- AndroidX ---
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // --- Wiedergabe (ExoPlayer + MediaSession: Sperrbildschirm, Bluetooth, Android Auto) ---
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")

    // --- Kokoro TTS on-device (ONNX Runtime + espeak-ng, gebündelt in sherpa-onnx) ---
    // Offizieller Weg (JitPack, wie android/SherpaOnnxJavaDemo im sherpa-onnx-Repo). Neuere Version: Release-Tag auf
    // https://github.com/k2-fsa/sherpa-onnx/releases eintragen. Alternative über Maven Central:
    //   implementation("com.k2fsa.sherpa.onnx:sherpa-onnx-android:1.13.2")
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")

    // --- Texterkennung für gescannte PDFs (Tesseract, offline; Sprachdaten lädt die App bei Bedarf) ---
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")

    // --- Dokumente ---
    implementation("org.jsoup:jsoup:1.18.3")                       // EPUB (XHTML/OPF)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")        // PDF-Textextraktion
    implementation("org.apache.commons:commons-compress:1.27.1")   // tar.bz2 Modellpakete entpacken

    // --- MP3 (reiner Java-Port von LAME; falls das Artefakt nicht auflöst, Zeile entfernen – M4A/WAV gehen weiter) ---
    implementation("de.sciss:jump3r:1.0.5")

    // --- Netz / Daten ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // --- Tests (Normalisierer, Zahlwörter, Segmentierer, Parser) ---
    testImplementation("junit:junit:4.13.2")
}
