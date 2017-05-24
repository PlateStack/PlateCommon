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

import kotlinx.collections.immutable.toImmutableMap
import org.platestack.api.plugin.PlateMetadata
import org.platestack.api.plugin.Relation
import org.platestack.api.plugin.RelationType
import org.platestack.api.plugin.version.Version
import org.platestack.api.server.PlateStack
import java.util.*
import java.util.stream.Collectors

private data class Node(val meta: PlateMetadata, val relative: ID, val relation: Relation, var match: Version? = null)
private data class ID(val namespace: String, val plugin: String) {
    constructor(meta: PlateMetadata): this("plate", meta.id)
    constructor(relation: Relation): this(relation.namespace, relation.id)
    override fun toString() = "$namespace:$plugin"
}

class DependencyResolution(vararg plugin: PlateMetadata) {
    val missingRequired: Map<PlateMetadata, Collection<Relation>>
    val missingOptional: Map<PlateMetadata, Collection<Relation>>
    val conflicts: Map<PlateMetadata, Collection<Relation>>

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
    }

    companion object {
        @JvmStatic private val required = EnumSet.of(RelationType.REQUIRED_BEFORE, RelationType.REQUIRED_AFTER)
        @JvmStatic private val optional = EnumSet.of(RelationType.OPTIONAL_BEFORE, RelationType.OPTIONAL_AFTER)
        @JvmStatic private val conflict = EnumSet.of(RelationType.INCOMPATIBLE, RelationType.INCLUDED)
    }

    private fun List<Node>.filterImmutable(predicate: (Node)-> Boolean) = filter(predicate).toMap().toImmutableMap()

    private fun List<Node>.toMap(): Map<PlateMetadata, Collection<Relation>> =
            stream().collect(Collectors.toMap({it.meta}, { setOf(it.relation) }, { a, b-> a + b }))
}
