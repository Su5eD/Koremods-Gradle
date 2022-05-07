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
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.initialization.ClassLoaderRegistry
import org.gradle.internal.UncheckedException
import org.gradle.internal.classloader.FilteringClassLoader
import org.gradle.internal.classloader.VisitableURLClassLoader
import org.gradle.internal.classpath.DefaultClassPath
import org.gradle.process.internal.JavaForkOptionsFactory
import org.gradle.workers.internal.*
import wtf.gofancy.koremods.RawScript
import java.io.File
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.pathString

@CacheableTask
abstract class CompileKoremodsScriptsTask @Inject constructor(
    private val isolatedClassloaderWorkerFactory: IsolatedClassloaderWorkerFactory,
    private val workerDaemonFactory: WorkerDaemonFactory,
    private val actionExecutionSpecFactory: ActionExecutionSpecFactory,
    private val classLoaderRegistry: ClassLoaderRegistry,
    private val forkOptionsFactory: JavaForkOptionsFactory
) : DefaultTask() {
    @get:Nested
    abstract val scripts: ListProperty<ScriptResource>

    @get:Input
    abstract val isolateProcess: Property<Boolean>

    class ScriptResource(
        @get:Internal val script: RawScript<Path>,
        @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) val inputFile: File,
        @get:OutputFile val outputFile: File
    )

    @TaskAction
    fun apply() {
        val koremodsConf = project.configurations.getByName(KoremodsGradlePlugin.KOREMODS_CONFIGURATION_NAME)
        val koremodsDep = koremodsConf.singleFile
        val scriptCompilerClasspath = project.configurations.getByName(KoremodsGradlePlugin.SCRIPT_COMPILER_CLASSPATH_CONFIGURATION_NAME)
        val scriptClasspath = project.configurations.getByName(KoremodsGradlePlugin.SCRIPT_CLASSPATH_CONFIGURATION_NAME)

        val daemonClassPath = scriptCompilerClasspath.resolve()
            .plus(File(javaClass.protectionDomain.codeSource.location.toURI()))
        val scriptLibraries = scriptClasspath.resolve() + koremodsDep

        project.logger.info("Compiling Koremods scripts")
        scripts.get().forEach { script ->
            val params = project.objects.newInstance(CompileScriptAction.CompileScriptParameters::class.java)
            params.identifier.set(script.script.identifier)
            params.scriptPath.set(script.script.source.pathString)
            params.classPath.from(scriptLibraries)
            params.destFile.set(script.outputFile)

            submitWork(daemonClassPath, params)
        }
    }

    private fun submitWork(classpath: Collection<File>, parameters: CompileScriptAction.CompileScriptParameters?) {
        val workerFactory = if (isolateProcess.get()) workerDaemonFactory else isolatedClassloaderWorkerFactory
        val workerRequirement = getWorkerRequirement(workerFactory.isolationMode, classpath)
        val worker = workerFactory.getWorker(workerRequirement)

        val result = worker.execute(actionExecutionSpecFactory.newIsolatedSpec("koremods script compiler daemon", CompileScriptAction::class.java, parameters, workerRequirement, true))
        if (!result.isSuccess) {
            throw UncheckedException.throwAsUncheckedException(result.exception!!)
        }
    }

    private fun toDaemonForkOptions(classpath: Collection<File>): DaemonForkOptions {
        val classLoaderStructure = HierarchicalClassLoaderStructure(classLoaderRegistry.gradleWorkerExtensionSpec)
            .withChild(getKotlinFilterSpec())
            .withChild(VisitableURLClassLoader.Spec("worker-loader", DefaultClassPath.of(classpath).asURLs))

        val javaForkOptions = forkOptionsFactory.newJavaForkOptions()
        javaForkOptions.maxHeapSize = "128M"

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

    private fun getWorkerRequirement(isolationMode: IsolationMode, classpath: Collection<File>): WorkerRequirement {
        val daemonForkOptions = toDaemonForkOptions(classpath)
        return when (isolationMode) {
            IsolationMode.CLASSLOADER -> IsolatedClassLoaderWorkerRequirement(daemonForkOptions.javaForkOptions.workingDir, daemonForkOptions.classLoaderStructure)
            IsolationMode.PROCESS -> ForkedWorkerRequirement(daemonForkOptions.javaForkOptions.workingDir, daemonForkOptions)
            else -> throw IllegalArgumentException("Received worker with unsupported isolation mode: $isolationMode")
        }
    }
}