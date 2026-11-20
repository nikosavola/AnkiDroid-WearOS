plugins {
  alias(libs.plugins.android.library)
  `maven-publish`
}

android {
  namespace = "fi.nikosavola.ankidroid.provider"
  compileSdk { version = release(37) { minorApiLevel = 2 } }

  defaultConfig { minSdk = 26 }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }

  publishing { singleVariant("release") { withSourcesJar() } }
}

kotlin { jvmToolchain(21) }

dependencies {
  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
}

publishing {
  publications {
    create<MavenPublication>("release") {
      groupId = "fi.nikosavola"
      artifactId = "ankidroid-provider"
      version = providers.gradleProperty("releaseVersion").getOrElse("0.0.0-SNAPSHOT")
      afterEvaluate { from(components["release"]) }
      pom {
        name = "ankidroid-provider"
        description = "Blocking Kotlin client for AnkiDroid's flashcard ContentProvider."
        url = "https://github.com/nikosavola/Wear-for-AnkiDroid"
        licenses {
          license {
            name = "LGPL-3.0-or-later"
            url = "https://www.gnu.org/licenses/lgpl-3.0.html"
          }
        }
        scm {
          url = "https://github.com/nikosavola/Wear-for-AnkiDroid"
          connection = "scm:git:https://github.com/nikosavola/Wear-for-AnkiDroid.git"
          developerConnection = "scm:git:ssh://git@github.com/nikosavola/Wear-for-AnkiDroid.git"
        }
      }
    }
  }
  repositories {
    maven {
      name = "GitHubPackages"
      url = uri("https://maven.pkg.github.com/nikosavola/Wear-for-AnkiDroid")
      credentials {
        username = System.getenv("GITHUB_ACTOR")
        password = System.getenv("GITHUB_TOKEN")
      }
    }
  }
}
