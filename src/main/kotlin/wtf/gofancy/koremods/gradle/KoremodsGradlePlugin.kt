package wtf.gofancy.koremods.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.language.jvm.tasks.ProcessResources
import wtf.gofancy.koremods.KoremodModConfig
import wtf.gofancy.koremods.parseConfig
import wtf.gofancy.koremods.prelaunch.KoremodsBlackboard

class KoremodsGradlePlugin : Plugin<Project> {
    companion object {
        const val KOREMODS_CONFIGURATION_NAME = "koremods"
    }

    override fun apply(project: Project) {
        val koremodsImplementation = project.configurations.create(KOREMODS_CONFIGURATION_NAME)
        project.plugins.apply(JavaPlugin::class.java)
        project.configurations.named(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME)
            .configure { it.extendsFrom(koremodsImplementation) }

        val javaExt = project.extensions.getByType(JavaPluginExtension::class.java)
        javaExt.sourceSets // TODO Sequence?
            .filterNot { it.resources.isEmpty }
            .mapNotNull { sourceSet ->
                sourceSet.resources.srcDirs.firstNotNullOfOrNull { resourceRoot ->
                    resourceRoot.resolve(KoremodsBlackboard.CONFIG_FILE_LOCATION)
                        .let { file -> if (file.exists()) Triple(sourceSet, resourceRoot, file) else null }
                }
            }
            .forEach { (sourceSet, resourceRoot, configFile) ->
                val taskName = sourceSet.getTaskName("compile", "koremodsScripts")

                val preCompileTask = project.tasks.register(taskName, PreCompileScriptsTask::class.java) { task ->
                    parseConfig<KoremodModConfig>(configFile.reader()).scripts
                        .map(resourceRoot::resolve)
                        .forEach(task.inputScripts::from)
                }
                
                project.afterEvaluate { proj ->
                    proj.tasks.named(sourceSet.processResourcesTaskName, ProcessResources::class.java) { processResources ->
                        processResources.dependsOn(preCompileTask)
                        
                        preCompileTask.get().run {
                            inputScripts.forEach { file -> processResources.exclude { it.file == file } }
                            processResources.from(outputDir)
                        }
                    }
                }
            }
    }
}