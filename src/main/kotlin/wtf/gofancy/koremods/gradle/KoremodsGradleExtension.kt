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

import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.tasks.SourceSet
import org.gradle.language.jvm.tasks.ProcessResources
import wtf.gofancy.koremods.RawScriptPack
import wtf.gofancy.koremods.scanPath
import wtf.gofancy.koremods.script.KOREMODS_SCRIPT_EXTENSION
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.pathString
import kotlin.io.path.relativeTo

data class WorkerDaemonOptions(var maxHeapSize: String? = null)

@Suppress("unused")
open class KoremodsGradleExtension @Inject constructor(project: Project) {
    companion object {
        const val EXTENSION_NAME = "koremods"
    }

    val useWorkerDaemon: Property<Boolean> = project.objects.property(Boolean::class.java)
        .convention(true)
    val workerDaemonOptions: Property<WorkerDaemonOptions> = project.objects.property(WorkerDaemonOptions::class.java)
        .convention(project.provider { WorkerDaemonOptions("512M") })
    private val sourceSets: MutableSet<SourceSet> = mutableSetOf()

    fun sources(vararg sources: SourceSet) {
        sourceSets += sources
    }

    fun workerDaemonOptions(block: WorkerDaemonOptions.() -> Unit) {
        val options = WorkerDaemonOptions()
        block(options)
        workerDaemonOptions.set(options)
    }

    internal fun apply(project: Project) {
        locateScriptPacks(project)
            .forEach { (scriptPack, sourceSet) ->
                val taskName = sourceSet.getTaskName("compile", "koremodsScripts")
                val outputDir = project.buildDir.resolve(taskName)

                val compileScriptsTask = project.tasks.register(taskName, CompileKoremodsScriptsTask::class.java) { task ->
                    task.inputs.property("namespace", scriptPack.namespace)

                    scriptPack.scripts.forEach { script ->
                        val relative = script.source.relativeTo(scriptPack.path).pathString
                        val outputFile = outputDir.resolve(relative.replace(KOREMODS_SCRIPT_EXTENSION, "jar"))
                        
                        task.script(script.identifier, script.source.toFile(), outputFile)
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

    private fun locateScriptPacks(project: Project): List<Pair<RawScriptPack<Path>, SourceSet>> {
        val sources = sourceSets.ifEmpty {
            project.extensions.getByType(JavaPluginExtension::class.java).sourceSets
        }
        val strict = sourceSets.isNotEmpty()
        
        return sources.mapNotNull { sourceSet ->
            sourceSet.resources.srcDirs.firstNotNullOfOrNull locatePack@{ resourceRoot ->
                val scriptPack = scanPath(resourceRoot.toPath())
                if (scriptPack == null && strict) {
                    throw RuntimeException("Expected source set '${sourceSet.name}' to contain a koremods script pack, but it could not be found")
                }
                return@locatePack scriptPack?.to(sourceSet)
            }
        }
    }
}