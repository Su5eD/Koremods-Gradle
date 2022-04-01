package wtf.gofancy.koremods.gradle

import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import javax.inject.Inject

open class KoremodsExtension @Inject internal constructor(project: Project) {
    companion object {
        const val EXTENSION_NAME = "koremods"
    }
    
    val configFile: RegularFileProperty = project.objects.fileProperty()
}
