plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.example.packvideo"
    compileSdk = 34
    defaultConfig { applicationId = "com.example.packvideo"; minSdk = 29; targetSdk = 34; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    val cx = "1.3.4"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.camera:camera-core:$cx")
    implementation("androidx.camera:camera-camera2:$cx")
    implementation("androidx.camera:camera-lifecycle:$cx")
    implementation("androidx.camera:camera-video:$cx")
    implementation("androidx.camera:camera-view:$cx")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
