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
import mu.KLogger
import mu.KotlinLogging
import org.objectweb.asm.*
import org.platestack.api.message.Text
import org.platestack.api.message.Translator
import org.platestack.api.plugin.*
import org.platestack.api.plugin.annotation.Library
import org.platestack.api.plugin.annotation.Plate
import org.platestack.api.plugin.exception.ConflictingPluginsException
import org.platestack.api.plugin.exception.DuplicatedPluginException
import org.platestack.api.plugin.exception.MissingDependenciesException
import org.platestack.api.plugin.exception.PluginLoadingException
import org.platestack.api.plugin.version.MavenArtifact
import org.platestack.api.plugin.version.Version
import org.platestack.api.plugin.version.VersionRange
import org.platestack.api.server.PlateServer
import org.platestack.api.server.PlateStack
import org.platestack.api.server.PlatformNamespace
import org.platestack.api.server.internal.InternalAccessor
import org.platestack.common.plugin.dependency.DependencyResolution
import org.platestack.libraryloader.ivy.LibraryResolver
import org.platestack.structure.immutable.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Modifier
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import kotlin.streams.asSequence

class CommonLoader(logger: KLogger): PlateLoader(logger) {
    override var loadingOrder = immutableListOf<String>(); private set

    public override fun setAPI(metadata: PlateMetadata) {
        super.setAPI(metadata)
    }

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
        val library = Type.getDescriptor(Library::class.java)!!
        object Ceylon {
            val import = "Lcom/redhat/ceylon/compiler/java/metadata/Import;"
            val module = "Lcom/redhat/ceylon/compiler/java/metadata/Module;"
        }
    }

    private class PlateAnnotationVisitor(private val classVersion: Int, private val callback: (PlateMetadata) -> Unit) : AnnotationVisitor(Opcodes.ASM5) {
        var id: String? = null
        var name: String? = null
        var version: Version? = null
        val relations = mutableListOf<Relation>()
        val libraries = mutableListOf<MavenArtifact>()
        var groovy = ""
        var scala = ""
        var kotlin = ""
        var jdk = ""
        override fun visit(name: String, value: Any) {
            if(value !is String)
                return

            when(name) {
                "id" -> id = value
                "name" -> this.name = value
                "groovy" -> groovy = value
                "scala" -> scala = value
                "kotlin" -> kotlin = value
                "jdk" -> jdk = value
            }
        }

        override fun visitAnnotation(name: String, desc: String): AnnotationVisitor? {
            if(name == "version" && desc == Descriptor.version)
                return VersionAnnotationVisitor { version = it }
            return null
        }

        override fun visitArray(name: String): AnnotationVisitor? {
            when(name) {
                "relations" -> RelationArrayAnnotationVisitor { relations += it }
                "requires" -> LibraryArrayAnnotationVisitor { libraries += it }
            }
            return null
        }

        override fun visitEnd() {
            if(jdk.isBlank()) {
                val version =  if(classVersion < 52) 52 else classVersion
                jdk = "#1."+(version - 44)
            }

            if(groovy.isNotBlank()) {
                libraries += MavenArtifact("org.codehaus.groovy", "groovy-all", groovy)
            }

            if(scala.isNotBlank()) {
                libraries += MavenArtifact("org.scala-lang", "scala-library-all", scala)
            }

            if(kotlin.isNotBlank()) {
                libraries += MavenArtifact("org.jetbrains.kotlin", "kotlin-stdlib", kotlin)
            }

            if(relations.none { it.namespace == "plate" && it.id == "platestack" }) {
                relations += Relation(RelationType.REQUIRED_AFTER, "platestack", "plate", immutableListOf(VersionRange()))
            }

            val data = PlateMetadata(
                    checkNotNull(id) { "The @Plate annotation does not defines an ID" },
                    checkNotNull(name) { "The @Plate annotation does not defines a name "},
                    checkNotNull(version) { "The @Plate annotation does not defines a version" },
                    jdk,
                    relations.toImmutableList(),
                    libraries.toImmutableList()
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

    private class LibraryAnnotationVisitor(private val callback: (MavenArtifact) -> Unit): AnnotationVisitor(Opcodes.ASM5) {
        var group: String? = null
        var artifact: String? = null
        var version: String? = null

        override fun visit(name: String, value: Any) {
            if(value is String) {
                when(name) {
                    "group" -> group = value
                    "artifact" -> artifact = value
                    "version" -> version = value
                }
            }
        }

        override fun visitEnd() {
            callback(MavenArtifact(
                    checkNotNull(group) { "A @Library annotation is missing the group parameter" },
                    checkNotNull(artifact) { "A @Library annotation is missing the artifact parameter" },
                    checkNotNull(version) { "A @Library annotation is missing the version parameter" }
            ))
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

    private class LibraryArrayAnnotationVisitor(private val callback: (List<MavenArtifact>) -> Unit): AnnotationVisitor(Opcodes.ASM5) {
        private val values = mutableListOf<MavenArtifact>()

        override fun visitAnnotation(name: String?, desc: String): AnnotationVisitor? {
            if(desc == Descriptor.library)
                return LibraryAnnotationVisitor { values += it }
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
        var version = 0

        init {
            reader.accept(this, 0)
        }

        override fun visit(version: Int, access: Int, name: String?, signature: String?, superName: String?, interfaces: Array<out String>?) {
            this.version = version
            className = name
            public = Modifier.isPublic(access)
        }

        override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor? {
            if(desc == Descriptor.plate)
                return PlateAnnotationVisitor(version) { metadata = it }
            return null
        }

        override fun visitEnd() {
            callback(public, className, metadata)
        }
    }

    private class CeylonModuleClassVisitor(reader: ClassReader, private val callback: (CeylonModule)->Unit): ClassVisitor(Opcodes.ASM5) {
        var module: CeylonModule? = null

        init {
            reader.accept(this, 0)
        }

        override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor? {
            if(desc == Descriptor.Ceylon.module)
                return CeylonModuleAnnotationVisitor { module = it }
            return null
        }

        override fun visitEnd() {
            module?.let(callback)
        }
    }

    private data class CeylonImport(
            val export: Boolean = false,
            val optional: Boolean = false,
            val name: String = "",
            val version: String = "",
            val namespace: String = "",
            val nativeBackends: ImmutableList<String> = immutableListOf()
    )

    private data class HerdArtifact(val name: String, val version: String) {
        fun toHerdMavenRepository(): MavenArtifact {
            return if(name.contains(':')) {
                val parts = name.split(':', limit = 2)
                MavenArtifact(parts[0], parts[1], version)
            }
            else if(name.contains(Regex("\\W\\.\\W"))) {
                MavenArtifact(name.substringBeforeLast('.'), name.substringAfterLast('.'), version)
            }
            else {
                MavenArtifact(name, name, version)
            }
        }

        fun toMavenCentral(): List<MavenArtifact> {
            return if(name.startsWith("ceylon.")) {
                listOf(MavenArtifact("org.ceylon-lang", name, version), toHerdMavenRepository())
            }
            else {
                listOf(toHerdMavenRepository())
            }
        }
    }

    private data class CeylonModule(
            val name: String, val version: String, val doc: String = "",
            val by: ImmutableList<String> = immutableListOf(),
            val license: String = "",
            val dependencies: ImmutableList<CeylonImport> = immutableListOf(),
            val nativeBackends: ImmutableList<String> = immutableListOf()
    ) {
        val jdk by lazy {
            "1."+(
                    (dependencies.find { it.namespace.isBlank() && it.name.startsWith("java.base") }
                        ?: dependencies.find { it.namespace.isBlank() && it.name.startsWith("java.") }
                    )?.version
                    ?: "8"
            )
        }

        val explicitMavenArtifacts by lazy {
            dependencies.asSequence().filter { it.namespace == "maven" && it.name.contains(':') }.map {
                val name = it.name.split(':', limit = 2)
                MavenArtifact(name[0], name[1], it.version)
            }.toList()
        }

        val herdArtifacts by lazy {
            dependencies.asSequence().filter { it.namespace.isBlank() }.map { HerdArtifact(it.name, it.version) }.toList()
        }

        val mavenArtifacts by lazy {
            explicitMavenArtifacts + herdArtifacts.map { it.toHerdMavenRepository() }
        }
    }

    private class CeylonImportArrayAnnotationVisitor(callback: (List<CeylonImport>) -> Unit)
        : ObjectArrayAnnotationVisitor<CeylonImport>(Descriptor.Ceylon.import, ::CeylonImportAnnotationVisitor, callback)

    private open class ObjectArrayAnnotationVisitor<T>(
            private val descriptor: String,
            private val reader: ((T)->Unit)->AnnotationVisitor,
            private val callback: (List<T>) -> Unit
    ): AnnotationVisitor(Opcodes.ASM5) {
        private val values = mutableListOf<T>()

        override fun visitAnnotation(name: String?, desc: String): AnnotationVisitor? {
            if(desc == descriptor)
                return reader { values += it }
            return null
        }

        override fun visitEnd() {
            callback(values)
        }
    }

    private class CeylonImportAnnotationVisitor(private val callback: (CeylonImport) -> Unit): AnnotationVisitor(Opcodes.ASM5) {
        var export = false
        var optional = false
        var name = ""
        var version = ""
        var namespace = ""
        var nativeBackends = emptyList<String>()

        override fun visit(name: String, value: Any) {
            if(value is String) {
                when(name) {
                    "name" -> this.name = value
                    "version" -> version = value
                    "namespace" -> namespace = value
                }
            } else if(value is Boolean) {
                when(name) {
                    "export" -> export = value
                    "optional" -> optional = value
                }
            }
        }

        override fun visitArray(name: String): AnnotationVisitor? {
            if(name == "nativeBackends")
                return StringArrayAnnotationVisitor { nativeBackends = it }
            return null
        }

        override fun visitEnd() {
            val ceylonImport = CeylonImport(export, optional, name, version, namespace, nativeBackends.toImmutableList())
            callback(ceylonImport)
        }
    }

    private class CeylonModuleAnnotationVisitor(private val callback: (CeylonModule)->Unit): AnnotationVisitor(Opcodes.ASM5) {
        var name: String? = null
        var version: String? = null
        var doc = ""
        var by = emptyList<String>()
        var license = ""
        var dependencies = emptyList<CeylonImport>()
        var nativeBackends = emptyList<String>()

        override fun visit(name: String, value: Any?) {
            if(value is String) {
                when (name) {
                    "name" -> this.name = value
                    "version" -> version = value
                    "doc" -> doc = value
                    "license" -> license = value
                }
            }
        }

        override fun visitArray(name: String): AnnotationVisitor? {
            return when (name) {
                "by" -> StringArrayAnnotationVisitor { by = it }
                "nativeBackends" -> StringArrayAnnotationVisitor { nativeBackends = it }
                "dependencies" -> CeylonImportArrayAnnotationVisitor { dependencies = it }
                else -> null
            }
        }

        override fun visitEnd() {
            val module = CeylonModule(
                    requireNotNull(name) { "A Ceylon @Module annotation dos not define the name property" },
                    requireNotNull(version) { "A Ceylon @Module annotation dos not define the version property" },
                    doc, by.toImmutableList(), license, dependencies.toImmutableList(), nativeBackends.toImmutableList()
            )
            callback(module)
        }
    }

    @Throws(IOException::class)
    override fun scan(file: URL): Map<String, PlateMetadata> {
        val classesToLoad = mutableMapOf<String, PlateMetadata>()
        val ceylonModules = mutableMapOf<String, CeylonModule>()
        file.openStream().use { JarInputStream(it).use { input ->
            input.forEachEntry { entry ->
                if(!entry.isDirectory && entry.name.endsWith(".class", ignoreCase = true)) {
                    if(entry.name.endsWith("\$module_.class", ignoreCase = true))
                        CeylonModuleClassVisitor(ClassReader(input)) {

                        }
                    else
                        ValidPluginClassVisitor(ClassReader(input)) { public, className, metadata ->
                            if (public && metadata != null && className == entry.name.let { it.substring(0, it.length-6) }) {
                                classesToLoad[Type.getType("L$className;").className] = metadata
                            }
                        }
                }
            }
        } }

        // Adds ceylon dependencies as required libraries
        ceylonModules.forEach { prefix, module ->
            classesToLoad.iterator().forEach {
                if(it.key.startsWith(prefix)) {
                    it.setValue(it.value.run {
                        copy(
                            jdk = jdk.takeUnless { it.startsWith('#') } ?: module.jdk,
                            libraries = libraries.addAll(module.mavenArtifacts)
                        )
                    })
                }
            }
        }

        // Remove scala's delegations to scala modules
        classesToLoad.keys.removeIf { classesToLoad["$it$"] == classesToLoad[it] }

        // Removes the temporary # from jdk
        classesToLoad.iterator().forEach { it.setValue(it.value.copy(jdk = it.value.jdk.replace(Regex("^#"), ""))) }

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
        data class RegisteredPlugin(val metadata: PlateMetadata, val url: URL, val className: String)
        val api = PlateNamespace.api.run { RegisteredPlugin(metadata, javaClass.protectionDomain.codeSource.location, javaClass.name) }

        // Scan valid @Plate annotated classes
        val scanResults = files.associate { it to scan(it) } + mapOf(api.url to mapOf(api.className to api.metadata))
        logger.info {
            if(scanResults.isEmpty()) {
                "No plugins were found"
            } else {
                "The scanner found the following plugins:\n---------------\n" +
                scanResults.entries.map { (url, validClasses) ->
                    val fileName = Paths.get(url.toURI()).fileName
                    validClasses.values
                            .map { "Found: ${it.name} ${it.version} -- #${it.id} inside $fileName" }
                            .joinToString("\n")
                }.joinToString("\n---------------\n")
            }
        }

        // Finds duplications and associates the results by the plugin id

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

        logger.debug { "The plugins will load in this order:"+loadingOrder.keys.joinToString("\n -", "\n -") }

        this.loadingOrder = loadingOrder.keys.asSequence().map { it.id }.toImmutableList()

        // Computes the URL dependencies

        /**
         * A map of URLs to all URLs that it depends
         */
        val urlDependencies = scanResults.asSequence().map { (url, validClasses) ->
            url to validClasses.values.asSequence().flatMap{
                dependencyResolution.established[it]?.asSequence()
                        ?.filter { it.namespace == "plate"}
                        ?.map { pluginNames[it.id]!!.url }
                        ?: emptySequence()
            }.toSet()
        }.toMap() + mapOf(pluginNames["platestack"]!!.url to emptySet())

        /**
         * A set of all URLs which does not depends on other URLs
         */
        val independentUrls = urlDependencies.entries.asSequence().filter { (url, dependencies) ->
            dependencies.isEmpty() || dependencies == setOf(url)
        }.map { it.key }.toSet()


        /**
         * URLs which depends on other URLs that also depends on the first URL.
         *
         * Example:
         * * A depends on B and B depends on A
         * * A depends on B, B depends on C, C depends on A
         *
         * The map key in a cyclic URL and the value are all URLs which are part of the cyclic
         */
        val cyclicUrls: Map<URL, Set<URL>> = urlDependencies.mapValues { (url, dependencies) ->
            dependencies.filterTo(mutableSetOf()) {
                it != url && url in urlDependencies[it]!!
            }
        }.filterNot { it.value.isEmpty() }

        /**
         * URLs which depends on other URLs which may depends on other but does not causes a cyclic dependency
         */
        val normalDependencyUrls: Map<URL, Set<URL>> = urlDependencies.filter { it.key !in independentUrls }.mapValues { (url, dependencies) ->
            dependencies.filterTo(mutableSetOf()) {
                it != url && cyclicUrls[it]?.contains(it) != true
            }
        }

        fun URL.openEntryStream(filePath: Path): InputStream? {
            val stream = openStream()
            fun InputStream.tryToClose(suppressTo: Throwable) {
                try {
                    close()
                }
                catch (e: Throwable) {
                    suppressTo.addSuppressed(e)
                }
            }

            try {
                try {
                    val jar = JarInputStream(stream)
                    jar.forEachEntry { entry ->
                        if (Paths.get(entry.name) == filePath) {
                            return jar
                        }
                    }
                }
                catch (jarIO: IOException) {
                    stream.tryToClose(jarIO)

                    val sub = URL(this, filePath.toString())
                    try {
                        return sub.openStream()
                    } catch (e: Throwable) {
                        jarIO.addSuppressed(e)
                        throw jarIO
                    }
                }

                stream.close()
                return null
            } catch (e: Throwable) {
                stream.tryToClose(e)
                throw e
            }
        }

        fun MavenArtifact.toIvy() = org.platestack.libraryloader.ivy.MavenArtifact(group, artifact, version)
        fun org.platestack.libraryloader.ivy.MavenArtifact.toPlugin() = MavenArtifact(group, artifact, version)

        val cachedLibraries = mutableMapOf<URL, Set<MavenArtifact>>()
        fun getRequiredLibraries(url: URL): MutableSet<MavenArtifact> {
            cachedLibraries[url]?.let { return it.toMutableSet() }

            val containedPlugins = scanResults[url]!!.values
            val librariesList: List<MavenArtifact> = url.openEntryStream(Paths.get("libraries.list"))
                    ?.use { input: InputStream -> LibraryResolver.readArtifacts(input).map { it.toPlugin() } }
                    ?: emptyList()

            val requiredLibraries = containedPlugins.flatMapTo(mutableSetOf<MavenArtifact>()) { it.libraries }
            requiredLibraries += librariesList

            logger.info { "Getting transitive dependencies for libraries required by "+Paths.get(url.toURI()).fileName+":"+requiredLibraries.joinToString("\n - ", "\n - ") }
            val dependencies = LibraryResolver.getInstance().dependencies(
                    MavenArtifact(
                            "org.platestack.runtime.resolver.lib.transient",
                            Paths.get(url.toURI()).fileName.toString().replace(Regex("^[a-zA-Z0-9_-]"), "_"),
                            "runtime"
                    ).toIvy(),
                    requiredLibraries.map { it.toIvy() }.toSet()
            ).mapTo(mutableSetOf()) { it.toPlugin() }.onEach {
                logger.info { "Resolution: $it" }
            }

            cachedLibraries[url] = dependencies
            return dependencies
        }

        // Computes the URL libraries
        val independentLibraries: Map<URL, Set<MavenArtifact>> = independentUrls.associate {
            it to getRequiredLibraries(it)
        }

        val normalLibraries: Map<URL, Set<MavenArtifact>> = normalDependencyUrls.entries.associate { (url, dependencies) ->
            url to getRequiredLibraries(url).also {
                val dependencyLibraries = dependencies.flatMap { getRequiredLibraries(it) }
                it.removeIf { lib ->
                    dependencyLibraries.any {
                        it.group == lib.group && it.artifact == lib.artifact
                    }
                }
                //it.addAll(dependencyLibraries)
            }
        }

        val cyclicLibraries: Map<URL, Set<MavenArtifact>> = cyclicUrls.entries.associate { (url, cyclic) ->
            url to getRequiredLibraries(url).also {
                it += cyclic.flatMap { getRequiredLibraries(it) }
                normalDependencyUrls[url]?.apply {
                    it -= flatMap { getRequiredLibraries(it) }
                }
            }
        }

        val libraryUrls = independentLibraries.entries.associateTo(mutableMapOf()) { (url, libs) ->
            logger.info { "Resolving library dependencies for "+Paths.get(url.toURI()).fileName+" (independent) which includes "+(scanResults[url]?.values?: emptyList()).map { it.name }.joinToString() }
            url to LibraryResolver.getInstance().resolve(
                    MavenArtifact("org.platestack.runtime.resolver.lib.independent", scanResults[url]!!.values.map { it.id }.joinToString("_-_"), "runtime").toIvy(),
                    libs.map { it.toIvy() }
            ).onEach { logger.info { "Resolution: $it" } }.map { it.toURI().toURL() }
        }

        val normalLibUrls = normalLibraries.entries.associate { (url, libs) ->
            logger.info { "Resolving library dependencies for "+Paths.get(url.toURI()).fileName+" (dependent) which includes "+(scanResults[url]?.values?: emptyList()).map { it.name }.joinToString() }
            url to LibraryResolver.getInstance().resolve(
                    MavenArtifact("org.platestack.runtime.resolver.lib.normal", scanResults[url]!!.values.map { it.id }.joinToString("_-_"), "runtime").toIvy(),
                    libs.map { it.toIvy() }
            ).onEach { logger.info { "Resolution: $it" } }.map { it.toURI().toURL() }
        }

        libraryUrls += normalLibUrls

        libraryUrls += cyclicLibraries.entries.associate { (url, libs) ->
            logger.info { "Resolving library dependencies for "+Paths.get(url.toURI()).fileName+" (cyclic) which includes "+(scanResults[url]?.values?: emptyList()).map { it.name }.joinToString() }
            url to LibraryResolver.getInstance().resolve(
                    MavenArtifact("org.platestack.runtime.resolver.lib.cyclic", scanResults[url]!!.values.map { it.id }.joinToString("_-_"), "runtime").toIvy(),
                    libs.map { it.toIvy() }
            ).map { it.toURI().toURL() }.let {
                it + cyclicUrls.entries.flatMap { normalLibUrls[it.key] ?: emptyList() }
            }.onEach { logger.info { "Resolution: $it" } }
        }

        // Creates the class loaders

        logger.info { "Creating class loaders..." }

        /**
         * The highest classLoader
         */
        val topClassLoader = javaClass.classLoader

        /**
         * A map of URL to its class loader
         */
        val classLoaders = independentUrls.associateTo(mutableMapOf()) { it to PluginClassLoader(topClassLoader, it) }

        /**
         * Gets or creates and register a class loader for a given URL.
         *
         * * All cyclic URLs will share the same class loader.
         * * URLs with normal dependencies will have a [PluginDependencyClassLoader] instead of the topClassLoader
         */
        fun getClassLoader(url: URL): PluginClassLoader {
            classLoaders[url]?.let { return it }

            val pluginDependencies = (cyclicUrls[url]?:setOf(url)).asSequence()
                    .mapNotNull { normalDependencyUrls[it] }.flatMap { it.asSequence() }
                    .map { getClassLoader(it) }
                    .toSet()

            val parent =
                    if(pluginDependencies.isEmpty())
                        topClassLoader
                    else
                        PluginDependencyClassLoader(topClassLoader, pluginDependencies)

            val classLoader = cyclicUrls[url]
                    ?.let { cyclic -> classLoaders.entries.find { it.key in cyclic }?.value ?: PluginClassLoader(parent, *(cyclic + (libraryUrls[url]?: emptyList())).toTypedArray()) }
                    ?: PluginClassLoader(parent, *(listOf(url) + (libraryUrls[url]?:emptyList())).toTypedArray())

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
        val instances = classes.entries.asSequence()
                .filter { it.key != api.metadata }
                .map { (meta, kClass) -> getOrCreateInstance(meta, kClass) }
                .toList()

        enable(PlateNamespace.api)

        // Enable the plugins
        instances.forEach { enable(it) }

        logger.info { "All plate plugins have been loaded and enabled successfully" }

        return instances
    }

    fun findPlugins(dir: Path, rename: Boolean, filter: (Path)->Boolean): Sequence<Path> {
        return Files.list(dir).asSequence()
                .filter { Files.isRegularFile(it) }
                .filter(filter)
                //.map { it to PlateNamespace.loader.scan(it.toUri().toURL()) }
                //.filter { it.second.isNotEmpty() }
                .run {
                    if(rename) {
                        map { path ->
                            val name = path.fileName.toString()
                            if(!name.endsWith(".plate", ignoreCase = true)) {
                                val target = path.parent.resolve(name.replaceBeforeLast('.', ".plate", "$name.plate"))
                                try {
                                    val moved = Files.move(path, target)
                                    moved
                                }
                                catch(e: Exception) {
                                    System.err.println("Failed to move $path to $target")
                                    e.printStackTrace()
                                    path
                                }
                            }
                            else path
                        }
                    } else this
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
        }
    }

    val testFiles = arrayOf(
            "D:\\_InteliJ\\CleanDishes\\001 Simple Hello World\\Kotlin\\build\\libs\\001 Simple Hello World - Kotlin-0.1.0-SNAPSHOT.jar",
            "D:\\_InteliJ\\CleanDishes\\001 Simple Hello World\\Java\\build\\libs\\001 Simple Hello World - Java-0.1.0-SNAPSHOT.jar",
            "D:\\_InteliJ\\CleanDishes\\001 Simple Hello World\\Scala\\Gradle\\build\\libs\\001 Simple Hello World - Scala - Gradle-0.1.0-SNAPSHOT.jar",
            //"D:\\_InteliJ\\CleanDishes\\001 Simple Hello World\\Scala\\SBT\\target\\scala-2.12\\001-sbt-plateplugin_2.12-0.1.0-SNAPSHOT.jar",
            "D:\\_InteliJ\\CleanDishes\\001 Simple Hello World\\Groovy\\build\\libs\\001 Simple Hello World - Groovy-0.1.0-SNAPSHOT.jar",
            "D:\\_InteliJ\\CleanDishes\\002 MavenPlugin\\Java\\target\\gradle\\libs\\002 MavenPlugin - Java.jar",
            "D:\\_InteliJ\\CleanDishes\\002 MavenPlugin\\Kotlin\\target\\gradle\\libs\\002 MavenPlugin - Kotlin.jar",
            "D:\\_InteliJ\\CleanDishes\\002 MavenPlugin\\Scala\\target\\gradle\\libs\\002 MavenPlugin - Scala.jar"
    )

    val loader = CommonLoader(KotlinLogging.logger("Test Execution"))
    PlateNamespace.loader = loader

    val resourceAsStream: InputStream = PlateServer::class.java.getResourceAsStream("/org/platestack/api/libraries.list")
    loader.setAPI(PlateMetadata(
            "platestack",
            "PlateStack Test",
            Version(0,1,0,"SNAPSHOT"),
            "1.8",
            immutableSetOf(),
            LibraryResolver.readArtifacts(resourceAsStream).map {
                MavenArtifact(it.group, it.artifact, it.version)
            }
    ))

    PlateNamespace.loader.load(testFiles.asSequence().map { File(it).toURI().toURL() }.toSet())
}
