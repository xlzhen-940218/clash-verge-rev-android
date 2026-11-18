pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases")
        google()
        mavenCentral()
    }
}

rootProject.name = "ClashVergeAndroid"

include(":app")
include(":service")

// CMFA modules
include(":core")
project(":core").projectDir = file("cmfa/core")

include(":common")
project(":common").projectDir = file("cmfa/common")

include(":hideapi")
project(":hideapi").projectDir = file("cmfa/hideapi")
