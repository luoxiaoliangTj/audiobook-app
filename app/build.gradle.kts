plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("kotlin-kapt")
}

android {
    namespace = "com.example.audiobook"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.audiobook"
        minSdk = 24
        targetSdk = 34
        versionCode = 100
        versionName = "1.3.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Read Baidu OCR keys from local.properties (not committed to git)
        val localProps = java.util.Properties()
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            localPropsFile.inputStream().use { localProps.load(it) }
        }
        buildConfigField("String", "BAIDU_OCR_API_KEY", "\"${localProps.getProperty("BAIDU_OCR_API_KEY", "")}\"")
        buildConfigField("String", "BAIDU_OCR_SECRET_KEY", "\"${localProps.getProperty("BAIDU_OCR_SECRET_KEY", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
        }
    }
    
    packagingOptions {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/MANIFEST.MF")
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0-beta02")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    // Room database (using KSP)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    // KSP for Room (faster than kapt)
    // Note: ksp plugin is applied in project-level build.gradle.kts
    // Lifecycle/ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.2")
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // OkHttp for Edge TTS
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // PDFBox Android port (classes.jar from pdfbox-android AAR)
    implementation(files("libs/pdfbox-aar/classes.jar"))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
