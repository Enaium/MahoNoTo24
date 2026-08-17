import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

group = "cn.enaium"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        mainRun {
            mainClass = "cn.enaium.mahonoto.MainKt"
        }
    }

    macosArm64 {
        binaries.executable {
            entryPoint = "cn.enaium.mahonoto.main"
        }
    }

    linuxX64 {
        binaries.executable {
            entryPoint = "cn.enaium.mahonoto.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("cn.enaium.sdl:sdl-kmp:1.0.8")
            implementation("cn.enaium.sdl:sdl-ttf-kmp:1.0.0")
            implementation("cn.enaium.sdl:sdl-image-kmp:1.0.0")
            implementation("cn.enaium.sdl:sdl-mixer-kmp:1.0.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("io.github.vinceglb:filekit-core:0.15.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Builds a runnable fat jar (includes Kotlin stdlib etc.)"
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "cn.enaium.mahonoto.MainKt"
    }
    from(kotlin.jvm().compilations.getByName("main").output.allOutputs)
    dependsOn(configurations.named("jvmRuntimeClasspath"))
    from({
        configurations.named("jvmRuntimeClasspath").get().map { if (it.isDirectory) it else zipTree(it) }
    })
}

tasks.withType(JavaExec::class.java).configureEach {
    if (OperatingSystem.current().isMacOsX && name == "jvmRun") {
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-XstartOnFirstThread")
    }
}
