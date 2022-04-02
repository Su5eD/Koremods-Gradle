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