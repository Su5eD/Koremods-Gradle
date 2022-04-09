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
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.language.jvm.tasks.ProcessResources
import wtf.gofancy.koremods.scanPath
import java.util.*
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo

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

        project.createConfigurationWithDependencies(SCRIPTING_COMPILE_DEPS_CONFIGURATION_NAME, scriptingCompileDeps) {
            exclude(mapOf(
                "group" to "org.jetbrains.kotlin",
                "module" to "kotlin-stdlib"
            ))
            exclude(mapOf(
                "group" to "org.jetbrains.kotlin",
                "module" to "kotlin-reflect"
            ))
        }
        project.createConfigurationWithDependencies(SCRIPTING_RUNTIME_DEPS_CONFI6GURATION_NAME, scriptingRuntimeDeps)

        val javaExt = project.extensions.getByType(JavaPluginExtension::class.java)
        javaExt.sourceSets.asSequence()
            .mapNotNull { sourceSet ->
                sourceSet.resources.srcDirs.firstNotNullOfOrNull locatePack@{ resourceRoot ->
                    val scriptPack = scanPath(resourceRoot.toPath())
                    return@locatePack scriptPack?.let { Pair(sourceSet, it) }
                }
            }
            .forEach { (sourceSet, scriptPack) ->
                val taskName = sourceSet.getTaskName("compile", "koremodsScripts")
                val outputDir = project.buildDir.resolve(taskName)

                val preCompileTask = project.tasks.register(taskName, CompileKoremodsScriptsTask::class.java) { task ->
                    task.inputs.property("namespace", scriptPack.namespace)

                    scriptPack.scripts.forEach { script ->
                        val inputFile = script.source.toFile()
                        val relative = script.source.relativeTo(scriptPack.path).pathString
                        val outputFile = outputDir.resolve(relative.replace(SCRIPT_EXTENSION, "jar"))

                        task.scripts.add(CompileKoremodsScriptsTask.ScriptResource(script, inputFile, outputFile))
                    }
                }

                project.tasks.named(sourceSet.processResourcesTaskName, ProcessResources::class.java) { processResources ->
                    processResources.dependsOn(preCompileTask)

                    preCompileTask.get().run {
                        scripts.get().map(CompileKoremodsScriptsTask.ScriptResource::inputFile).forEach { file ->
                            processResources.exclude { it.file == file }
                        }
                        processResources.from(outputDir)
                    }
                }
            }
    }

    private fun Project.createConfigurationWithDependencies(name: String, dependencies: Iterable<String>, action: Configuration.() -> Unit = {}) {
        project.configurations.create(name) { conf ->
            conf.dependencies += dependencies
                .map(project.dependencies::create)
            
            action(conf)
        }
    }
}