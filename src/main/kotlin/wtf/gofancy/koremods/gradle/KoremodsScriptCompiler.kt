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

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Nested
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import wtf.gofancy.koremods.compileScriptResult
import wtf.gofancy.koremods.readScriptSource
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.Path
import kotlin.script.experimental.jvm.impl.*
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath

abstract class CompileScriptAction : WorkAction<CompileScriptAction.CompileScriptParameters> {
    interface CompileScriptParameters : WorkParameters {
        @get:Nested
        val scriptResources: ListProperty<ScriptResource>
        val classPath: ConfigurableFileCollection
    }

    override fun execute() {
        val threads = parameters.scriptResources.get().size
        val executor = Executors.newFixedThreadPool(threads)

        parameters.scriptResources.get().forEach { script ->
            executor.submit { compileScript(script) }
        }

        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun compileScript(script: ScriptResource) {
        val source = readScriptSource(script.identifier, Path(script.sourcePath))
        val compiled = compileScriptResult(script.identifier, source) {
            jvm {
                updateClasspath(parameters.classPath.files)
            }
        }
        val compiledScript = compiled as? KJvmCompiledScript ?: throw IllegalArgumentException("Unsupported compiled script type $compiled")
        compiledScript.saveScriptToJar(script.outputFile)
    }
}

fun KJvmCompiledScript.saveScriptToJar(outputJar: File) {
    val module = getCompiledModule().let {
        it as? KJvmCompiledModuleInMemory ?: throw IllegalArgumentException("Unsupported module type $it")
    }
    FileOutputStream(outputJar).use { fileStream ->
        val manifest = Manifest().apply {
            mainAttributes.apply {
                putValue("Manifest-Version", "1.0")
                putValue("Created-By", "JetBrains Kotlin")
                putValue("Main-Class", scriptClassFQName)
            }
        }

        JarOutputStream(fileStream, manifest).use { jar ->
            jar.putNextEntry(JarEntry(scriptMetadataPath(scriptClassFQName)))
            jar.write(copyWithoutModule().toBytes())
            jar.closeEntry()

            module.compilerOutputFiles.forEach { (path, bytes) ->
                jar.putNextEntry(JarEntry(path))
                jar.write(bytes)
                jar.closeEntry()
            }

            jar.finish()
            jar.flush()
        }
        fileStream.flush()
    }
}