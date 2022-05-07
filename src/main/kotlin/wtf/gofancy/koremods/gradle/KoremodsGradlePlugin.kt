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
import org.gradle.api.attributes.AttributeCompatibilityRule
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.CompatibilityCheckDetails
import org.gradle.api.attributes.Usage
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSet
import org.gradle.language.jvm.tasks.ProcessResources
import wtf.gofancy.koremods.RawScriptPack
import wtf.gofancy.koremods.scanPath
import java.nio.file.Path
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo

class KoremodsGradlePlugin : Plugin<Project> {
    companion object {
        const val KOREMODS_CONFIGURATION_NAME = "koremods"
        const val SCRIPT_EXTENSION = "core.kts"

        const val SCRIPT_COMPILER_CLASSPATH_CONFIGURATION_NAME = "koremodsScriptCompilerClasspath"
        const val SCRIPT_CLASSPATH_CONFIGURATION_NAME = "koremodsScriptClasspath"
        const val SCRIPT_COMPILER_CLASSPATH_USAGE = "script-compiler-classpath"
    }

    override fun apply(project: Project) {
        val koremodsExtension = project.extensions.create(KoremodsGradleExtension.EXTENSION_NAME, KoremodsGradleExtension::class.java)

        val koremodsImplementation = project.configurations.create(KOREMODS_CONFIGURATION_NAME) { conf ->
            conf.attributes.attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category::class.java, Category.LIBRARY))
            conf.attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        }

        project.plugins.apply(JavaPlugin::class.java)
        project.configurations.named(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME)
            .configure { it.extendsFrom(koremodsImplementation) }

        project.configurations.create(SCRIPT_COMPILER_CLASSPATH_CONFIGURATION_NAME) { conf ->
            conf.attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, SCRIPT_COMPILER_CLASSPATH_USAGE))

            conf.extendsFrom(koremodsImplementation)
        }

        project.configurations.create(SCRIPT_CLASSPATH_CONFIGURATION_NAME) { conf ->
            conf.attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_API))
            
            conf.extendsFrom(koremodsImplementation)
        }
        
        project.dependencies.attributesSchema.getMatchingStrategy(Usage.USAGE_ATTRIBUTE).compatibilityRules
            .add(ScriptCompilerClasspathUsageCompatibilityRule::class.java)

        project.afterEvaluate { proj -> koremodsExtension.apply(proj) }
    }
    
    class ScriptCompilerClasspathUsageCompatibilityRule : AttributeCompatibilityRule<Usage> {
        override fun execute(details: CompatibilityCheckDetails<Usage>) {
            if (details.consumerValue != null &&details.producerValue != null
                && details.consumerValue!!.name == SCRIPT_COMPILER_CLASSPATH_USAGE && details.producerValue!!.name == Usage.JAVA_RUNTIME) details.compatible()
        }
    }

    private fun locateScriptPacks(sourceSets: Iterable<SourceSet>, strict: Boolean): List<Pair<SourceSet, RawScriptPack<Path>>> {
        return sourceSets.mapNotNull { sourceSet ->
            sourceSet.resources.srcDirs.firstNotNullOfOrNull locatePack@{ resourceRoot ->
                val scriptPack = scanPath(resourceRoot.toPath())
                if (scriptPack == null && strict) {
                    throw RuntimeException("Expected source set '${sourceSet.name}' to contain a koremods script pack, but it could not be found")
                }
                return@locatePack scriptPack?.let { Pair(sourceSet, it) }
            }
        }
    }

    private fun KoremodsGradleExtension.apply(project: Project) {
        val sources = sourceSets.ifEmpty {
            project.extensions.getByType(JavaPluginExtension::class.java).sourceSets
        }

        locateScriptPacks(sources, sourceSets.isNotEmpty())
            .forEach { (sourceSet, scriptPack) ->
                val taskName = sourceSet.getTaskName("compile", "koremodsScripts")
                val outputDir = project.buildDir.resolve(taskName)

                val compileScriptsTask = project.tasks.register(taskName, CompileKoremodsScriptsTask::class.java) { task ->
                    task.inputs.property("namespace", scriptPack.namespace)
                    task.isolateProcess.set(scriptCompilerDaemon)

                    scriptPack.scripts.forEach { script ->
                        val inputFile = script.source.toFile()
                        val relative = script.source.relativeTo(scriptPack.path).pathString
                        val outputFile = outputDir.resolve(relative.replace(SCRIPT_EXTENSION, "jar"))

                        task.inputs.file(inputFile).withPathSensitivity(PathSensitivity.RELATIVE)
                        task.scripts.add(ScriptResource(script.identifier, script.source.pathString, outputFile))
                    }
                }

                project.tasks.named(sourceSet.processResourcesTaskName, ProcessResources::class.java) { processResources ->
                    processResources.dependsOn(compileScriptsTask)

                    compileScriptsTask.get().run {
                        processResources.exclude { inputs.files.contains(it.file) }
                        processResources.from(outputDir)
                    }
                }
            }
    }
}