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

import net.minecraftforge.gradle.common.util.MinecraftExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.AttributeCompatibilityRule
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.CompatibilityCheckDetails
import org.gradle.api.attributes.Usage
import org.gradle.api.plugins.JavaPlugin
import java.io.File

class KoremodsGradlePlugin : Plugin<Project> {
    companion object {
        const val KOREMODS_CONFIGURATION_NAME = "koremods"

        const val SCRIPT_COMPILER_CLASSPATH_CONFIGURATION_NAME = "koremodsScriptCompilerClasspath"
        const val SCRIPT_CLASSPATH_CONFIGURATION_NAME = "koremodsScriptClasspath"
        const val SCRIPT_COMPILER_CLASSPATH_USAGE = "script-compiler-classpath"

        /**
         * ForgeGradle RunConfig token used for the runtime classpath
         */
        const val MINECRAFT_CLASSPATH_TOKEN = "minecraft_classpath"
    }

    override fun apply(project: Project) {
        // Create the Koremods project extension
        val koremodsExtension = project.extensions.create(KoremodsGradleExtension.EXTENSION_NAME, KoremodsGradleExtension::class.java)
        // Create the koremods configuration for adding required runtime dependencies (such as koremods-script and frontends)
        val koremodsImplementation = project.configurations.create(KOREMODS_CONFIGURATION_NAME) { conf ->
            // Set the category attribute to LIBRARY
            conf.attributes.attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category::class.java, Category.LIBRARY))
            // Set the usage attribute to JAVA_RUNTIME
            conf.attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        }

        // Apply the java plugin in case it wasn't applied yet
        project.plugins.apply(JavaPlugin::class.java)
        // Make implementation extend from koremods, adds koremods dependencies to compile and runtime classpaths
        project.configurations.named(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME)
            .configure { conf -> conf.extendsFrom(koremodsImplementation) }

        // Create the script compiler classpath configuration, used as the classpath of the isolated script compiler environment.
        // Variants with the 'script-compiler-classpath' usage attribute will be selected primarily.
        // In case one doesn't exist, 'JAVA_RUNTIME' will be selected instead
        project.createUsageConfiguration(SCRIPT_COMPILER_CLASSPATH_CONFIGURATION_NAME, SCRIPT_COMPILER_CLASSPATH_USAGE, koremodsImplementation)
        // Create the script compilation classpath, used for compiling koremods scripts.
        // Requests the 'JAVA_API' usage variant for compile-time artifacts
        project.createUsageConfiguration(SCRIPT_CLASSPATH_CONFIGURATION_NAME, Usage.JAVA_API, koremodsImplementation)

        // Make 'JAVA_RUNTIME' compatible with 'script-compiler-classpath' to use as a fallback
        project.dependencies.attributesSchema.getMatchingStrategy(Usage.USAGE_ATTRIBUTE).compatibilityRules
            .add(ScriptCompilerClasspathUsageCompatibilityRule::class.java)

        project.afterEvaluate { proj ->
            // Apply the koremodsExtension's configured values
            koremodsExtension.apply(proj)
            
            // ForgeGradle RunConfig runtime classpath integration
            proj.minecraftExtension?.apply {
                // Resolve the koremods configuration and join the file paths
                val koremodsClassPath: () -> String = {
                    koremodsImplementation.copyRecursive()
                        .resolve()
                        .joinToString(separator = File.pathSeparator, transform = File::getAbsolutePath)
                }
                
                // Add koremodsClassPath to each run configuration's minecraft_classpath token
                runs.all { run -> 
                    // Get the currect minecraft_classpath value
                    val oldClassPath = run.lazyTokens[MINECRAFT_CLASSPATH_TOKEN]
                    // Wrap the lazy token so that we can add our own part
                    run.lazyToken(MINECRAFT_CLASSPATH_TOKEN) {
                        val classPath = koremodsClassPath()
                        // Merge the existing value with koremodsClassPath if it exists
                        if (oldClassPath != null) "${oldClassPath.get()}${File.pathSeparator}$classPath"
                        else classPath
                    }
                }
            }
        }
    }

    /**
     * Create a [Configuration] with a specific [Usage] attribute and parent.
     * 
     * @param name the name of the configuration
     * @param usage the value of the Usage attribute
     * @param parent the configuration to extend from
     */
    private fun Project.createUsageConfiguration(name: String, usage: String, parent: Configuration) {
        configurations.create(name) { conf ->
            conf.attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, usage))
            conf.extendsFrom(parent)
        }
    }
}

/**
 * Gets the minecraft extension if the ForgeGradle plugin is applied, otherwise null 
 */
internal val Project.minecraftExtension: MinecraftExtension?
    get() = extensions.findByType(MinecraftExtension::class.java)

/**
 * A rule to make [Usage.JAVA_RUNTIME] compatible with [KoremodsGradlePlugin.SCRIPT_COMPILER_CLASSPATH_USAGE]
 */
private class ScriptCompilerClasspathUsageCompatibilityRule : AttributeCompatibilityRule<Usage> {
    override fun execute(details: CompatibilityCheckDetails<Usage>) {
        if (details.consumerValue != null && details.producerValue != null
            && details.consumerValue!!.name == KoremodsGradlePlugin.SCRIPT_COMPILER_CLASSPATH_USAGE
            && details.producerValue!!.name == Usage.JAVA_RUNTIME
        ) details.compatible()
    }
}