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

@file:JvmName("KoremodsScriptCompiler")

package wtf.gofancy.koremods.compile

import kotlinx.coroutines.runBlocking
import wtf.gofancy.koremods.script.KoremodsKtsScript
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.script.experimental.api.hostConfiguration
import kotlin.script.experimental.api.valueOrThrow
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.impl.*
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvmhost.JvmScriptCompiler
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

@Suppress("unused")
fun compileScript(source: String, destFile: File, classPath: Collection<File>) {
    val compileConf = createJvmCompilationConfigurationFromTemplate<KoremodsKtsScript> {
        jvm {
            updateClasspath(classPath)
        }
        hostConfiguration(ScriptingHostConfiguration()) // TODO TEMP disable cache
    }

    val compiler = JvmScriptCompiler()

    runBlocking {
        val compiled = compiler(source.toScriptSource(), compileConf)
        val compiledValue = compiled.valueOrThrow()
        val compiledScript = (compiledValue as? KJvmCompiledScript)
            ?: throw IllegalArgumentException("Unsupported compiled script type $compiledValue")
        compiledScript.saveScriptToJar(destFile)
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