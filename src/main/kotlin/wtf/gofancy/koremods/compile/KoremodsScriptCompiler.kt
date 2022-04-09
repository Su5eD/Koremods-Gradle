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

import wtf.gofancy.koremods.RawScript
import wtf.gofancy.koremods.compileScriptResult
import wtf.gofancy.koremods.readScriptSource
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.script.experimental.jvm.impl.*
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath

@Suppress("UNUSED")
fun compileScriptPack(script: RawScript<Path>, destFile: File, classPath: Collection<File>) {
    val source = readScriptSource(script.identifier, script.source)
    val compiled = compileScriptResult(script.identifier, source) {
        jvm {
            updateClasspath(classPath)
        }
    }
    val compiledScript = compiled as? KJvmCompiledScript ?: throw IllegalArgumentException("Unsupported compiled script type $compiled")
    compiledScript.saveScriptToJar(destFile)
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