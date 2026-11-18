plugins {
    id("com.android.library")
}

android {
    namespace = "com.github.kr328.clash.service.stub"
    compileSdk {
        version = release(35)
    }
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
}
