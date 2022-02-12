version = "1.0.3"

plugins {
    id("org.jetbrains.dokka") version "1.6.10"
    id("maven-publish")
    id("signing")
}

buildscript {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.6.10")
        classpath("com.android.tools.build:gradle:7.0.4")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

dependencies {
    dokkaHtmlPlugin("org.jetbrains.dokka:kotlin-as-java-plugin:1.6.10")
}

signing {
    useGpgCmd()
    sign(publishing.publications)
}

publishing {
    repositories {
        maven {
            name="oss"
            val releasesRepoUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            val snapshotsRepoUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
            credentials {
                username = project.property("sonatype.username") as String?
                password = project.property("sonatype.password") as String?
            }
        }
    }
    publications {
        withType<MavenPublication> {
            artifact(javadocJar)
            pom {
                name.set("Kotlin Phoenix")
                description.set("A Phoenix client written in Kotlin, multiplatform and backed by coroutines")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                url.set("https://github.com/ajacquierbret/kotlin-phoenix")
                issueManagement {
                    system.set("Github")
                    url.set("https://github.com/ajacquierbret/kotlin-phoenix/issues")
                }
                scm {
                    connection.set("https://github.com/ajacquierbret/kotlin-phoenix.git")
                    url.set("https://github.com/ajacquierbret/kotlin-phoenix")
                }
                developers {
                    developer {
                        name.set("Adrien Jacquier Bret")
                        email.set("a.jacquierbret@lancey.fr")
                    }
                }
            }
        }
    }
}

val dokkaOutputDir = "$buildDir/dokka"

tasks.dokkaHtmlMultiModule.configure {
    outputDirectory.set(file(dokkaOutputDir))
}

val deleteDokkaOutputDir by tasks.register<Delete>("deleteDokkaOutputDirectory") {
    delete(dokkaOutputDir)
}

val javadocJar = tasks.register<Jar>("javadocJar") {
    dependsOn(deleteDokkaOutputDir, tasks.dokkaHtmlMultiModule)
    archiveClassifier.set("javadoc")
    from(dokkaOutputDir)
}