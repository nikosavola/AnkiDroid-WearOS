plugins {
  alias(libs.plugins.android.application)
  // kotlin.android removed: AGP 9 built-in Kotlin (version pinned in the root buildscript
  // classpath).
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "fi.nikosavola.ankidroid.wearos.wear"
  compileSdk = 36
  // Match the NDK that cargo-ndk uses (ndk.dir) so AGP can strip the bundled .so and doesn't warn.
  ndkVersion = "27.0.12077973"

  defaultConfig {
    applicationId = "fi.nikosavola.ankidroid.wearos"
    minSdk = 30
    targetSdk = 36
    versionCode = 2
    versionName = "1.1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
  .matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }
  .configureEach { dependsOn(":generateBindings") }

dependencies {
  implementation(libs.kotlin.stdlib)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  implementation(libs.wear.compose.material3)
  implementation(libs.wear.compose.foundation)
  implementation(libs.wear.tiles)
  implementation(libs.wear.protolayout)
  implementation(libs.wear.protolayout.core)

  implementation(libs.play.services.wearable)
  implementation("net.java.dev.jna:jna:5.14.0@aar")
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.play.services)

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.mockk)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.test.ext.junit)
  // Plain desktop JNA jar so uniffi's Native.load works under the host test JVM.
  testImplementation(libs.jna.jvm)
  // Compose UI testing (runs under Robolectric).
  testImplementation(libs.compose.ui.test.junit4)
  debugImplementation(libs.compose.ui.test.manifest)
}
