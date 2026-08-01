plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.chloemlla.crooot"
    compileSdk = providers.gradleProperty("duckdetector.android.compileSdk").get().toInt()
    ndkVersion = providers.gradleProperty("duckdetector.android.ndk").get()

    defaultConfig {
        minSdk = providers.gradleProperty("duckdetector.android.minSdk").get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = providers.gradleProperty("duckdetector.android.cmake").get()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = false
        aidl = true
    }
    // Keep copied UI sources available for audit, but do not make Compose a host SDK dependency.
    sourceSets.getByName("main").java.exclude(
        "**/ui/**",
        "**/presentation/**",
        "**/MainActivity.kt",
        "**/MainViewModel.kt",
        "**/features/dashboard/**",
        "**/features/licenses/**",
        "**/features/settings/**",
        "**/core/ui/**",
        "**/core/notifications/**",
        "**/core/startup/legal/**",
    )
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.bundles.app.security)
    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = providers.gradleProperty("crooot.group").get()
            artifactId = "crooot-sdk"
            version = providers.gradleProperty("crooot.version").get()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("CRooot Android SDK")
                description.set("Combined Android root, integrity, runtime, SELinux, TEE and virtualization detection SDK")
                url.set("https://github.com/Chloemlla/CRooot")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri(System.getenv("GITHUB_SERVER_URL")?.let { "$it/${System.getenv("GITHUB_REPOSITORY") ?: "Chloemlla/CRooot"}/packages/maven" } ?: "https://maven.pkg.github.com/Chloemlla/CRooot")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: System.getenv("GH_TOKEN") ?: ""
            }
        }
    }
}
