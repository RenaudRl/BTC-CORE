import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java
    id("io.papermc.paperweight.patcher")
}

paperweight {
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(JAVA_VERSION))
        }
    }

    repositories {
        mavenCentral()
        maven(PAPER_MAVEN_PUBLIC_URL)
        maven("https://jitpack.io")
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
        options.release = JAVA_VERSION
        options.isFork = true
        options.compilerArgs.add("--add-modules=jdk.incubator.vector")
    }
    tasks.withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources> {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test> {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }
}

tasks.register("buildAll") {
    group = "build"
    description = "Assembles the entire project (Server Paperclip and Plugin ShadowJar)"
    dependsOn(project(":btccore-server").tasks.named("createPaperclipJar"))
    dependsOn(project(":plugin").tasks.named("assemble"))
    
    doLast {
        println("#######################################################################")
        println("BUILD ALL COMPLETED")
        println("Server: btccore-server/build/libs/")
        println("Plugin: plugin/build/libs/")
        println("#######################################################################")
    }
}
