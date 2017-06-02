/*
 *  Copyright (C) 2017 José Roberto de Araújo Júnior
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.platestack.common.plugin.loader

import com.google.gson.JsonObject
import org.objectweb.asm.*
import org.platestack.api.message.Text
import org.platestack.api.message.Translator
import org.platestack.api.plugin.*
import org.platestack.api.plugin.annotation.Plate
import org.platestack.api.plugin.exception.ConflictingPluginsException
import org.platestack.api.plugin.exception.DuplicatedPluginException
import org.platestack.api.plugin.exception.MissingDependenciesException
import org.platestack.api.plugin.exception.PluginLoadingException
import org.platestack.api.plugin.version.Version
import org.platestack.api.plugin.version.VersionRange
import org.platestack.api.server.PlateServer
import org.platestack.api.server.PlateStack
import org.platestack.api.server.PlatformNamespace
import org.platestack.api.server.internal.InternalAccessor
import org.platestack.common.plugin.dependency.DependencyResolution
import org.platestack.structure.immutable.immutableListOf
import org.platestack.structure.immutable.toImmutableHashSet
import org.platestack.structure.immutable.toImmutableList
import java.io.File
import java.io.IOException
import java.lang.reflect.Modifier
import java.net.URL
import java.net.URLClassLoader
import java.util.jar.JarEntry
import java.util.jar.JarInputStream

class CommonLoader: PlateLoader() {
    override var loadingOrder = immutableListOf<String>(); private set

    inline private fun JarInputStream.forEachEntry(action: (JarEntry) -> Unit) {
        while (null != nextJarEntry?.also(action)) {
            // Action already executed
        }
    }

    private object Descriptor {
        val id = Type.getDescriptor(org.platestack.api.plugin.annotation.ID::class.java)!!
        val plate = Type.getDescriptor(Plate::class.java)!!
        val version = Type.getDescriptor(org.platestack.api.plugin.annotation.Version::class.java)!!
        val versionRange = Type.getDescriptor(org.platestack.api.plugin.annotation.VersionRange::class.java)!!
        val relation = Type.getDescriptor(org.platestack.api.plugin.annotation.Relation::class.java)!!
        val relationType = Type.getDescriptor(RelationType::class.java)!!
    }
    private object Abort: Throwable()

    private class PlateAnnotationVisitor(private val callback: (PlateMetadata) -> Unit) : AnnotationVisitor(Opcodes.ASM5) {
        var id: String? = null
        var name: String? = null
        var version: Version? = null
        val relations = mutableListOf<Relation>()
        override fun visit(name: String, value: Any) {
            if(value !is String)
                return

            when(name) {
                "id" -> id = value
                "name" -> this.name = value
            }
        }

        override fun visitAnnotation(name: String, desc: String): AnnotationVisitor? {
            if(name == "version" && desc == Descriptor.version)
                return VersionAnnotationVisitor { version = it }
            return null
        }

        override fun visitArray(name: String): AnnotationVisitor? {
            if(name == "relations")
                return RelationArrayAnnotationVisitor { relations += it }
            return null
        }

        override fun visitEnd() {
            val data = PlateMetadata(
                    checkNotNull(id) { "The @Plate annotation does not defines an ID" },
                    checkNotNull(name) { "The @Plate annotation does not defines a name "},
                    checkNotNull(version) { "The @Plate annotation does not defines a version" },
                    relations.toImmutableList()
            )
            callback(data)
        }
    }

    private class VersionAnnotationVisitor(val callback: (Version)->Unit): AnnotationVisitor(Opcodes.ASM5) {
        var major = 0
        var minor = 0
        var patch = 0
        var label = emptyList<String>()
        var metadata = ""
        var value = ""

        override fun visit(name: String, value: Any) {
            if(value is Int) {
                when (name) {
                    "major" -> major = value
                    "minor" -> minor = value
                    "patch" -> patch = value
                }
            }
            else if(value is String) {
                when (name) {
                    "metadata" -> metadata = value
                    "value" -> this.value = value
                }
            }
        }

        override fun visitArray(name: String): AnnotationVisitor? {
            if(name == "label")
                return StringArrayAnnotationVisitor { label = it }
            return null
        }

        override fun visitEnd() {
            val version: Version
            if(value.isNotBlank())
                version = Version.parse(value)
            else
                version = Version(major, minor, patch, label.toImmutableList(), metadata)
            callback(version)
        }
    }

    private class RelationAnnotationVisitor(private val callback: (Relation) -> Unit): AnnotationVisitor(Opcodes.ASM5) {
        var type: RelationType? = null
        val versions = mutableListOf<VersionRange>()
        var id: String? = null
        var namespace: String? = null

        override fun visitArray(name: String): AnnotationVisitor? {
            if(name == "versions")
                return VersionRangeArrayAnnotationVisitor { versions += it }
            return null
        }

        override fun visitAnnotation(name: String, desc: String): AnnotationVisitor? {
            if(name == "id" && desc == Descriptor.id) {
                return IdAnnotationVisitor { id, namespace ->
                    this.id = id
                    this.namespace = namespace
                }
            }
            return null
        }

        override fun visitEnum(name: String, desc: String, value: String) {
            if(name == "type" && desc == Descriptor.relationType) {
                type = RelationType.valueOf(value)
            }
        }

        override fun visitEnd() {
            callback(Relation(
                    checkNotNull(type) { "A @Relation annotation didn't specify the type" },
                    checkNotNull(id) { "A @Relation annotation didn't specify the ID" },
                    checkNotNull(namespace),
                    versions.toImmutableList()
            ))
        }
    }

    private class IdAnnotationVisitor(private val callback: (String, String) -> Unit): AnnotationVisitor(Opcodes.ASM5) {
        var id: String? = null
        var namespace = "plate"
        override fun visit(name: String, value: Any) {
            if(value is String) {
                when(name) {
                    "value" -> id = value
                    "namespace" -> namespace = value
                }
            }
        }

        override fun visitEnd() {
            callback(
                    checkNotNull(id) { "An @ID annotation does not have an ID" },
                    namespace
            )
        }
    }

    private class VersionRangeAnnotationVisitor(private val callback: (VersionRange) -> Unit): AnnotationVisitor(Opcodes.ASM5) {
        var min = Version(0)
        var max = Version(0, label = "0")
        val exclusions = mutableListOf<VersionRange>()
        var unstable = true
        var caseSensitive = false
        var dynamic = ""

        override fun visitAnnotation(name: String, desc: String): AnnotationVisitor? {
            if(desc == Descriptor.version) {
                return VersionAnnotationVisitor {
                    when (name) {
                        "min" -> min = it
                        "max" -> max = it
                    }
                }
            }

            return null
        }

        override fun visitArray(name: String): AnnotationVisitor? {
            if(name == "exclusions")
                return VersionRangeArrayAnnotationVisitor { exclusions += it }
            return null
        }

        override fun visit(name: String, value: Any) {
            if(value is Boolean) {
                when (name) {
                    "unstable" -> unstable = value
                    "caseSensitive" -> caseSensitive = value
                }
            }
            else if(value is String) {
                if(name == "dynmic")
                    dynamic = value
            }
        }

        override fun visitEnd() {
            val (min, max) =
                    if(dynamic.isNotBlank())
                        VersionRange.parse(dynamic)
                    else
                        min to max

            callback(VersionRange(
                    if(min == null || min == Version(0)) null else min,
                    if(max == null || min != null && max < min) null else max,
                    exclusions.toImmutableHashSet(),
                    unstable,
                    caseSensitive
            ))
        }
    }

    private class VersionRangeArrayAnnotationVisitor(private val callback: (List<VersionRange>) -> Unit): AnnotationVisitor(Opcodes.ASM5) {
        private val values = mutableListOf<VersionRange>()

        override fun visitAnnotation(name: String?, desc: String): AnnotationVisitor? {
            if(desc == Descriptor.versionRange)
                return VersionRangeAnnotationVisitor { values += it }
            return null
        }

        override fun visitEnd() {
            callback(values)
        }
    }

    private class RelationArrayAnnotationVisitor(private val callback: (List<Relation>) -> Unit): AnnotationVisitor(Opcodes.ASM5) {
        private val values = mutableListOf<Relation>()

        override fun visitAnnotation(name: String?, desc: String): AnnotationVisitor? {
            if(desc == Descriptor.relation)
                return RelationAnnotationVisitor { values += it }
            return null
        }

        override fun visitEnd() {
            callback(values)
        }
    }

    private class StringArrayAnnotationVisitor(private val callback: (List<String>) -> Unit): AnnotationVisitor(Opcodes.ASM5) {
        private val values = mutableListOf<String>()
        override fun visit(name: String?, value: Any) {
            values += value as String
        }

        override fun visitEnd() {
            callback(values)
        }
    }

    private class ValidPluginClassVisitor(reader: ClassReader, private val callback: (Boolean, String?, PlateMetadata?) -> Unit): ClassVisitor(Opcodes.ASM5) {
        var className: String? = null
        var metadata: PlateMetadata? = null
        var public = false

        init {
            reader.accept(this, 0)
        }

        override fun visit(version: Int, access: Int, name: String?, signature: String?, superName: String?, interfaces: Array<out String>?) {
            className = name
            public = Modifier.isPublic(access)
        }

        override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor? {
            if(desc == Descriptor.plate)
                return PlateAnnotationVisitor { metadata = it }
            return null
        }

        override fun visitEnd() {
            callback(public, className, metadata)
        }
    }

    @Throws(IOException::class)
    private fun scan(file: URL): Map<String, PlateMetadata> {
        val classesToLoad = mutableMapOf<String, PlateMetadata>()
        file.openStream().use { JarInputStream(it).use { input ->
            input.forEachEntry { entry ->
                if(!entry.isDirectory && entry.name.endsWith(".class", ignoreCase = true)) {
                    try {
                        ValidPluginClassVisitor(ClassReader(input)) { public, className, metadata ->
                            if (public && metadata != null && className == entry.name.let { it.substring(0, it.length-6) }) {
                                classesToLoad[Type.getType("L$className;").className] = metadata
                            }
                        }
                    }
                    catch (ignored: Abort) {
                        // We just want to read the annotations and the class header, not the entire class.
                    }
                }
            }
        } }

        // Remove scala's delegations to scala modules
        classesToLoad.keys.removeIf { classesToLoad["$it$"] == classesToLoad[it] }

        return classesToLoad
    }

    private class PluginDependencyClassLoader(parent: ClassLoader, val dependencies: Set<PluginClassLoader>): ClassLoader(parent) {
        constructor(parent: ClassLoader, dependencies: Iterable<PluginClassLoader>): this(parent, dependencies.toSet())
        override fun findClass(name: String): Class<*> {
            dependencies.forEach { sub->
                try {
                    return sub.loadClass(name)
                }
                catch (ignored: ClassNotFoundException){}
            }

            throw ClassNotFoundException(name)
        }
    }

    private class PluginClassLoader(parent: ClassLoader, vararg url: URL): URLClassLoader(url, parent) {
        override fun findClass(name: String): Class<*> {
            if(name.startsWith("org.platestack") || name.startsWith("net.minecraft"))
                throw ClassNotFoundException(name)

            return super.findClass(name)
        }
    }

    @Throws(PluginLoadingException::class)
    override fun load(files: Set<URL>): List<PlatePlugin> {
        // Scan valid @Plate annotated classes
        val scanResults = files.associate { it to scan(it) }

        // Finds duplications and associates the results by the plugin id
        data class RegisteredPlugin(val metadata: PlateMetadata, val url: URL, val className: String)

        val pluginNames = mutableMapOf<String, RegisteredPlugin>()
        scanResults.forEach { url, validClasses ->
            validClasses.forEach { className, metadata ->
                pluginNames.computeIfPresent(metadata.id) { _, registry ->
                    throw DuplicatedPluginException(metadata.id, registry.url, url)
                }

                pluginNames[metadata.id] = RegisteredPlugin(metadata, url, className)
            }
        }

        // Resolves the dependencies
        val dependencyResolution = DependencyResolution(pluginNames.values.map { it.metadata })
        if(dependencyResolution.missingRequired.isNotEmpty()) {
            throw MissingDependenciesException(dependencyResolution.missingRequired)
        }

        if(dependencyResolution.conflicts.isNotEmpty()) {
            throw ConflictingPluginsException(dependencyResolution.conflicts)
        }

        // Defines the loading order
        val loadingOrder = dependencyResolution.createList().let { list ->
            list.associate {
                it to (pluginNames[it.id] ?: error("The dependency resolution created a list which includes a plugin which is not loading.\n\nPlugin: ${it.id}\n\nList: $list\n\nLoading: ${pluginNames.values}"))
            }
        }

        this.loadingOrder = loadingOrder.keys.asSequence().map { it.id }.toImmutableList()

        // Prepare the class loaders
        val topClassLoader = javaClass.classLoader

        val urlDependencies = scanResults.asSequence().map { (url, validClasses) ->
            url to validClasses.values.asSequence().flatMap{
                dependencyResolution.established[it]?.asSequence()
                        ?.filter { it.namespace == "plate"}
                        ?.map { pluginNames[it.id]!!.url }
                        ?: emptySequence()
            }.toSet()
        }.toMap()

        val independentUrls = urlDependencies.entries.asSequence().filter { (url, dependencies) ->
            dependencies.isEmpty() || dependencies == setOf(url)
        }.map { it.key }.toSet()


        val cyclicUrls: Map<URL, Set<URL>> = urlDependencies.mapValues { (url, dependencies) ->
            dependencies.filterTo(mutableSetOf()) {
                it != url && url in urlDependencies[it]!!
            }
        }.filterNot { it.value.isEmpty() }

        val normalDependencyUrls: Map<URL, Set<URL>> = urlDependencies.filter { it.key !in independentUrls }.mapValues { (url, dependencies) ->
            dependencies.filterTo(mutableSetOf()) {
                it != url && cyclicUrls[it]?.contains(it) != true
            }
        }

        val classLoaders = independentUrls.associateTo(mutableMapOf()) { it to PluginClassLoader(topClassLoader, it) }

        fun getClassLoader(url: URL): PluginClassLoader {
            classLoaders[url]?.let { return it }

            val parent = normalDependencyUrls[url]?.let { dependencies ->
                PluginDependencyClassLoader(topClassLoader, dependencies.map { getClassLoader(it) })
            } ?: topClassLoader

            val classLoader = cyclicUrls[url]
                    ?.let { cyclic -> classLoaders.entries.find { it.key in cyclic }?.value ?: PluginClassLoader(parent, *cyclic.toTypedArray()) }
                    ?: PluginClassLoader(parent, url)

            classLoaders[url] = classLoader
            return classLoader
        }

        normalDependencyUrls.keys.forEach { getClassLoader(it) }
        cyclicUrls.keys.forEach { getClassLoader(it) }

        // Loads the classes (may invoke static blocks)
        val classes = loadingOrder.entries.associate { (meta, registry) ->
            val classLoader = classLoaders[registry.url] ?: error("The class loader for ${registry.url} should be available")
            try {
                meta to classLoader.loadPluginClass(meta, registry.className)
            }
            catch (e: ClassNotFoundException) {
                throw PluginLoadingException((if(e.message == null) "" else e.message+". ") + "Offending file: ${registry.url} -- ${registry.metadata.name} ${registry.metadata.version} #${registry.metadata.id}")
            }
        }

        // Instantiates the classes
        val instances = classes.entries.map { (meta, kClass) -> getOrCreateInstance(meta, kClass) }

        // Enable the plugins
        instances.forEach { enable(it) }

        return instances
    }

}

fun main(args: Array<String>) {
    PlateStack = object : PlateServer {
        override val platformName = "test"
        override lateinit var translator: Translator
        override val platform = PlatformNamespace("test" to Version(0,1,0,"SNAPSHOT"))
        @Suppress("OverridingDeprecatedMember")
        override val internal = object : InternalAccessor {
            override fun toJson(text: Text): JsonObject {
                TODO("not implemented")
            }

            override fun resolveOrder(metadata: Collection<PlateMetadata>) = DependencyResolution(metadata).createList()
        }
    }

    val testFiles = arrayOf(
            "D:\\_InteliJ\\CleanDishes\\001 Simple Hello World\\Kotlin\\build\\libs\\001 Simple Hello World - Kotlin-0.1.0-SNAPSHOT.jar",
            "D:\\_InteliJ\\CleanDishes\\001 Simple Hello World\\Java\\build\\libs\\001 Simple Hello World - Java-0.1.0-SNAPSHOT.jar",
            "D:\\_InteliJ\\CleanDishes\\001 Simple Hello World\\Scala\\Gradle\\build\\libs\\001 Simple Hello World - Scala - Gradle-0.1.0-SNAPSHOT.jar",
            "D:\\_InteliJ\\CleanDishes\\001 Simple Hello World\\Groovy\\build\\libs\\001 Simple Hello World - Groovy-0.1.0-SNAPSHOT.jar"
    )

    PlateNamespace.loader = CommonLoader()
    PlateNamespace.loader.load(testFiles.asSequence().map { File(it).toURI().toURL() }.toSet())
}
