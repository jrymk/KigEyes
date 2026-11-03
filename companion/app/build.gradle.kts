plugins { id("com.android.application") }
android {
    namespace = "com.jerrymk.kigeyes"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.jerrymk.kigeyes"
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "0.3-test"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.media3:media3-exoplayer:1.9.3")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.9.3")
    implementation("androidx.media3:media3-ui:1.9.3")
    testImplementation("junit:junit:4.13.2")
}
