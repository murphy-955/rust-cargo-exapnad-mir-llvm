import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.murphy955"
version = "0.1.0"

val rustRoverLocalPath = providers.gradleProperty("rustRoverLocalPath").orNull

repositories {
    // Mirrors of the JetBrains Maven repositories (test framework, platform deps, verifier).
    maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
    maven("https://repo.huaweicloud.com/repository/maven")
    mavenCentral()

    intellijPlatform {
        if (rustRoverLocalPath != null) {
            localPlatformArtifacts()
        } else {
            defaultRepositories()
        }
    }
}

dependencies {
    intellijPlatform {
        // Reuse an already installed IDE when `-PrustRoverLocalPath=...` is passed.
        // Otherwise the RustRover distribution is resolved from JetBrains repositories.
        if (rustRoverLocalPath != null) {
            local(rustRoverLocalPath)
        } else {
            rustRover("2026.2.5")
        }

        testFramework(TestFrameworkType.Platform)
        testBundledPlugin("com.jetbrains.rust")
    }

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    // RustRover 2026.2 / IntelliJ Platform 262 requires Java 25.
    jvmToolchain(25)
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        name = "Rust Compiler Reader"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "262.11879.102"
            // No untilBuild on purpose: future RustRover versions may install the plugin.
        }

        vendor {
            name = "zeyuli"
            email = "13776528859@163.com"
            url = "https://github.com/murphy-955/rust-cargo-exapnad-mir-llvm"
        }
    }

    pluginVerification {
        // `-PverifyOffline=true` avoids Marketplace downloads when the dependency cache is warm.
        if (providers.gradleProperty("verifyOffline").orNull == "true") {
            freeArgs.addAll("-offline")
        }
        ides {
            val localIde = providers.gradleProperty("rustRoverLocalPath").orNull
            if (localIde != null) {
                local(localIde)
            } else {
                create(IntelliJPlatformType.RustRover, "2026.2.5")
            }
        }
    }
}

tasks {
    test {
        maxHeapSize = "2g"
        systemProperty(
            "rustreader.smoke",
            providers.gradleProperty("rustreader.smoke")
                .orElse(providers.systemProperty("rustreader.smoke"))
                .orElse("false")
                .get(),
        )
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    runIde {
        maxHeapSize = "2g"
    }
}
