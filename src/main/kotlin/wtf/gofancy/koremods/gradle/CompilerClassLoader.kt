package wtf.gofancy.koremods.gradle

import java.net.URL
import java.net.URLClassLoader

class CompilerClassLoader(private val priorityClasses: Set<String>, urls: Array<URL>) : URLClassLoader(urls, Thread.currentThread().contextClassLoader) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (priorityClasses.any(name::startsWith)) {
            val existing = findLoadedClass(name)
            return existing ?: findClass(name)
        }
        return super.loadClass(name, resolve)
    }
}