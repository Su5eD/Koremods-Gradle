package wtf.gofancy.koremods.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.language.jvm.tasks.ProcessResources
import wtf.gofancy.koremods.KoremodModConfig
import wtf.gofancy.koremods.parseConfig

class KoremodsGradlePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val koremodsExtension = project.extensions.create(KoremodsExtension.EXTENSION_NAME, KoremodsExtension::class.java, project)

        project.configurations.register("koremodsImplementation") {
//            project.configurations.getByName(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME).extendsFrom(it)
        }

        val preCompileTask = project.tasks.register("preCompileScripts", PreCompileScriptsTask::class.java) { task ->
            val configFile = koremodsExtension.configFile.get().asFile
            val config = parseConfig<KoremodModConfig>(configFile.reader())

            config.scripts.forEach { script ->
                val sourceFile = project.file("src/main/resources/${script}") // TODO
                task.inputScripts.from(sourceFile)
            }
        }

        project.afterEvaluate {
            project.tasks.withType(ProcessResources::class.java) { task ->
                task.dependsOn(preCompileTask)
            }
        }
    }
}