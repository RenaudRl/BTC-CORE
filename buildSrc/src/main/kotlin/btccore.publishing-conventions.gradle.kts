import dev.btc.core.conventions.PublishConfiguration.Companion.publishConfiguration
import org.kordamp.gradle.plugin.profiles.ProfilesExtension

plugins {
    `maven-publish`
    signing
    id("org.kordamp.gradle.profiles")
}

val publishConfiguration = publishConfiguration()

extensions.configure<ProfilesExtension>("profiles") {
    profile("publish") {
        activation {
            property {
                setKey("publish")
                setValue("true")
            }
        }
        action {
            extensions.configure<PublishingExtension>("publishing") {
                publications {
                    create<MavenPublication>("maven") {
                        groupId = "${project.group}"
                        artifactId = project.name
                        version = "${project.version}"

                        from(components["java"])

                        pom {
                            name.set(publishConfiguration.name.convention(project.name))
                            description.set(publishConfiguration.description.convention(project.description ?: ""))
                            url.set("https://github.com/btc-core/BTC-Core")
                            licenses {
                                license {
                                    name.set("GNU General Public License, Version 3.0")
                                    url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
                                }
                            }
                            developers {
                                developer {
                                    id.set("btc-core")
                                    name.set("The btc-core Team")
                                    url.set("https://github.com/btc-core")
                                    email.set("btc-core@gmail.com")
                                }
                            }
                            scm {
                                connection.set("scm:git:https://github.com:btc-core/BTC-Core.git")
                                developerConnection.set("scm:git:ssh://github.com:btc-core/BTC-Core.git")
                                url.set("https://github.com/btc-core/BTC-Core/")
                            }
                            issueManagement {
                                system.set("Github")
                                url.set("https://github.com/btc-core/BTC-Core/issues")
                            }
                        }

                        versionMapping {
                            usage("java-api") {
                                fromResolutionOf("runtimeClasspath")
                            }
                            usage("java-runtime") {
                                fromResolutionResult()
                            }
                        }
                    }
                }
                repositories {
                    maven {
                        name = "Local"
                        url = uri(rootProject.layout.projectDirectory.dir("repo"))
                    }
                    maven {
                        name = "btc-core"

                        url = if("${project.version}".endsWith("-SNAPSHOT")) {
                            uri("https://repo.btc-core.com/repository/maven-snapshots/")
                        } else {
                            uri("https://repo.btc-core.com/repository/maven-releases/")
                        }

                        credentials {
                            username = project.findProperty("ISUsername") as String?
                            password = project.findProperty("ISPassword") as String?
                        }
                    }
                }
            }
            if (project.hasProperty("sign")) {
                extensions.configure<SigningExtension>("signing") {
                    useGpgCmd()
                    sign(extensions.getByName<PublishingExtension>("publishing").publications["maven"])
                }
            }

        }
    }
}

tasks.withType<Javadoc>().configureEach {
    isFailOnError = false
}

// Task to publish Javadocs as a website in the repo folder
tasks.register<Copy>("publishJavadocs") {
    group = "publishing"
    description = "Extracts and publishes Javadocs to the local repository website."
    
    val javadocTask = tasks.withType<Javadoc>().findByName("javadoc")
    if (javadocTask != null) {
        dependsOn(javadocTask)
        from(javadocTask.destinationDir)
        into(rootProject.layout.projectDirectory.dir("repo/javadoc/${project.name}"))
    }
}

