import fr.brouillard.oss.gradle.plugins.JGitverPluginExtensionBranchPolicy
import fr.brouillard.oss.jgitver.Strategies
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*

plugins {
    `java-gradle-plugin`
    `maven-publish`
    kotlin("jvm") version "1.6.10"
    id("org.cadixdev.licenser") version "0.6.1"
    id("com.gradle.plugin-publish") version "0.14.0"
    id("fr.brouillard.oss.gradle.jgitver") version "0.10.+"
}

group = "wtf.gofancy.koremods"

pluginBundle {
    website = "https://gitlab.com/gofancy/koremods"
    vcsUrl = "https://gitlab.com/gofancy/koremods/koremods-gradle"
    tags = listOf("kotlin", "bytecode-manipulation")
}

gradlePlugin {
    plugins {
        create("koremods-gradle") {
            id = "wtf.gofancy.koremods.koremods-gradle"
            displayName = "Koremods Gradle"
            description = "A Gradle plugin for pre-compiling Koremods scripts"
            implementationClass = "wtf.gofancy.koremods.gradle.KoremodsGradlePlugin"
        }
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(JavaVersion.VERSION_11.majorVersion))
}

repositories {
    mavenCentral()
    maven("https://su5ed.jfrog.io/artifactory/maven")
    mavenLocal()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
    implementation(kotlin("scripting-compiler-embeddable"))
    implementation(kotlin("scripting-common"))
    implementation(kotlin("scripting-jvm"))
    implementation(kotlin("scripting-jvm-host"))
    implementation(group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version = "1.6.0")
    
    // TODO Better shade dep handling
    implementation(group = "wtf.gofancy.koremods", name = "koremods-script", version = "0.1.26")
    implementation(group = "io.github.config4k", name = "config4k", version = "0.4.2")

    testImplementation(group = "org.assertj", name = "assertj-core", version = "3.19.0")
    testImplementation(group = "org.junit.jupiter", name = "junit-jupiter", version = "5.7.1")
}

license {
    header(project.file("NOTICE"))

    ext["year"] = Calendar.getInstance().get(Calendar.YEAR)
    ext["name"] = "Garden of Fancy"
    ext["app"] = "Koremods Gradle"
}

jgitver {
    strategy = Strategies.PATTERN
    versionPattern = "\${M}\${<m}\${<meta.COMMIT_DISTANCE}\${-~meta.QUALIFIED_BRANCH_NAME}"

    policy(closureOf<JGitverPluginExtensionBranchPolicy> {
        pattern = "(^master\$)|(dev\\/.*)"
        transformations = mutableListOf("IGNORE")
    })
}

tasks {
    withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = JavaVersion.VERSION_11.toString()
            
//            apiVersion = "1.4"
//            languageVersion = "1.4"
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

    wrapper {
        gradleVersion = "7.4"
        distributionType = Wrapper.DistributionType.ALL
    }
}
