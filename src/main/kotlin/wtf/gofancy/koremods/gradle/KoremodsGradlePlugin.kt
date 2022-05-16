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
        
        const val MINECRAFT_CLASSPATH_TOKEN = "minecraft_classpath"
    }

    override fun apply(project: Project) {
        val koremodsExtension = project.extensions.create(KoremodsGradleExtension.EXTENSION_NAME, KoremodsGradleExtension::class.java)
        val koremodsImplementation = project.configurations.create(KOREMODS_CONFIGURATION_NAME) { conf ->
            conf.attributes.attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category::class.java, Category.LIBRARY))
            conf.attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        }

        project.plugins.apply(JavaPlugin::class.java)
        project.configurations.named(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME)
            .configure { conf -> conf.extendsFrom(koremodsImplementation) }

        project.createUsageConfiguration(SCRIPT_COMPILER_CLASSPATH_CONFIGURATION_NAME, SCRIPT_COMPILER_CLASSPATH_USAGE, koremodsImplementation)
        project.createUsageConfiguration(SCRIPT_CLASSPATH_CONFIGURATION_NAME, Usage.JAVA_API, koremodsImplementation)

        project.dependencies.attributesSchema.getMatchingStrategy(Usage.USAGE_ATTRIBUTE).compatibilityRules
            .add(ScriptCompilerClasspathUsageCompatibilityRule::class.java)

        project.afterEvaluate { proj ->
            koremodsExtension.apply(proj)
            
            proj.minecraftExtension?.apply {
                val koremodsClassPath: () -> String = {
                    koremodsImplementation.copyRecursive()
                        .resolve()
                        .joinToString(separator = File.pathSeparator, transform = File::getAbsolutePath)
                }
                
                runs.all { run -> 
                    val oldClassPath = run.lazyTokens[MINECRAFT_CLASSPATH_TOKEN]
                    run.lazyToken(MINECRAFT_CLASSPATH_TOKEN) {
                        val classPath = koremodsClassPath()
                        
                        if (oldClassPath != null) "${oldClassPath.get()}${File.pathSeparator}$classPath"
                        else classPath
                    }
                }
            }
        }
    }

    private fun Project.createUsageConfiguration(name: String, usage: String, parent: Configuration) {
        configurations.create(name) { conf ->
            conf.attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, usage))

            conf.extendsFrom(parent)
        }
    }
}

internal val Project.minecraftExtension: MinecraftExtension?
    get() = extensions.findByType(MinecraftExtension::class.java)

private class ScriptCompilerClasspathUsageCompatibilityRule : AttributeCompatibilityRule<Usage> {
    override fun execute(details: CompatibilityCheckDetails<Usage>) {
        if (details.consumerValue != null && details.producerValue != null
            && details.consumerValue!!.name == KoremodsGradlePlugin.SCRIPT_COMPILER_CLASSPATH_USAGE && details.producerValue!!.name == Usage.JAVA_RUNTIME
        ) details.compatible()
    }
}