plugins {
    id("btccore.base-conventions")
    id("btccore.publishing-conventions")
    id("net.minecrell.plugin-yml.paper")
    id("com.gradleup.shadow")
}

publishConfiguration {
    name.set("BTC-CORE Plugin")
    description.set("BTCCore plugin for Paper, providing utilities and core features.")
}

dependencies {
    compileOnly(project(":btccore-api"))
    implementation(project(":loaders"))

    compileOnly(libs.configurate.yaml)
    implementation(libs.bstats)
    implementation(libs.cloud.paper)
    implementation(libs.cloud.minecraft.extras)
    implementation(libs.cloud.annotations)

    compileOnly(paperApi())

    testImplementation(project(":btccore-api"))
    testImplementation(libs.mockbukkit) {
        exclude(group = "io.papermc.paper", module = "paper-api")
    }
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2G"
}

tasks {
    withType<Jar> {
        archiveBaseName.set("btccore-plugin")
    }

    shadowJar {
        archiveClassifier.set("")
        
        relocate("org.bstats", "dev.btc.core.libs.bstats")
        relocate("org.spongepowered.configurate", "dev.btc.core.libs.configurate")
        relocate("com.zaxxer.hikari", "dev.btc.core.libs.hikari")
        relocate("com.mongodb", "dev.btc.core.libs.mongo")
        relocate("io.lettuce", "dev.btc.core.libs.lettuce")
        relocate("org.bson", "dev.btc.core.libs.bson")

        // Exclude Netty as it is provided by Paper
        exclude("io/netty/**")
        exclude("META-INF/maven/io.netty/**")
        exclude("META-INF/native-image/io.netty/**")
        
        // Exclude Reactor and Reactive Streams (from Lettuce)
        exclude("reactor/**")
        exclude("org/reactivestreams/**")
        exclude("META-INF/native-image/io.projectreactor/**")

        // Exclude Slf4j as it is provided by Paper
        exclude("org/slf4j/**")
        exclude("META-INF/maven/org.slf4j/**")
    }

    assemble {
        dependsOn(shadowJar)
    }
}

paper {
    name = "BTCCorePlugin"
    description = "BTCCore plugin for Paper, providing utilities for the platform"
    version = "\${gitCommitId}"
    apiVersion = "26.1"
    main = "dev.btc.core.plugin.SWPlugin"
    authors = listOf("RenaudRl")
    bootstrapper = "dev.btc.core.plugin.SlimePluginBootstrap"
}


