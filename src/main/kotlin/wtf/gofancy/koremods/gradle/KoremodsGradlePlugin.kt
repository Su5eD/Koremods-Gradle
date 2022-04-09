/*
 * This file is part of Koremods Gradle, licensed under the MIT License
 *
 * Copyright (c) 2022 Garden of Fancy
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package wtf.gofancy.koremods.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.language.jvm.tasks.ProcessResources
import wtf.gofancy.koremods.KoremodsPackConfig
import wtf.gofancy.koremods.parseConfig
import wtf.gofancy.koremods.prelaunch.KoremodsBlackboard
import java.util.*

class KoremodsGradlePlugin : Plugin<Project> {
    companion object {
        const val KOREMODS_CONFIGURATION_NAME = "koremods"
        const val SCRIPT_EXTENSION = "core.kts"

        const val SCRIPTING_COMPILE_DEPS_CONFIGURATION_NAME = "koremodsScriptingCompileDependencies"
        const val SCRIPTING_RUNTIME_DEPS_CONFI6GURATION_NAME = "koremodsScriptingRuntimeDependencies"
        
        val KOTLIN_SCRIPT_DEPS = setOf(
            "reflect",
            "scripting-common",
            "scripting-compiler-embeddable",
            "scripting-jvm",
            "scripting-jvm-host"
        )
        val ASM_DEPS = setOf("asm", "asm-analysis", "asm-commons", "asm-tree", "asm-util")
    }

    override fun apply(project: Project) {
        val pluginProperties = Properties().also {
            it.load(javaClass.getResourceAsStream("/koremods-gradle.properties"))
        }
        val kotlinVersion = pluginProperties["kotlinVersion"]
        val asmVersion = pluginProperties["asmVersion"]
        val scriptingCompileDeps = KOTLIN_SCRIPT_DEPS
            .map { "org.jetbrains.kotlin:kotlin-$it:$kotlinVersion" }
        val scriptingRuntimeDeps = ASM_DEPS
            .map { "org.ow2.asm:$it:$asmVersion" }

        val koremodsImplementation = project.configurations.create(KOREMODS_CONFIGURATION_NAME)
        project.plugins.apply(JavaPlugin::class.java)
        project.configurations.named(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME)
            .configure { it.extendsFrom(koremodsImplementation) }

        project.createConfigurationWithDependencies(SCRIPTING_COMPILE_DEPS_CONFIGURATION_NAME, scriptingCompileDeps)
        project.createConfigurationWithDependencies(SCRIPTING_RUNTIME_DEPS_CONFI6GURATION_NAME, scriptingRuntimeDeps)

        val javaExt = project.extensions.getByType(JavaPluginExtension::class.java)
        javaExt.sourceSets.asSequence()
            .filterNot { it.resources.isEmpty }
            .mapNotNull { sourceSet ->
                sourceSet.resources.srcDirs.firstNotNullOfOrNull { resourceRoot ->
                    resourceRoot.resolve(KoremodsBlackboard.CONFIG_FILE_LOCATION)
                        .let { file -> if (file.exists()) Triple(sourceSet, resourceRoot, file) else null }
                }
            }
            .forEach { (sourceSet, resourceRoot, configFile) ->
                val taskName = sourceSet.getTaskName("compile", "koremodsScripts")
                val outputDir = project.buildDir.resolve(taskName)

                val preCompileTask = project.tasks.register(taskName, CompileKoremodsScriptsTask::class.java) { task ->
                    parseConfig<KoremodsPackConfig>(configFile.reader()).scripts
                        .forEach { script ->
                            val inputFile = resourceRoot.resolve(script)
                            val outputFile = outputDir.resolve(script.replace(SCRIPT_EXTENSION, "jar"))

                            task.filesMap.add(CompileKoremodsScriptsTask.ScriptFileMapping(inputFile, outputFile))
                        }
                }

                project.tasks.named(sourceSet.processResourcesTaskName, ProcessResources::class.java) { processResources ->
                    processResources.dependsOn(preCompileTask)

                    preCompileTask.get().run {
                        filesMap.get().map(CompileKoremodsScriptsTask.ScriptFileMapping::inputFile).forEach { file ->
                            processResources.exclude { it.file == file }
                        }
                        processResources.from(outputDir)
                    }
                }
            }
    }

    private fun Project.createConfigurationWithDependencies(name: String, dependencies: Iterable<String>) {
        project.configurations.create(name) { conf ->
            conf.dependencies += dependencies
                .map(project.dependencies::create)
        }
    }
}