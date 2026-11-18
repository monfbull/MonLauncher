import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { kotlin("jvm") }

// Targets Java 17 like :app, and builds with whatever JDK (17 or newer) runs Gradle, rather than
// requiring a JDK 17 install through a toolchain.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies { testImplementation(kotlin("test")) }
tasks.test { useJUnitPlatform() }
