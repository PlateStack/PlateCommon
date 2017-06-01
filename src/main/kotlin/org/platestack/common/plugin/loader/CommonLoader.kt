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
import org.platestack.api.plugin.version.Version
import org.platestack.api.plugin.version.VersionRange
import org.platestack.api.server.PlateServer
import org.platestack.api.server.PlateStack
import org.platestack.api.server.PlatformNamespace
import org.platestack.api.server.internal.InternalAccessor
import org.platestack.structure.immutable.toImmutableHashSet
import org.platestack.structure.immutable.toImmutableList
import java.io.File
import java.io.IOException
import java.lang.reflect.Modifier
import java.net.URL
import java.net.URLClassLoader
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf

class CommonLoader: PlateLoader() {
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

        return classesToLoad
    }

    private class PluginClassLoader(url: URL, parent: ClassLoader): URLClassLoader(arrayOf(url), parent) {
        override fun findClass(name: String): Class<*> {
            if(name.startsWith("org.platestack") || name.startsWith("net.minecraft"))
                throw ClassNotFoundException(name)

            return super.findClass(name)
        }
    }

    private data class LoadingClass(val file: URL, val name: String, val classLoader: PluginClassLoader, var kClass: KClass<out PlatePlugin>, var metadata: PlateMetadata)

    private fun loadClasses(file: URL, classNames: Map<String, PlateMetadata>): Map<String, LoadingClass?> {
        if(classNames.isEmpty())
            return emptyMap()

        val pluginClass = PlatePlugin::class
        val loader = PluginClassLoader(file, javaClass.classLoader)
        return classNames.asSequence()
                .map { (it, _) ->
                    println("Loading the class $it from $file")
                    it to loader.loadClass(it).kotlin
                }
                .map { (name, `class`) ->

                    if(`class`.isSubclassOf(pluginClass)) {
                        val annotation = `class`.findAnnotation<Plate>()
                        if(annotation == null) {
                            println("The class $`class` is not contains @Plate annotation and will not be loaded!")
                            name to null
                        }
                        else {
                            @Suppress("UNCHECKED_CAST")
                            name to LoadingClass(file, name, loader, `class` as KClass<out PlatePlugin>, PlateMetadata(annotation))
                        }
                    }
                    else {
                        println("The class $`class` does not extends ${pluginClass.simpleName} and will not be loaded!")
                        name to null
                    }

                }
                .toMap()
    }

    override fun load(files: Set<URL>): List<PlatePlugin> {
        val loadingClasses = files.asSequence()
                .map { it to scan(it) }
                .map { (url, classes) -> loadClasses(url, classes) }
                .flatMap { it.asSequence() }
                .map { (name, loading) -> name to loading }
                .toMap()

        val loadingPlugins = loadingClasses.asSequence()
                .mapNotNull { it.value }
                .associate { it.metadata.id to it }

        val order = PlateStack.internal.resolveOrder(loadingClasses.values.mapNotNull { it?.metadata })

        return order.map {
            val data = loadingPlugins[it.id] ?: error("Could not find the plugin data for ${it.id}.\n\nOrder: $order\n\nLoading plugins: $loadingClasses\n\nLoading classes: $loadingClasses\n\nURLs: $files")
            synchronized(this) {
                println("Loading ${it.name} ${it.version} -- #${it.id} ${data.kClass}")
                getOrCreateInstance(data.metadata, data.kClass)
            }
        }
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

            override fun resolveOrder(metadata: Collection<PlateMetadata>) = metadata.toList()
        }
    }

    val testFiles = arrayOf(
            "D:\\_InteliJ\\CleanDishes\\001 Simple Hello World\\Kotlin\\build\\libs\\001 Simple Hello World - Kotlin-0.1.0-SNAPSHOT.jar"
            //,"D:\\_InteliJ\\CleanDishes\\001 Simple Hello World\\Java\\build\\libs\\001 Simple Hello World - Java-0.1.0-SNAPSHOT.jar"
    )

    PlateNamespace.loader = CommonLoader()
    PlateNamespace.loader.load(testFiles.asSequence().map { File(it).toURI().toURL() }.toSet())
}
