import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.4.10"
}

group = "cn.enaium"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
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
    macosX64 {
        binaries.executable {
            entryPoint = "cn.enaium.mahonoto.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("cn.enaium.sdl:sdl-kmp:1.0.7")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.register<JavaExec>("generateAtlas") {
    group = "dev"
    description = "Generates the Chinese glyph atlas used by the game UI"
    mainClass.set("cn.enaium.mahonoto.tools.AtlasGeneratorKt")
    classpath = kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles +
            files(kotlin.jvm().compilations.getByName("main").output.allOutputs)
    args(
        rootProject.file("assets/fonts/317_黑体.ttf").absolutePath,
        rootProject.file("assets/fonts").absolutePath,
    )
}


tasks.withType(JavaExec::class.java).configureEach {
    if (OperatingSystem.current().isMacOsX && name == "jvmRun") {
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-XstartOnFirstThread")
    }
}
