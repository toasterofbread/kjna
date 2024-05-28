import com.vanniktech.maven.publish.SonatypeHost
import com.vanniktech.maven.publish.JavadocJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("multiplatform")
    id("com.vanniktech.maven.publish")
}

kotlin {
    jvmToolchain(21)

    jvm()
    linuxX64()
    linuxArm64()
    mingwX64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        all {
            languageSettings.apply {
                enableLanguageFeature("ExpectActualClasses")

                if (!name.startsWith("jvm") && !name.startsWith("common")) {
                    optIn("kotlin.experimental.ExperimentalNativeApi")
                    optIn("kotlinx.cinterop.ExperimentalForeignApi")
                }
            }
        }

        val commonMain by getting
    }
}

mavenPublishing {
    val project_version: String = extra["project.version"] as String
    coordinates("dev.toastbits.kjna", "runtime", project_version)

    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    pom {
        name.set("KJna runtime")
        description.set("TODO")
        url.set("https:/github.com/toasterofbread/KJna")
        inceptionYear.set("2024")

        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id.set("toasterofbread")
                name.set("Talo Halton")
                email.set("talohalton@gmail.com")
                url.set("https://github.com/toasterofbread")
            }
        }
        scm {
            connection.set("https://github.com/toasterofbread/KJna.git")
            url.set("https://github.com/toasterofbread/KJna")
        }
        issueManagement {
            system.set("Github")
            url.set("https://github.com/toasterofbread/KJna/issues")
        }
    }
}