import java.util.Properties
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

/** Supplies the PDFBox AAR whose bundled assets back the extraction test. */
val pdfboxTestAssets: Configuration by configurations.creating

/**
 * Where the SHERIF backend lives when `local.properties` does not say.
 *
 * Not a secret: it is the public hostname of a server. Point it at the
 * deployment with `SHERIF_API_BASE_URL` in `local.properties`.
 *
 * `.invalid` is reserved by RFC 2606 and can never resolve, so a release build
 * that was never configured fails loudly at the first request instead of
 * quietly talking to somebody else's server.
 */
val DEFAULT_API_BASE_URL = "https://api.sherif.invalid/"

/**
 * The same fallback for a debug build: the host machine of an Android emulator.
 *
 * 10.0.2.2 is the emulator's alias for the development machine itself, so a
 * backend started locally on port 8080 is reachable with no configuration at
 * all. Debug only, and only because `app/src/debug` permits cleartext to it --
 * a release build keeps [DEFAULT_API_BASE_URL] and must be pointed at an
 * https deployment.
 */
val DEBUG_API_BASE_URL = "http://10.0.2.2:8080/"

/**
 * `local.properties` is normally the Android SDK's file and is git-ignored, so
 * it is the right place for a per-machine backend address. It is read here
 * rather than inside `defaultConfig` because both build types need it.
 */
val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) {
        FileInputStream(propertiesFile).use { load(it) }
    }
}

/**
 * Applies Retrofit's one hard requirement on a base URL: the path must end in
 * `/`. Without it, `Retrofit.Builder().baseUrl(...)` throws at dependency
 * injection time and takes the whole app down, so the trailing slash is
 * normalised here instead of being left as a configuration trap.
 */
fun apiBaseUrl(raw: String): String = if (raw.endsWith("/")) raw else "$raw/"

/**
 * Unpacks only the PDFBox resource tree from the AAR onto the unit-test
 * classpath, with the `assets/` prefix stripped so PDFBox's own classpath
 * lookups resolve. Test-only: nothing here ships in the app.
 */
val extractPdfboxTestAssetsDir = layout.buildDirectory.dir("generated/pdfboxTestAssets")

val extractPdfboxTestAssets = tasks.register("extractPdfboxTestAssets") {
    val aarFiles = files(pdfboxTestAssets)
    val target = extractPdfboxTestAssetsDir
    inputs.files(aarFiles)
    outputs.dir(target)
    doLast {
        val root = target.get().asFile
        root.deleteRecursively()
        root.mkdirs()
        aarFiles.files.filter { it.name.endsWith(".aar") }.forEach { aarFile ->
            ZipFile(aarFile).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory || !entry.name.startsWith("assets/com/tom_roush/")) continue
                    val destination = File(root, entry.name.removePrefix("assets/"))
                    destination.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    // PDFBox ships its font metrics inside the AAR's assets, which are not on
    // the JVM classpath. Putting them on the test classpath lets the real
    // extraction path run on the JVM.
    dependsOn(extractPdfboxTestAssets)
    classpath += files(extractPdfboxTestAssetsDir)
    systemProperty(
        "pdfbox.testAssets",
        extractPdfboxTestAssetsDir.get().asFile.absolutePath
    )
}

android {
    namespace = "com.example.aiinterviewapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.aiinterviewapp"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Phase 3: there is deliberately no GEMINI_API_KEY buildConfigField.
        // A secret compiled into the APK is extractable, so the Gemini
        // credential lives only in the backend's environment. What the app is
        // allowed to know is *where* the backend is, which is public
        // information, not a secret.
        buildConfigField(
            "String",
            "SHERIF_API_BASE_URL",
            "\"${apiBaseUrl(localProperties.getProperty("SHERIF_API_BASE_URL") ?: DEFAULT_API_BASE_URL)}\""
        )
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${localProperties.getProperty("GOOGLE_CLIENT_ID") ?: ""}\"")

        // Development sign-in. Declared false here and overridden to true only
        // in the debug build type below, so a build type that nobody thought
        // about cannot inherit it: the safe value is the default and enabling
        // is the explicit act.
        //
        // `false` is a compile-time constant in release, so R8 folds the
        // `if` and drops the development entry along with the code behind it.
        buildConfigField("boolean", "DEV_AUTH_ENABLED", "false")
    }

    buildTypes {
        debug {
            // `local.properties` still wins; this only supplies the default for
            // a machine that has not been told where its backend is.
            buildConfigField(
                "String",
                "SHERIF_API_BASE_URL",
                "\"${apiBaseUrl(localProperties.getProperty("SHERIF_API_BASE_URL") ?: DEBUG_API_BASE_URL)}\""
            )

            // The only place this becomes true. The backend must also be started
            // with SHERIF_DEV_AUTH_ENABLED=true; the app showing a button that
            // the server refuses is a visible misconfiguration, which is safer
            // than the reverse.
            buildConfigField("boolean", "DEV_AUTH_ENABLED", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Stated explicitly rather than relying on the defaultConfig value:
            // this is the field a release build must never have, and it should
            // be possible to read that from this block alone.
            buildConfigField("boolean", "DEV_AUTH_ENABLED", "false")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Retrofit
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Authentication: Google Identity Services via Credential Manager.
    // These provide the ID token the backend verifies. They carry no secret.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    // Coil
    implementation(libs.coil.compose)

    // Serialization
    implementation(libs.kotlinx.serialization.json)
    
    // PDF
    implementation(libs.pdf.viewer)
    implementation(libs.pdfbox.android)
    implementation(libs.mlkit.text.recognition)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    pdfboxTestAssets(libs.pdfbox.android) {
        isTransitive = false
    }
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
