import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { kotlin("jvm") }

// Targets Java 17 like :app. No toolchain, so any JDK 17 or newer can build it.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies { testImplementation(kotlin("test")) }
tasks.test { useJUnitPlatform() }
