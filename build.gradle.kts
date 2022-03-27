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
            id = "wtf.gofancy.koremods-gradle"
            displayName = "Koremods Gradle"
            description = "A Gradle plugin for pre-compiling Koremods scripts"
            implementationClass = "wtf.gofancy.koremods.gradle.KoremodsGradlePlugin"
        }
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(JavaVersion.VERSION_1_8.majorVersion))
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("reflect"))
    implementation(kotlin("stdlib-jdk8"))

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
        pattern = "(dev/.*)"
        transformations = mutableListOf("IGNORE")
    })
}

tasks {
    withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = JavaVersion.VERSION_1_8.toString()
            freeCompilerArgs = listOf("-Xjvm-default=all", "-Xlambdas=indy")
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

publishing {
    publications {
        create<MavenPublication>("bleeding") {
            from(components["java"])

            // in order for gradle to find the plugin (otherwise a resolution strategy would need to be used)
            groupId = "wtf.gofancy.koremods-gradle"
            artifactId = "wtf.gofancy.koremods-gradle.plugin"
            version = project.version.toString()
        }
    }
}

