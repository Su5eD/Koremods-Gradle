/*
 * This file is part of Koremods Gradle, licensed under the MIT License
 *
 * Copyright (c) 2023 Garden of Fancy
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

/**
 * Holds java fork options for gradle worker daemons
 * 
 * @param maxHeapSize maximum Java heap size
 */
data class WorkerDaemonOptions(var maxHeapSize: String? = null)

@Suppress("unused")
open class KoremodsGradleExtension @Inject constructor(project: Project) {
    companion object {
        const val EXTENSION_NAME = "koremods"
    }

    /**
     * Enable the script compiler daemon.
     */
    val useWorkerDaemon: Property<Boolean> = project.objects.property(Boolean::class.java)
        .convention(true)

    /**
     * Script compiler daemon java fork options.
     */
    val workerDaemonOptions: Property<WorkerDaemonOptions> = project.objects.property(WorkerDaemonOptions::class.java)
        .convention(project.provider { WorkerDaemonOptions("512M") })

    /**
     * Set of [SourceSet][org.gradle.api.tasks.SourceSet]s scanned for Koremods script packs.
     * If left empty, all existing source sets in the project will be included.
     */
    private val sourceSets: MutableSet<SourceSet> = mutableSetOf()

    /**
     * Add [SourceSet][org.gradle.api.tasks.SourceSet]s to be scanned for Koremods script packs.
     * Candidates are expected to contain a valid script pack, otherwise a [RuntimeException] will be thrown.
     */
    fun sources(vararg sources: SourceSet) {
        sourceSets += sources
    }

    /**
     * Customize the script compiler daemon fork options, replacing the default values.
     */
    fun workerDaemonOptions(block: WorkerDaemonOptions.() -> Unit) {
        val options = WorkerDaemonOptions()
        block(options)
        workerDaemonOptions.set(options)
    }

    /**
     * Locate script packs in candidate source sets, then create a script compilation task
     * for each of them, and replace source scripts in processResources outputs with
     * compiled script jars.
     */
    internal fun apply(project: Project) {
        locateScriptPacks(project)
            .forEach { (scriptPack, sourceSet) ->
                // task name unique to the source set's name, in the format of verbNameNoun
                val taskName = sourceSet.getTaskName("compile", "koremodsScripts")
                // output compiled scripts to a folder inside the project's build directory, named after the task
                val outputDir = project.buildDir.resolve(taskName)

                val compileScriptsTask = project.tasks.register(taskName, CompileKoremodsScriptsTask::class.java) { task ->
                    // Set a task input on the script pack's namespace to re-compile the pack when it changes
                    task.inputs.property("namespace", scriptPack.namespace)

                    scriptPack.scripts.forEach { script ->
                        // The compiled script path is based on its location related to the resource root
                        val relative = script.source.relativeTo(scriptPack.path).pathString
                        // Set the extension to 'jar' for compiled scripts
                        val outputFile = outputDir.resolve(relative.replace(KOREMODS_SCRIPT_EXTENSION, "jar"))
                        
                        task.script(script.identifier, script.source.toFile(), outputFile)
                    }
                }
                
                // Get the SourceSet's respective processResources task
                project.tasks.named(sourceSet.processResourcesTaskName, ProcessResources::class.java) { processResources ->
                    // Make the script compilation task run before resources are processed
                    processResources.dependsOn(compileScriptsTask)

                    // Replace script sources with their compiled variant in the resources output
                    compileScriptsTask.get().run {
                        processResources.exclude { inputs.files.contains(it.file) }
                        processResources.from(outputDir)
                    }
                }
            }
    }

    /**
     * Scan [sourceSets] for Koremods script packs.
     * @return a list of pairs associating located script packs to their origin SourceSet
     */
    private fun locateScriptPacks(project: Project): List<Pair<RawScriptPack<Path>, SourceSet>> {
        // Get configured candidate SourceSets, or all SourceSets of this project if empty 
        val sources = sourceSets.ifEmpty { project.extensions.getByType(JavaPluginExtension::class.java).sourceSets }
        // If sourceSets were configured by the user, require them to contain a script pack
        val strict = sourceSets.isNotEmpty()
        
        return sources.mapNotNull { sourceSet ->
            // Scan sourceSet resource roots for script packs and return first found, or null if there are none
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