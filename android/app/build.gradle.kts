plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "net.nether.vpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.nether.vpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.7-alpha"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Established Noise Protocol reference implementation; no custom crypto primitives.
    implementation("org.signal.forks:noise-java:0.1.1")
}
