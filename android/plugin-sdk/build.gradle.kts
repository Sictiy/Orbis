import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

val sdkVersion = Properties().apply {
    file("sdk-version.properties").inputStream().use(::load)
}.getProperty("version") ?: error("plugin-sdk version is missing")
group = "com.orbising.orbis"
version = sdkVersion

android {
    namespace = "com.orbising.orbis.plugin.sdk"
    compileSdk = 35

    defaultConfig { minSdk = 26 }
    buildFeatures { aidl = true }
    publishing { singleVariant("release") { withSourcesJar() } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// This is a local repository, not a public SDK release. A consumer selects and
// verifies the exact artifact; rebuilding this project does not update it.
publishing {
    repositories {
        maven {
            name = "localSdk"
            url = uri(providers.gradleProperty("orbisSdkPublishRepo").orNull
                ?: layout.buildDirectory.dir("local-maven").get().asFile)
        }
    }
    publications {
        afterEvaluate {
            create<MavenPublication>("release") { from(components["release"]) }
        }
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
}
