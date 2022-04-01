@file:JvmName("KoremodsScriptCompiler")
package wtf.gofancy.koremods.compile

import kotlinx.coroutines.runBlocking
import wtf.gofancy.koremods.script.KoremodsKtsScript
import java.io.File
import kotlin.script.experimental.api.hostConfiguration
import kotlin.script.experimental.api.valueOrThrow
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvmhost.JvmScriptCompiler
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.script.experimental.jvmhost.saveToJar

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
//        compiled.reports.forEach(::println)

        val compiledScript = compiled.valueOrThrow() as KJvmCompiledScript
        compiledScript.saveToJar(destFile)
    }
}