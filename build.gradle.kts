import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*

plugins {
    `java-gradle-plugin`
    `maven-publish`
    kotlin("jvm")
    id("org.cadixdev.licenser")
    id("com.gradle.plugin-publish")
    id("me.qoomon.git-versioning")
}

group = "wtf.gofancy.koremods"
version = "0.0.0-SNAPSHOT"

gradlePlugin {
    website.set("https://gitlab.com/gofancy/koremods")
    vcsUrl.set("https://gitlab.com/gofancy/koremods/koremods-gradle")

    plugins {
        create("koremods-gradle") {
            id = "wtf.gofancy.koremods.gradle"
            displayName = "Koremods Gradle"
            description = "A Gradle plugin for pre-compiling Koremods scripts"
            implementationClass = "wtf.gofancy.koremods.gradle.KoremodsGradlePlugin"
            tags.set(listOf("kotlin", "kotlin-script", "bytecode-manipulation"))
        }
    }
}

gitVersioning.apply {
    rev {
        version =
            "\${describe.tag.version.major}.\${describe.tag.version.minor}.\${describe.tag.version.patch.plus.describe.distance}"
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(JavaVersion.VERSION_11.majorVersion))
}

license {
    header(project.file("NOTICE"))

    ext["year"] = Calendar.getInstance().get(Calendar.YEAR)
    ext["name"] = "Garden of Fancy"
    ext["app"] = "Koremods Gradle"

    include("**/**.kt")
}

repositories {
    mavenCentral()
    maven {
        name = "Garden of Fancy"
        url = uri("https://maven.gofancy.wtf/releases")
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
    implementation(kotlin("scripting-compiler-embeddable"))
    implementation(kotlin("scripting-common"))
    implementation(kotlin("scripting-jvm"))
    implementation(kotlin("scripting-jvm-host"))

    implementation(group = "wtf.gofancy.koremods", name = "koremods-script", version = "_")

    testImplementation(group = "org.assertj", name = "assertj-core", version = "_")
    testImplementation(group = "org.junit.jupiter", name = "junit-jupiter", version = "_")
}

tasks {
    withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = JavaVersion.VERSION_11.toString()
        }
    }

    withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    withType<Jar> {
        manifest {
            attributes(
                "Name" to "wtf/gofancy/koremods/gradle",
                "Specification-Title" to "Koremods Gradle",
                "Specification-Version" to project.version,
                "Specification-Vendor" to "Garden of Fancy",
                "Implementation-Title" to "wtf.gofancy.koremods.gradle",
                "Implementation-Version" to project.version,
                "Implementation-Vendor" to "Garden of Fancy",
                "Implementation-Timestamp" to DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            )
        }
    }

    withType<Wrapper> {
        gradleVersion = "8.2"
        distributionType = Wrapper.DistributionType.ALL
    }
}

publishing {
    repositories {
        val mavenUser = System.getenv("GOFANCY_MAVEN_USER")
        val mavenToken = System.getenv("GOFANCY_MAVEN_TOKEN")

        if (mavenUser != null && mavenToken != null) {
            maven {
                name = "gofancy"
                url = uri("https://maven.gofancy.wtf/releases")

                credentials {
                    username = mavenUser
                    password = mavenToken
                }
            }
        }
    }
}
