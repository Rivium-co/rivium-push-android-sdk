plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish") version "0.30.0"
}

// SDK Version - MAJOR.MINOR.PATCH
// Requires a rivium-push-android release that includes RiviumPushTransports.
val sdkVersion = "0.1.0"

android {
    namespace = "co.rivium.push.fcm"
    compileSdk = 34

    defaultConfig {
        // Same as rivium-push; Firebase BoM 33.x still supports API 21
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-opt-in=co.rivium.push.sdk.RiviumPushInternalApi"
    }
}

// Publish to Maven Central
mavenPublishing {
    publishToMavenCentral(
        com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL,
        automaticRelease = true
    )
    signAllPublications()

    coordinates("co.rivium", "rivium-push-fcm", sdkVersion)

    pom {
        name.set("Rivium Push FCM Add-on")
        description.set("Optional Firebase Cloud Messaging transport for the Rivium Push Android SDK.")
        inceptionYear.set("2026")
        url.set("https://rivium.co/cloud/rivium-push")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("rivium")
                name.set("Rivium")
                email.set("founder@rivium.co")
                url.set("https://rivium.co")
            }
        }

        scm {
            url.set("https://github.com/Rivium-co/rivium-push-android-sdk")
            connection.set("scm:git:git://github.com/Rivium-co/rivium-push-android-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/Rivium-co/rivium-push-android-sdk.git")
        }
    }
}

dependencies {
    // Rivium Push core SDK
    api(project(":rivium-push"))

    // Firebase Cloud Messaging (BoM 33.x keeps minSdk 21)
    implementation(platform("com.google.firebase:firebase-bom:33.16.0"))
    implementation("com.google.firebase:firebase-messaging")

    // Auto-initialisation without app code
    implementation("androidx.startup:startup-runtime:1.1.1")

    // ==================== Testing ====================
    testImplementation("junit:junit:4.13.2")
}
