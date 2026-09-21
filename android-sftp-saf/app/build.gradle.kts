plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.simonsimon006.sftpsaf"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.simonsimon006.sftpsaf"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // R8 is off on purpose: BouncyCastle resolves most of its algorithm
            // implementations reflectively, so shrinking it needs keep rules that
            // are easy to get subtly wrong and only blow up at runtime. See
            // proguard-rules.pro if you want to trade that risk for ~half the APK.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/INDEX.LIST",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/versions/**",
            "module-info.class",
        )
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("com.hierynomus:sshj:0.40.0")
    // sshj needs a full BouncyCastle; the one Android bundles is a cut-down build.
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    // ed25519 host and user keys
    implementation("net.i2p.crypto:eddsa:0.3.0")
    implementation("org.slf4j:slf4j-nop:2.0.17")
}
