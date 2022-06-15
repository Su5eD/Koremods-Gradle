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
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import wtf.gofancy.koremods.Identifier
import wtf.gofancy.koremods.compileScriptResult
import wtf.gofancy.koremods.readScriptSource
import java.io.File
import java.io.FileOutputStream
import java.io.Serializable
import java.lang.invoke.MethodHandles
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.Path
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.dependencies
import kotlin.script.experimental.jvm.impl.*
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.util.PropertiesCollection

/**
 * Contains resolved compilation configuration values of a koremods script.
 * Implements Serializable so that it can be sent to the script compiler daemon.
 * 
 * @param identifier the script's unique [Identifier]
 * @param sourcePath path to the script's source file
 * @param outputFile the compiled script's output file
 */
data class CompilableScript(val identifier: Identifier, val sourcePath: String, val outputFile: File) : Serializable

/**
 * Gradle Worker script compilation action used to parallelly compile koremods scripts in an isolated environment.
 */
abstract class CompileScriptAction : WorkAction<CompileScriptAction.CompileScriptParameters> {
    interface CompileScriptParameters : WorkParameters {
        /**
         * A list of candidate compilable scripts
         * 
         * @see CompilableScript
         */
        val scriptResources: ListProperty<CompilableScript>

        /**
         * The koremods script compilation classpath, containing script dependencies
         */
        val classPath: ConfigurableFileCollection
    }

    override fun execute() {
        // The thread pool's size, equivalent to the amount of candidate scripts
        val threads = parameters.scriptResources.get().size
        // Create a new thread pool of a fixed size
        val executor = Executors.newFixedThreadPool(threads)

        // Compile scripts in parallel using the executor
        val futures = parameters.scriptResources.get().map { script ->
            executor.submit { compileScript(script) }
        }
        
        // Wait for the scripts to finish compiling, for at most 5 seconds
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        // Get the compilation results to throw an exception in case an error occured during compilation
        futures.forEach(Future<*>::get)
    }

    /**
     * Compile a source script with the Kotlin embedded compiler
     * 
     * @param script the script to be compiled
     */
    private fun compileScript(script: CompilableScript) {
        // Read the script's source
        val source = readScriptSource(script.identifier, Path(script.sourcePath))
        // Compile the script using the configured compilation classpath
        val compiled = compileScriptResult(script.identifier, source) {
            jvm {
                updateClasspath(parameters.classPath.files)
            }
        }
        // Ensure the resulting object is the correct type (other types may be returned by loading the script from a jar, for example)
        val compiledScript = compiled as? KJvmCompiledScript ?: throw IllegalArgumentException("Unsupported compiled script type $compiled")
        // Save the compiled script to the output file jar
        compiledScript.saveScriptToJar(script.outputFile)
    }
}

/**
 * Save the compiled script to a jar file. As opposed to kotlin's build-in
 * [saveToJar][kotlin.script.experimental.jvmhost.saveToJar] method, we focus on serializing as least information
 * as possible to minimize the file size.
 */
fun KJvmCompiledScript.saveScriptToJar(outputJar: File) {
    // Get the compiled module, which contains the output files
    val module = getCompiledModule().let { module ->
        // Ensure the module is of the correct type
        // (other types may be returned if the script is cached, for example, which is undesired)
        module as? KJvmCompiledModuleInMemory ?: throw IllegalArgumentException("Unsupported module type $module")
    }
    FileOutputStream(outputJar).use { fileStream ->
        // The compiled script jar manifest
        val manifest = Manifest().apply {
            mainAttributes.apply {
                putValue("Manifest-Version", "1.0")
                putValue("Created-By", "JetBrains Kotlin")
                putValue("Main-Class", scriptClassFQName)
            }
        }

        // Create a new JarOutputStream for writing
        JarOutputStream(fileStream, manifest).use { jar ->
            // Write sanitized compiled script metadata
            jar.putNextEntry(JarEntry(scriptMetadataPath(scriptClassFQName)))
            jar.write(copyWithoutModule().apply(::shrinkSerializableScriptData).toBytes())
            jar.closeEntry()

            // Write each output file
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

private val lookup = MethodHandles.lookup()
private val compiledScriptDataClass = Class.forName("kotlin.script.experimental.jvm.impl.KJvmCompiledScriptData")
private val scriptDataGetter = MethodHandles.privateLookupIn(KJvmCompiledScript::class.java, lookup).findGetter(KJvmCompiledScript::class.java, "data", compiledScriptDataClass)
private val sourceLocationIdSetter = MethodHandles.privateLookupIn(compiledScriptDataClass, lookup).findSetter(compiledScriptDataClass, "sourceLocationId", String::class.java)

/**
 * A hack to remove unused information from the compiled script and shrink the serialized file,
 * such as the compilation classpath and default imports.
 */
private fun shrinkSerializableScriptData(compiledScript: KJvmCompiledScript) {
    (compiledScript.compilationConfiguration.entries() as? MutableSet<Map.Entry<PropertiesCollection.Key<*>, Any?>>)
        ?.removeIf { it.key == ScriptCompilationConfiguration.dependencies || it.key == ScriptCompilationConfiguration.defaultImports }

    val scriptData = scriptDataGetter.invoke(compiledScript)
    sourceLocationIdSetter.invoke(scriptData, null)
}