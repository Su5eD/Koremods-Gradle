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

interface ScriptResource : Serializable {
    @get:Internal
    val identifier: Property<Identifier>

    @get:Incremental
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val inputFile: RegularFileProperty

    @get:OutputFile
    val outputFile: RegularFileProperty
}

@CacheableTask
abstract class CompileKoremodsScriptsTask @Inject constructor(
    private val isolatedClassloaderWorkerFactory: IsolatedClassloaderWorkerFactory,
    private val workerDaemonFactory: WorkerDaemonFactory,
    private val actionExecutionSpecFactory: ActionExecutionSpecFactory,
    private val classLoaderRegistry: ClassLoaderRegistry,
    private val forkOptionsFactory: JavaForkOptionsFactory
) : DefaultTask() {
    @get:Nested
    val scripts: List<ScriptResource>
        get() = scriptsInternal

    private val scriptsInternal: MutableList<ScriptResource> = mutableListOf()
    private val koremodsExtension: KoremodsGradleExtension = project.extensions.getByType(KoremodsGradleExtension::class.java)

    fun script(identifier: Identifier, inputFile: File, outputFile: File) {
        scriptsInternal += project.objects.newInstance(ScriptResource::class.java).also { resource ->
            resource.identifier.set(identifier)
            resource.inputFile.set(inputFile)
            resource.outputFile.set(outputFile)
        }
    }

    @TaskAction
    fun apply(inputChanges: InputChanges) {
        val scriptCompilerClasspathConf = project.configurations.getByName(KoremodsGradlePlugin.SCRIPT_COMPILER_CLASSPATH_CONFIGURATION_NAME)
        val scriptClasspathConf = project.configurations.getByName(KoremodsGradlePlugin.SCRIPT_CLASSPATH_CONFIGURATION_NAME)
        val daemonClassPath = scriptCompilerClasspathConf.resolve() + File(javaClass.protectionDomain.codeSource.location.toURI())
        val scriptLibraries = scriptClasspathConf.resolve()

        project.logger.info("Compiling Koremods scripts")

        val candidateScripts = scripts.filter { script ->
            inputChanges.getFileChanges(script.inputFile).any()
        }
        val params = project.objects.newInstance(CompileScriptAction.CompileScriptParameters::class.java).apply {
            val candidateCompilableScripts = candidateScripts.map { script ->
                CompilableScript(script.identifier.get(), script.inputFile.asFile.map { it.toPath().pathString }.get(), script.outputFile.get().asFile)
            }
            scriptResources.set(candidateCompilableScripts)
            classPath.from(scriptLibraries)
        }
        submitWork(daemonClassPath, params)
    }

    private fun submitWork(classpath: Collection<File>, parameters: CompileScriptAction.CompileScriptParameters?) {
        val workerFactory = if (koremodsExtension.scriptCompilerDaemon.isPresent) workerDaemonFactory else isolatedClassloaderWorkerFactory
        val workerRequirement = getWorkerRequirement(workerFactory.isolationMode, classpath)
        val worker = workerFactory.getWorker(workerRequirement)

        val result = worker.execute(actionExecutionSpecFactory.newIsolatedSpec("koremods script compiler daemon", CompileScriptAction::class.java, parameters, workerRequirement, true))
        if (!result.isSuccess) {
            throw UncheckedException.throwAsUncheckedException(result.exception!!)
        }
    }

    private fun getWorkerRequirement(isolationMode: IsolationMode, classpath: Collection<File>): WorkerRequirement {
        val daemonForkOptions = toDaemonForkOptions(classpath)
        return when (isolationMode) {
            IsolationMode.CLASSLOADER -> IsolatedClassLoaderWorkerRequirement(daemonForkOptions.javaForkOptions.workingDir, daemonForkOptions.classLoaderStructure)
            IsolationMode.PROCESS -> ForkedWorkerRequirement(daemonForkOptions.javaForkOptions.workingDir, daemonForkOptions)
            else -> throw IllegalArgumentException("Received worker with unsupported isolation mode: $isolationMode")
        }
    }

    private fun toDaemonForkOptions(classpath: Collection<File>): DaemonForkOptions {
        val classLoaderStructure = HierarchicalClassLoaderStructure(classLoaderRegistry.gradleWorkerExtensionSpec)
            .withChild(getKotlinFilterSpec())
            .withChild(VisitableURLClassLoader.Spec("worker-loader", DefaultClassPath.of(classpath).asURLs))

        val extensionOptions = koremodsExtension.scriptCompilerDaemon.orNull
        val javaForkOptions = forkOptionsFactory.newJavaForkOptions().apply {
            maxHeapSize = extensionOptions?.maxHeapSize ?: "256M"
        }

        return DaemonForkOptionsBuilder(forkOptionsFactory)
            .javaForkOptions(javaForkOptions)
            .withClassLoaderStructure(classLoaderStructure)
            .keepAliveMode(KeepAliveMode.DAEMON)
            .build()
    }

    private fun getKotlinFilterSpec(): FilteringClassLoader.Spec {
        return classLoaderRegistry.gradleApiFilterSpec.apply {
            disallowPackage("kotlin")
        }
    }
}