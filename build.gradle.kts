plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    application
}

group = "family.geraghty.ed.yolo.ferretcannon"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
    implementation("io.ktor:ktor-server-netty:2.3.8")
    implementation("io.ktor:ktor-server-core:2.3.8")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.8")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.8")
    implementation("io.ktor:ktor-server-websockets:2.3.8")
    implementation("io.ktor:ktor-server-auth:2.3.8")
    implementation("io.ktor:ktor-server-auth-jwt:2.3.8")
    implementation("io.ktor:ktor-client-core:2.3.8")
    implementation("io.ktor:ktor-client-cio:2.3.8")
    implementation("io.ktor:ktor-client-okhttp:2.3.8")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.8")
    implementation("io.ktor:ktor-server-cors:2.3.8")
    implementation("io.ktor:ktor-server-rate-limit:2.3.8")
    implementation("io.ktor:ktor-server-plugins:2.3.8")
    implementation("org.xerial:sqlite-jdbc:3.42.0.0")
    implementation("org.jetbrains.exposed:exposed-core:0.41.1")
    implementation("org.jetbrains.exposed:exposed-dao:0.41.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.41.1")
    implementation("net.i2p.crypto:eddsa:0.3.0") // For ed25519 signatures
    implementation("dnsjava:dnsjava:3.5.2") // For DNS SRV record lookup
    implementation("com.github.jai-imageio:jai-imageio-core:1.4.0") // For image processing
    implementation("org.imgscalr:imgscalr-lib:4.2") // For image scaling/thumbnail generation
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2") // For JSON/YAML parsing
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2") // For YAML support
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2") // For Kotlin support
    implementation("at.favre.lib:bcrypt:0.10.2") // For password hashing
    implementation("org.bouncycastle:bcprov-jdk18on:1.76") // For cryptographic operations
    // Logging dependencies
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.14")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("MainKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "MainKt"
    }
}
