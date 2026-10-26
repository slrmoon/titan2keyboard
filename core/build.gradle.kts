// Pure Kotlin: layouts, typo model, dictionary and prediction. No Android dependency, so it is
// unit-tested on the JVM.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // tests read the bundled dictionaries and layouts from ../app/src/main/assets
    workingDir = projectDir
    jvmArgs("-Xss8m")
    testLogging { events("passed", "failed"); showStandardStreams = false; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
