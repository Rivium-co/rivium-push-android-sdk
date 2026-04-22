plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish") version "0.30.0"
}

// SDK Version - MAJOR.MINOR.PATCH.BUILD
// Increment MAJOR for breaking changes
// Increment MINOR for new features
// Increment PATCH for bug fixes
// Increment BUILD for iterations/testing during development
val sdkVersion = "0.1.1"

android {
    namespace = "co.rivium.push.sdk"
    compileSdk = 34

    defaultConfig {
        // minSdk 21 = Android 5.0 Lollipop (99%+ device coverage)
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")

        // Dev server URL override — read from local.properties (gitignored).
        // Empty string means use production. Never set this in CI or release builds.
        buildConfigField("String", "DEV_SERVER_URL", "\"${project.findProperty("RIVIUM_PUSH_SERVER_URL") ?: ""}\"")
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
    }

    buildFeatures {
        buildConfig = true
    }

    // vanniktech plugin handles publishing configuration
}

// Publish to Maven Central
mavenPublishing {
    publishToMavenCentral(
        com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL,
        automaticRelease = true
    )
    signAllPublications()

    coordinates("co.rivium", "rivium-push-android", sdkVersion)

    pom {
        name.set("Rivium Push Android SDK")
        description.set("Real-time push notification SDK for Android. No Firebase dependency.")
        inceptionYear.set("2025")
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
    // PN Protocol - Rivium Push messaging protocol layer
    // Use project dependency for local development, Maven dependency for published builds
    val useLocalProtocol = findProject(":pn-protocol") != null
    if (useLocalProtocol) {
        implementation(project(":pn-protocol"))
    } else {
        implementation("co.rivium:pn-protocol:0.2.0")
    }

    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("androidx.cardview:cardview:1.0.0")

    // Lifecycle for app foreground/background detection
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Coroutines for async operations (image download, etc.)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // HTTP client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Image loading for in-app messages
    implementation("io.coil-kt:coil:2.5.0")

    // ==================== Testing ====================
    // Unit testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20231013")
    testImplementation("org.robolectric:robolectric:4.11.1")

    // Android instrumentation testing
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
