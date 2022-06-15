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

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.initialization.ClassLoaderRegistry
import org.gradle.internal.UncheckedException
import org.gradle.internal.classloader.FilteringClassLoader
import org.gradle.internal.classloader.VisitableURLClassLoader
import org.gradle.internal.classpath.DefaultClassPath
import org.gradle.process.internal.JavaForkOptionsFactory
import org.gradle.work.Incremental
import org.gradle.work.InputChanges
import org.gradle.workers.internal.*
import wtf.gofancy.koremods.Identifier
import java.io.File
import java.io.Serializable
import javax.inject.Inject
import kotlin.io.path.pathString

/**
 * Contains compilation properties of a koremods script.
 * Implements Serializable so that it can be sent to the script compiler daemon.
 */
interface ScriptResource : Serializable {
    /**
     * The script's unique [Identifier]
     */
    @get:Internal
    val identifier: Property<Identifier>

    /**
     * Input script source file (core.kts) to read from
     */
    @get:Incremental
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val inputFile: RegularFileProperty

    /**
     * Output compiled script jar to write the compilation output to
     */
    @get:OutputFile
    val outputFile: RegularFileProperty
}

/**
 * Compiles Koremods Scripts in an isolated environment parallelly. The isolation mode can be configured to be on
 * [CLASSLOADER][IsolationMode.CLASSLOADER] or [PROCESS][IsolationMode.PROCESS] level.
 */
@CacheableTask
abstract class CompileKoremodsScriptsTask @Inject constructor(
    private val isolatedClassloaderWorkerFactory: IsolatedClassloaderWorkerFactory,
    private val workerDaemonFactory: WorkerDaemonFactory,
    private val actionExecutionSpecFactory: ActionExecutionSpecFactory,
    private val classLoaderRegistry: ClassLoaderRegistry,
    private val forkOptionsFactory: JavaForkOptionsFactory
) : DefaultTask() {
    /**
     * A list of scripts to be compiled
     */
    @get:Nested
    val scripts: List<ScriptResource>
        get() = scriptsInternal

    /**
     * Internal mutable value of [scripts], which new scripts are added to
     */
    private val scriptsInternal: MutableList<ScriptResource> = mutableListOf()

    /**
     * Get the project's KoremodsExtension for retrieving user configuration values
     */
    private val koremodsExtension: KoremodsGradleExtension = project.extensions.getByType(KoremodsGradleExtension::class.java)

    /**
     * Add a new compilable script to this task.
     * 
     * @param identifier unique script identifier
     * @param inputFile script source file
     * @param outputFile compiled script jar output
     */
    fun script(identifier: Identifier, inputFile: File, outputFile: File) {
        // Create a new ScriptResource from method args and add it to scripts
        scriptsInternal += project.objects.newInstance(ScriptResource::class.java).also { resource ->
            resource.identifier.set(identifier)
            resource.inputFile.set(inputFile)
            resource.outputFile.set(outputFile)
        }
    }

    @TaskAction
    fun apply(changes: InputChanges) {
        // Get the script compiler classpath configuration
        val scriptCompilerClasspathConf = project.configurations.getByName(KoremodsGradlePlugin.SCRIPT_COMPILER_CLASSPATH_CONFIGURATION_NAME)
        // Get the koremods script classpath configuration
        val scriptClasspathConf = project.configurations.getByName(KoremodsGradlePlugin.SCRIPT_CLASSPATH_CONFIGURATION_NAME)
        // Join the resolved scriptCompilerClasspathConf with this class' origin File
        val isolationClassPath = scriptCompilerClasspathConf.resolve() + File(javaClass.protectionDomain.codeSource.location.toURI())
        // Kotlin compiler classpath libraries
        val scriptLibraries = scriptClasspathConf.resolve()

        project.logger.info("Compiling Koremods scripts")

        // Filter out unchanged scripts to optimize compilation times
        val candidateScripts = scripts.filter { script ->
            changes.getFileChanges(script.inputFile).any()
        }
        val params = project.objects.newInstance(CompileScriptAction.CompileScriptParameters::class.java).apply {
            // Convert scripts to CompilableScripts, which use resolved property values
            val candidateCompilableScripts = candidateScripts.map { script ->
                // Path is not serializable, so we convert it to a string first
                CompilableScript(script.identifier.get(), script.inputFile.asFile.map { it.toPath().pathString }.get(), script.outputFile.get().asFile)
            }
            scriptResources.set(candidateCompilableScripts)
            classPath.from(scriptLibraries)
        }
        // Submit the script compilation parameters to the isolated worker
        submitWork(isolationClassPath, params)
    }

    /**
     * Submit compilation parameters to the isolated worker for processing.
     * 
     * @param classpath the worker's classpath
     * @param parameters compilation parameters
     */
    private fun submitWork(classpath: Collection<File>, parameters: CompileScriptAction.CompileScriptParameters) {
        // Get the desired worker factory, depending on whether the worker daemon is enabled
        val workerFactory = if (koremodsExtension.useWorkerDaemon.get()) workerDaemonFactory else isolatedClassloaderWorkerFactory
        // Get a worker configuration from our isolation mode and classpath
        val workerRequirement = getWorkerRequirement(workerFactory.isolationMode, classpath)
        // Create or get existing worker from the requirement
        val worker = workerFactory.getWorker(workerRequirement)

        // Submit parameters to the worker for execution
        val result = worker.execute(actionExecutionSpecFactory.newIsolatedSpec("koremods script compiler daemon", CompileScriptAction::class.java, parameters, workerRequirement, true))
        if (!result.isSuccess) {
            // Throw an exception if compilation fails
            // This is required as the worker is isolated and won't throw it by itself
            throw UncheckedException.throwAsUncheckedException(result.exception!!)
        }
    }

    /**
     * Create a Gradle WorkerRequirement, which is used to configure and 'cache' the worker.
     * 
     * @param mode the worker's isolation mode, should be [CLASSLOADER][IsolationMode.CLASSLOADER]
     * or [PROCESS][IsolationMode.PROCESS]
     * @param classpath the worker's classpath
     * @return a new WorkerRequirement
     * @throws IllegalArgumentException if an unsupported [mode] is passed in
     */
    private fun getWorkerRequirement(mode: IsolationMode, classpath: Collection<File>): WorkerRequirement {
        // Get the worker's Java fork options
        val daemonForkOptions = toDaemonForkOptions(classpath)
        return when (mode) {
            // Return an isolated worker requirement, java fork options are ignored
            IsolationMode.CLASSLOADER -> IsolatedClassLoaderWorkerRequirement(daemonForkOptions.javaForkOptions.workingDir, daemonForkOptions.classLoaderStructure)
            // Return a forked worker requirement
            IsolationMode.PROCESS -> ForkedWorkerRequirement(daemonForkOptions.javaForkOptions.workingDir, daemonForkOptions)
            // Throw an exception for unsupported modes
            else -> throw IllegalArgumentException("Requested worker with unsupported isolation mode: $mode")
        }
    }

    /**
     * Get Java fork options for the gradle worker. Currently only supports modifying the max heap size.
     * 
     * @param classpath the worker's classpath
     * @return worker Java fork options
     */
    private fun toDaemonForkOptions(classpath: Collection<File>): DaemonForkOptions {
        // Create the worker's hierarchical class loader structure 
        val classLoaderStructure = HierarchicalClassLoaderStructure(classLoaderRegistry.gradleWorkerExtensionSpec)
            .withChild(getKotlinFilterSpec()) // Filter out gradle's kotlin libs
            .withChild(VisitableURLClassLoader.Spec("worker-loader", DefaultClassPath.of(classpath).asURLs)) // Set the worker's classpath

        // Create java fork options from user-configured values
        val extensionOptions = koremodsExtension.workerDaemonOptions.get()
        val javaForkOptions = forkOptionsFactory.newJavaForkOptions().apply {
            maxHeapSize = extensionOptions.maxHeapSize
        }

        // Merge the CL structure and java options into a single object
        return DaemonForkOptionsBuilder(forkOptionsFactory)
            .javaForkOptions(javaForkOptions)
            .withClassLoaderStructure(classLoaderStructure)
            .keepAliveMode(KeepAliveMode.DAEMON) // Persist the daemon across builds 
            .build()
    }

    /**
     * Create a filtering spec that disallows the `kotlin` package to avoid conflicts with
     * kotlin libs and compiler shipped by gradle.
     */
    private fun getKotlinFilterSpec(): FilteringClassLoader.Spec {
        return classLoaderRegistry.gradleApiFilterSpec.apply {
            disallowPackage("kotlin")
        }
    }
}