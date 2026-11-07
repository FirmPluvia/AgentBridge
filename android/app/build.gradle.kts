plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "dev.agentbridge"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { applicationId = "dev.agentbridge"; minSdk = 26; targetSdk = 36; versionCode = 14; versionName = "0.5"; ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") } }
    buildFeatures { compose = true; aidl = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST", "META-INF/*.kotlin_module") }

}
dependencies {
    implementation("top.yukonga.miuix.kmp:miuix:0.5.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    implementation("com.google.zxing:core:3.5.4")
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

// Gradle always emits an unsigned release. CI signs it later using GitHub Secrets.
// Namespace/classes stay stable while the installed release application ID is isolated.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.applicationId.set("starx.dev.agentbridge")
    }
}
