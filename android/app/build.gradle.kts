plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "starx.dev.agentbridge"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { applicationId = "starx.dev.agentbridge"; minSdk = 26; targetSdk = 36; versionCode = 10; versionName = "0.3.7"; ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") } }
    buildFeatures { compose = true; aidl = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST", "META-INF/*.kotlin_module") }
    val signingPath = System.getenv("AB_SIGNING_FILE")
    if (!signingPath.isNullOrBlank()) {
        signingConfigs.create("release") { storeFile = file(signingPath); storePassword = System.getenv("AB_STORE_PASSWORD"); keyAlias = System.getenv("AB_KEY_ALIAS"); keyPassword = System.getenv("AB_KEY_PASSWORD") }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
}
dependencies {
    implementation("top.yukonga.miuix.kmp:miuix:0.5.2")
    testImplementation("junit:junit:4.13.2")
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.github.topjohnwu.libsu:service:6.0.0")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.86")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
