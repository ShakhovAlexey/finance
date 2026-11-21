plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// CI: GITHUB_RUN_NUMBER 1,2,3… → versions 1.0.2, 1.0.3…; local build = 1.0.1
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0) + 1
// Keystore password is never stored in the repo: GitHub secret KEYSTORE_PASSWORD (or a local file outside git)
val keyPass: String = System.getenv("KEYSTORE_PASSWORD")
    ?: rootProject.file("../.finance_keypass").takeIf { it.exists() }?.readText()?.trim() ?: ""

android {
    namespace = "com.shakhov.finance"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.shakhov.finance"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    signingConfigs {
        create("release") {
            storeFile = file("../keystore/finance.jks")
            storePassword = keyPass
            keyAlias = "finance"
            keyPassword = keyPass
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    androidResources { noCompress += "traineddata" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources { excludes += setOf("META-INF/*") } }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.8.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
}
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
    maxHeapSize = "2g"
}
