plugins {
    id("btccore.base-conventions")
    id("btccore.publishing-conventions")
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":btccore-server"))
    implementation(project(":btccore-api"))
}

tasks {
    jar {
        manifest {
            attributes["Main-Class"] = "dev.btc.core.importer.SWMImporter"
        }
    }
    shadowJar {
        archiveClassifier.set("")
        minimize()
    }
    assemble {
        dependsOn(shadowJar)
    }
}

description = "asp-importer"


