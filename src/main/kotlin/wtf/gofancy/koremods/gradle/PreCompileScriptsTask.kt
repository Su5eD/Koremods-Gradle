package wtf.gofancy.koremods.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import org.gradle.internal.classloader.ClasspathUtil
import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

@CacheableTask
abstract class PreCompileScriptsTask : DefaultTask() {
    companion object {
        // TODO Remove hardcoding
        const val KOTLIN_VERSION = "1.6.10"
        val COMPILE_DEP_NAMES = setOf("kotlin-scripting-", "kotlin-script-runtime", "kotlin-compiler-embeddable")
        val RUNTIME_DEP_NAMES = setOf("asm", "asm-commons", "asm-tree")
        val DEP_PACKAGES = setOf("org.jetbrains.kotlin.scripting.", "kotlin.script.", "wtf.gofancy.koremods.")
    }

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputScripts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        outputDir.convention(project.layout.buildDirectory.dir("koremods/${name}"))
    }

    @TaskAction
    fun apply() {
        val projectConf = project.configurations.getByName("koremodsImplementation")
        val koremodsDep = projectConf.singleFile

        val classpath = ClasspathUtil.getClasspath(Thread.currentThread().contextClassLoader).asURLs
        val deps = classpath
                .filter { url -> COMPILE_DEP_NAMES.any(url.path::contains) && url.path.contains(KOTLIN_VERSION) }
                .plus(javaClass.protectionDomain.codeSource.location)
                .plus(koremodsDep.toURI().toURL())
                .toTypedArray()
        val scriptLibraries = classpath
                .filter { url -> RUNTIME_DEP_NAMES.any(url.path::contains) }
                .map { File(it.toURI()) }
                .plus(koremodsDep)
                .toSet()
        val classloader = KoremodsClassLoader(DEP_PACKAGES, deps)

        val cls = classloader.loadClass("wtf.gofancy.koremods.compile.KoremodsScriptCompiler")
        val compileMethod = MethodHandles.lookup().findStatic(cls, "compileScript", MethodType.methodType(Void::class.javaPrimitiveType, String::class.java, File::class.java, Collection::class.java))
        
        project.logger.info("Compiling koremods scripts")

        val outputDir = outputDir.get()
        inputScripts.forEach { file ->
            val saveFile = outputDir.file("${file.nameWithoutExtension}.jar").asFile
            val source = file.readText()

            compileMethod.invoke(source, saveFile, scriptLibraries)
        }
    }
}