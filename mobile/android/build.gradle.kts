import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

buildscript {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases")
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.4.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.0")
        classpath("org.jetbrains.kotlin:kotlin-serialization:2.2.0")
        classpath("com.github.kr328.golang:gradle-plugin:1.0.4")
    }
}

subprojects {
    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension> {
            namespace = when (name) {
                "hideapi" -> "com.github.kr328.clash.hideapi"
                else -> "com.github.kr328.clash.$name"
            }
            buildFeatures.buildConfig = true
            compileSdk = 35
            ndkVersion = "28.2.13676358"

            defaultConfig {
                minSdk = 24

                ndk {
                    abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                }

                externalNativeBuild {
                    cmake {
                        abiFilters("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                    }
                }

                consumerProguardFiles("consumer-rules.pro")
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
        }
    }

    plugins.withId("com.android.application") {
        extensions.configure<ApplicationExtension> {
            buildFeatures.buildConfig = true
            compileSdk = 35
            ndkVersion = "28.2.13676358"

            defaultConfig {
                minSdk = 24
                versionCode = 1
                versionName = "1.0.0"

                ndk {
                    abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                }
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
        }
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
