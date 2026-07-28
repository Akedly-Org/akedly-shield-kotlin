plugins {
    id("com.android.library") version "8.2.2"
    kotlin("android") version "1.9.22"
    `maven-publish`
}

group = "com.akedly"
version = "1.0.0"

android {
    namespace = "com.akedly.shield"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Expose a single publishable "release" component (required for from(components["release"])).
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // Passkey.kt (Context/Intent/Uri) + Turnstile.kt (WebView) need the Android runtime, and
    // Solver.kt + Turnstile.kt use kotlinx.coroutines — declare it (kotlinx-coroutines-android
    // bundles -core). The undeclared coroutines import + the missing Android classpath are the
    // two gaps that broke the build on the old kotlin("jvm") module.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.browser:browser:1.7.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

// The Android "release" software component only materializes after the android block evaluates,
// so wrap the whole publication registration in afterEvaluate (the canonical AGP-library form).
afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "com.akedly"
                artifactId = "shield"
                version = project.version.toString()
                from(components["release"])
            }
        }
    }
}
