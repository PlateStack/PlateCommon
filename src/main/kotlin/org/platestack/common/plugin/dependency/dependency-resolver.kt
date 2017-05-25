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

package org.platestack.common.plugin.dependency

import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import org.platestack.api.plugin.*
import org.platestack.api.plugin.version.Version
import org.platestack.api.server.PlateStack
import java.util.*
import java.util.stream.Collectors
import kotlin.Comparator

private data class Node(val meta: PlateMetadata, val relative: ID, val relation: Relation, var match: Version? = null)
private data class ID(val namespace: String, val plugin: String): Comparable<ID> {
    constructor(meta: PlateMetadata): this("plate", meta.id)
    constructor(relation: Relation): this(relation.namespace, relation.id)
    override fun toString() = "$namespace:$plugin"
    override fun compareTo(other: ID): Int {
        val diff = namespace.compareTo(other.namespace)
        if(diff != 0)
            return diff
        else
            return plugin.compareTo(other.plugin)
    }
}

private fun resolveOrder(elements: Collection<PlateMetadata>): List<PlateMetadata> {
    class Entry(val meta: PlateMetadata) {
        val id = ID(meta)
        val needBefore = meta.relations.filter { it.type == RelationType.REQUIRED_BEFORE || it.type == RelationType.OPTIONAL_BEFORE }
        val needAfter = meta.relations.filter { it.type == RelationType.REQUIRED_AFTER || it.type == RelationType.OPTIONAL_AFTER }
        val requiredBefore = mutableMapOf<ID, Entry>()
        val requiredAfter = mutableMapOf<ID, Entry>()
    }

    val entries = elements.asSequence().map(::Entry).associate { it.id to it }

    entries.values.forEach { entry ->
        entries.values.forEach { other ->
            if(entry.needBefore.any { other.meta in it })
                other.requiredBefore[entry.id] = entry
            else if(entry.needAfter.any { other.meta in it })
                other.requiredAfter[entry.id] = entry
        }
    }

    fun addTransients(entry: Entry, name: String, getRequired: (Entry.() -> MutableMap<ID, Entry>), getOpposite: (Entry.() -> MutableMap<ID, Entry>)) {
        val stack = ArrayDeque<Pair<List<Entry>, Iterator<Entry>>>()
        stack.push(listOf(entry) to getRequired(entry).values.toList().iterator())
        while (stack.isNotEmpty()) {
            val (current, iter) = stack.pop()
            iter.forEach { transient ->
                val path = current + transient
                if(transient == entry)
                    error("Impossible resolution: A circular $name dependency was detected at: $path; All elements: $elements")

                if(transient.id in getOpposite(entry))
                    error("Impossible resolution: ${transient.id} is required both BEFORE and AFTER at: $path; All elements: $elements")

                getRequired(entry)[transient.id] = transient
                stack.push(path to getRequired(transient).values.iterator())
            }
        }
    }

    entries.values.forEach { entry ->
        addTransients(entry, "BEFORE", Entry::requiredBefore, Entry::requiredAfter)
        addTransients(entry, "AFTER", Entry::requiredAfter, Entry::requiredBefore)
    }

    val sorted = entries.values.sortedWith(Comparator<Entry> { a, b ->
        if(b.id in a.requiredBefore || a.id in b.requiredAfter)
            -1
        else if(b.id in a.requiredAfter || a.id in b.requiredBefore)
            1
        else
            a.id.compareTo(b.id)
    })

    return sorted.map(Entry::meta)
}

private data class KeyPoint<E>(val vertex: Vertex<E>)

private data class Vertex<E>(val value: E, val link: KeyPoint<E>? = null) {
    val before = KeyPoint(this)
    val after = KeyPoint(this)
}

class MetaCollection(vararg elements: PlateMetadata): AbstractMutableCollection<PlateMetadata>() {
    private val lists = mutableListOf(mutableListOf<PlateMetadata>())
    init {
        elements.firstOrNull()?.let { add(it) }
    }

    override fun add(element: PlateMetadata) = addAll(setOf(element))

    override fun addAll(elements: Collection<PlateMetadata>): Boolean {
        if(elements.isEmpty())
            return false

        val pending = elements.toList()

        val byId = pending.associateTo(mutableMapOf()) { ID(it) to it }
        var last: Map<ID, PlateMetadata>? = null
        while (byId.isNotEmpty()) {
            if(byId == last)
                error("Unable to resolve the dependency order of: ${byId.keys}\nPending metadata: ${byId.values}")

            val iter = byId.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if(entry.value.relations.none { it.namespace == "plate" && ID(it) in byId }) {
                    if(entry.value.relations.none { rel-> rel.namespace == "plate" && lists.any { it.any { meta-> meta.name == rel.id } } }) {
                        lists += mutableListOf(entry.value)
                        iter.remove()
                        continue
                    }
                }
            }
        }

        TODO()
    }

    override fun remove(element: PlateMetadata): Boolean {
        TODO()
    }

    override val size get() = lists.sumBy { it.size }
    override fun iterator() = object : MutableIterator<PlateMetadata> {
        private val reference = lists.asSequence().flatMap { it.asSequence() }.iterator()
        private var last: PlateMetadata? = null

        override fun hasNext() = reference.hasNext()
        override fun next(): PlateMetadata {
            val next = reference.next()
            last = next
            return next
        }

        override fun remove() {
            remove(requireNotNull(last))
        }
    }
}

class DependencyResolution(vararg plugin: PlateMetadata) {
    val missingRequired: ImmutableMap<PlateMetadata, ImmutableSet<Relation>>
    val missingOptional: ImmutableMap<PlateMetadata, ImmutableSet<Relation>>
    val conflicts: ImmutableMap<PlateMetadata, ImmutableSet<Relation>>
    val established: ImmutableMap<PlateMetadata, ImmutableSet<Relation>>

    fun order() {
        val associate = established.entries.associate { it.key to it.value.map { rel-> Vertex(it.key to rel) } }
        TODO()
    }

    fun createList() = resolveOrder(PlateNamespace.loadedPlugins.map(PlatePlugin::metadata) + established.keys)

    init {
        val nodes = plugin.flatMap { meta -> meta.relations.map { Node(meta, ID(it), it) } }

        val plugins: Map<ID, Node> = nodes.stream().collect(Collectors.toMap({ ID(it.meta) }, { it }, { a, b -> error("Duplicated nodes: $a, $b") }))
        plugins.keys.find { PlateStack.getPlugin(it.plugin, it.namespace) != null }?.let { error("The plugin $it is already loaded!") }

        nodes.forEach { node ->
            node.match = node.relative.let { plugins[it]?.meta?.version ?: PlateStack.getPlugin(it.plugin, it.namespace)?.version }
                    ?.takeIf { node.relation.versions.any { range -> it in range } }
        }

        missingRequired = nodes.filterImmutable { it.match == null && it.relation.type in required }
        missingOptional = nodes.filterImmutable { it.match == null && it.relation.type in optional }
        conflicts = nodes.filterImmutable { it.match != null && it.relation.type in conflict }
        established = nodes.filterImmutable { it.match != null && it.relation.type !in conflict }
    }

    companion object {
        @JvmStatic private val required = EnumSet.of(RelationType.REQUIRED_BEFORE, RelationType.REQUIRED_AFTER)
        @JvmStatic private val optional = EnumSet.of(RelationType.OPTIONAL_BEFORE, RelationType.OPTIONAL_AFTER)
        @JvmStatic private val conflict = EnumSet.of(RelationType.INCOMPATIBLE, RelationType.INCLUDED)
    }

    private fun List<Node>.filterImmutable(predicate: (Node)-> Boolean) =
            filter(predicate).toMap().mapValues { it.value.toImmutableSet() }.toImmutableMap()

    private fun List<Node>.toMap(): Map<PlateMetadata, Collection<Relation>> =
            stream().collect(Collectors.toMap({it.meta}, { setOf(it.relation) }, { a, b-> a + b }))
}
