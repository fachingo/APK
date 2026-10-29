import java.io.File
import java.security.MessageDigest

plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.compose"); kotlin("kapt") }
android {
    namespace = "ca.canadavhs.snapmobile"
    compileSdk = 37
    defaultConfig {
        applicationId = "ca.canadavhs.snapmobile"
        minSdk = 28
        targetSdk = 36
        ndk { abiFilters += "arm64-v8a" }
        versionCode = 11
        versionName = "0.8.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    signingConfigs.getByName("debug") { storeFile = File(System.getenv("ANDROID_USER_HOME") ?: "${System.getProperty("user.home")}/.android", "debug.keystore") }
    buildTypes { release { isMinifyEnabled = false } }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { jvmToolchain(21) }
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("androidx.camera:camera-extensions:1.6.2")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    kapt("androidx.room:room-compiler:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation(files("libs/tesseract4android-4.9.0-arm64-v8a.aar"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.commonmark:commonmark:0.31.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.room:room-testing:2.8.5")
    testImplementation("androidx.work:work-testing:2.12.0")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

// Give the host-side Android runtime a writable cache without changing shell HOME.
tasks.withType<Test>().configureEach {
    // Robolectric/WorkManager keep process-global schedulers. Separate classes
    // prevent teardown callbacks from crossing native test-runtime boundaries.
    forkEvery = 1
    maxParallelForks = 1
    val testHome = layout.buildDirectory.dir("test-home").get().asFile
    doFirst { testHome.mkdirs() }
    systemProperty("user.home", testHome.absolutePath)
    systemProperty("snapims.schemaDir", layout.projectDirectory.dir("schemas").asFile.absolutePath)
    providers.gradleProperty("marketCsv").orNull?.let {systemProperty("snapims.marketCsv",it)}
    systemProperty("snapims.testOutput", layout.buildDirectory.dir("fixture-output").get().asFile.absolutePath)
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
}


val verifyOfflineRecognitionArtifacts = tasks.register("verifyOfflineRecognitionArtifacts") {
    val nativeLibrary = layout.projectDirectory.file("libs/tesseract4android-4.9.0-arm64-v8a.aar").asFile
    val expectedFile = layout.projectDirectory.file("libs/tesseract4android-4.9.0-arm64-v8a.aar.sha256").asFile
    val language = layout.projectDirectory.file("src/main/assets/tessdata/eng.traineddata").asFile
    inputs.files(nativeLibrary, expectedFile, language)
    doLast {
        fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        check(digest(nativeLibrary) == expectedFile.readText().trim().substringBefore(' ')) { "Vendored Tesseract AAR integrity check failed." }
        check(digest(language) == "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2") { "English OCR data integrity check failed." }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyOfflineRecognitionArtifacts) }
