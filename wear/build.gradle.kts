plugins {
  alias(libs.plugins.android.application)
  // kotlin.android removed: AGP 9 built-in Kotlin (version pinned in the root buildscript
  // classpath).
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.ksp)
}

android {
  namespace = "com.ichi2.anki.wearos.wear"
  // Compose 1.13 needs the 37.1+ minor SDK.
  compileSdk { version = release(37) { minorApiLevel = 2 } }
  // Match the NDK that cargo-ndk uses (ndk.dir) so AGP can strip the bundled .so and doesn't warn.
  ndkVersion = "27.0.12077973"

  defaultConfig {
    applicationId = "com.ichi2.anki.wearos"
    minSdk = 30
    targetSdk = 37
    // Play requires distinct versionCodes per form factor within one listing (same applicationId
    // as phone/build.gradle.kts). Scheme: versionCode = release * 10 + formFactor (0 = phone, 1 =
    // wear), so bumping "release" together on both sides keeps them numerically ordered and never
    // collides. Release 2 -> phone 20, wear 21.
    versionCode = 21
    versionName = "1.1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    // Release code (R8, no debuggable) signed with the debug key, for frame timing and baseline
    // profiles. Debug Compose is too slow to profile meaningfully.
    create("benchmark") {
      initWith(getByName("release"))
      signingConfig = signingConfigs.getByName("debug")
      matchingFallbacks += "release"
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
  buildFeatures { compose = true }
  // ../bindings is generated UniFFI Kotlin. Under AGP 9 built-in Kotlin, extra dirs must be added
  // to
  // the *kotlin* source set (java.srcDirs no longer feeds Kotlin compilation as the KGP plugin
  // did).
  sourceSets { getByName("main") { kotlin.directories.add("../bindings") } }

  testOptions {
    unitTests {
      // Robolectric needs the merged Android resources; Compose-under-Robolectric needs them too.
      isIncludeAndroidResources = true
      all {
        // Point JNA at the host-built libwearos_core.so so the Rust core is callable from JVM
        // tests.
        it.systemProperty("jna.library.path", rootProject.file("core/target/debug").absolutePath)
        // Build the host .so before running tests that call into the core.
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
  implementation(libs.kotlin.stdlib)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  implementation(libs.wear.compose.material3)
  implementation(libs.wear.compose.foundation)
  implementation(libs.wear.compose.navigation3)
  implementation(libs.navigation3.runtime)
  implementation(libs.navigation3.ui)
  implementation(libs.wear.tiles)
  implementation(libs.wear.protolayout)
  implementation(libs.wear.protolayout.core)
  implementation(libs.wear.watchface.complications.data.source)
  implementation(libs.glance.wear)
  implementation(libs.glance.wear.core)
  implementation(libs.remote.creation.compose)
  implementation(libs.remote.core)
  implementation(libs.wear.compose.remote.material3)
  // Pulls ui-tooling (and its exported PreviewActivity), so keep it out of release builds.
  debugImplementation(libs.glance.wear.tooling.preview)
  testImplementation(libs.glance.wear.tooling.preview)

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
  testImplementation(libs.androidx.work.testing)
  // Plain desktop JNA jar so uniffi's Native.load works under the host test JVM.
  testImplementation(libs.jna.jvm)
  // Compose UI testing (runs under Robolectric).
  testImplementation(libs.compose.ui.test.junit4)
  debugImplementation(libs.compose.ui.test.manifest)
}
