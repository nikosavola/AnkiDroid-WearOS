plugins {
  alias(libs.plugins.android.application)
  // kotlin.android removed: AGP 9 built-in Kotlin (version pinned in the root buildscript
  // classpath).
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.ksp)
}

android {
  namespace = "fi.nikosavola.ankidroid.wearos.phone"
  // Compose 1.13 needs the 37.1+ minor SDK.
  compileSdk { version = release(37) { minorApiLevel = 2 } }
  // Match the NDK that cargo-ndk uses (ndk.dir) so AGP can strip the bundled .so and doesn't warn.
  ndkVersion = "27.0.12077973"

  defaultConfig {
    applicationId = "fi.nikosavola.ankidroid.wearos"
    minSdk = 26
    targetSdk = 37
    // Play requires distinct versionCodes per form factor within one listing (same applicationId
    // as wear/build.gradle.kts). Scheme: versionCode = release * 10 + formFactor (0 = phone, 1 =
    // wear), so bumping "release" together on both sides keeps them numerically ordered and never
    // collides. Release 2 -> phone 20, wear 21.
    versionCode = 20
    versionName = "1.1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  // ../bindings is generated UniFFI Kotlin. Under AGP 9 built-in Kotlin, extra dirs must be added
  // to
  // the *kotlin* source set (java.srcDirs no longer feeds Kotlin compilation as the KGP plugin
  // did).
  sourceSets { getByName("main") { kotlin.directories.add("../bindings") } }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      all {
        // Point JNA at the host-built libwearos_core.so so the Rust core is callable from JVM
        // tests.
        it.systemProperty("jna.library.path", rootProject.file("core/target/debug").absolutePath)
        it.dependsOn(":compileRustHost")
      }
    }
  }
}

kotlin { jvmToolchain(21) }

// The ../bindings UniFFI Kotlin must exist before Kotlin compiles. Match by task name rather than
// the KGP KotlinCompile type so it holds under AGP 9 built-in Kotlin (no KGP import on the
// classpath).
tasks
  .matching {
    (it.name.startsWith("compile") || it.name.startsWith("ksp")) && it.name.endsWith("Kotlin")
  }
  .configureEach { dependsOn(":generateBindings") }

ksp { arg("appfunctions:aggregateAppFunctions", "true") }

dependencies {
  implementation(project(":ankidroid-provider"))
  implementation(libs.kotlin.stdlib)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.material)

  // Jetpack Compose (Material 3) — phase-1 of the phone UI migration; hosts the animated deck tree.
  implementation(libs.androidx.activity.compose)
  implementation(libs.compose.foundation)
  implementation(libs.compose.ui)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.animation)
  implementation(libs.compose.material3)
  implementation(libs.compose.material3.adaptive)
  implementation(libs.compose.material3.adaptive.layout)
  implementation(libs.compose.material3.adaptive.navigation)

  implementation(libs.play.services.wearable)
  implementation(libs.androidx.work.runtime)
  implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.play.services)
  implementation(libs.androidx.appfunctions)
  ksp(libs.androidx.appfunctions.compiler)

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.mockk)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.test.ext.junit)
  // Plain desktop JNA jar so uniffi's Native.load works under the host test JVM.
  testImplementation(libs.jna.jvm)
  testImplementation(libs.compose.ui.test.junit4)
  debugImplementation(libs.compose.ui.test.manifest)

  // Instrumented tests run against a real AnkiDroid install on a device/emulator: they exercise the
  // ContentProvider contract (deck OPTIONS, card scheduler state) that unit tests can only fake.
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.runner)
}
