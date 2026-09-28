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
        
        val properties = Properties()
        val propertiesFile = rootProject.file("local.properties")
        if (propertiesFile.exists()) {
            FileInputStream(propertiesFile).use { properties.load(it) }
        }
        buildConfigField("String", "GEMINI_API_KEY", "\"${properties.getProperty("GEMINI_API_KEY") ?: ""}\"")
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${properties.getProperty("GOOGLE_CLIENT_ID") ?: ""}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
