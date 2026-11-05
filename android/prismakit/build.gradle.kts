// Pure-Kotlin filter engine: no Android dependency, so it is unit-tested on the JVM.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

// Target Java 17 bytecode (what Android consumes); builds with any JDK 17+.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Reference outputs generated from the JS engine (npm run fixtures).
    systemProperty("fixtures.dir", rootProject.projectDir.resolve("../fixtures").canonicalPath)
}
