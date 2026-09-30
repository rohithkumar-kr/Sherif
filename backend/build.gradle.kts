/**
 * The SHERIF backend: the trusted server that owns the Gemini credential.
 *
 * Deliberately dependency-free beyond the Kotlin standard library and
 * kotlinx-serialization. The whole point of this module is to be the one place
 * a Gemini API key exists, so it is kept small enough to audit by reading: a
 * JDK `HttpServer`, a handful of filters, and one outbound HTTP client.
 *
 * It is a plain JVM module rather than a framework (Ktor/Spring) because none of
 * the required behaviour needs one, and every transitive dependency is another
 * supply-chain holder of a production secret.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
