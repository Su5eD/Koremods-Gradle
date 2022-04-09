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
import org.gradle.api.tasks.*
import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.net.URLClassLoader

@CacheableTask
open class CompileKoremodsScriptsTask : DefaultTask() {
    @get:Nested
    val filesMap: ListProperty<ScriptFileMapping> = project.objects.listProperty(ScriptFileMapping::class.java)

    class ScriptFileMapping(
        @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) val inputFile: File,
        @get:OutputFile val outputFile: File
    )

    @TaskAction
    fun apply() {
        val projectConf = project.configurations.getByName(KoremodsGradlePlugin.KOREMODS_CONFIGURATION_NAME)
        val koremodsDep = projectConf.singleFile
        val scriptCompileDepsConf = project.configurations.getByName(KoremodsGradlePlugin.SCRIPTING_COMPILE_DEPS_CONFIGURATION_NAME)
        val scriptRuntimeDepsConf = project.configurations.getByName(KoremodsGradlePlugin.SCRIPTING_RUNTIME_DEPS_CONFI6GURATION_NAME)

        val loadedURLs = scriptCompileDepsConf.resolve()
            .plus(koremodsDep)
            .map { it.toURI().toURL() }
            .plus(javaClass.protectionDomain.codeSource.location)
            .toTypedArray()
        val classloader = URLClassLoader(loadedURLs, ClassLoader.getSystemClassLoader())
        val scriptLibraries = scriptRuntimeDepsConf.resolve() + koremodsDep

        val cls = classloader.loadClass("wtf.gofancy.koremods.compile.KoremodsScriptCompiler")
        val compileMethod = MethodHandles.lookup().findStatic(cls, "compileScript", MethodType.methodType(Void::class.javaPrimitiveType, String::class.java, File::class.java, Collection::class.java))

        project.logger.info("Compiling Koremods scripts")

        filesMap.get().forEach { scriptFiles ->
            val saveFile = scriptFiles.outputFile
            val source = scriptFiles.inputFile.readText()
            compileMethod.invoke(source, saveFile, scriptLibraries)
        }
    }
}