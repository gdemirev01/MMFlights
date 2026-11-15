plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    application
}

group = "mmflights"
version = "0.1.0"

repositories { mavenCentral() }

val arrow = "2.3.0"
val ktor = "3.3.1"

dependencies {
    implementation("io.arrow-kt:arrow-core:$arrow")
    implementation("io.arrow-kt:arrow-fx-coroutines:$arrow")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.charleskorn.kaml:kaml:0.104.0")
    implementation("io.ktor:ktor-client-core:$ktor")
    implementation("io.ktor:ktor-client-cio:$ktor")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-mock:$ktor")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

kotlin { jvmToolchain(21) }

application {
    mainClass.set("mmflights.app.MainKt")
    applicationName = "mmflights"
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.test { useJUnitPlatform() }
