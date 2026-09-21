# Only relevant if you flip isMinifyEnabled to true in app/build.gradle.kts.
# BouncyCastle looks its algorithms up by class name at runtime.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }
# sshj instantiates transport/key factories through its DefaultConfig factories.
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.** { *; }
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
