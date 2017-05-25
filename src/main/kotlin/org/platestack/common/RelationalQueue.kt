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

package org.platestack.common

import org.platestack.api.plugin.Relation
import org.platestack.api.plugin.RelationType
import java.util.*

interface Relational {
    val id: String
    val namespace: String
    val relations: Set<Relation>
}

private val before = EnumSet.of(RelationType.OPTIONAL_BEFORE, RelationType.REQUIRED_BEFORE)
private val after = EnumSet.of(RelationType.OPTIONAL_AFTER, RelationType.REQUIRED_AFTER)
private val dependency = EnumSet.copyOf(before).apply { addAll(after) }

class RelationalQueue<E: Relational> {
    private val independents = mutableMapOf<E, Node<E>>()

    internal data class Node<E>(val element: E, val before: MutableMap<E, Node<E>> = mutableMapOf(), val after: MutableMap<E, Node<E>> = mutableMapOf())

    fun add(element: E): Boolean {
        val dependencies = element.relations.filter { it.type in dependency }
        if(dependencies.isEmpty()) {
            return independents.putIfAbsent(element, Node(element)) == null
        }
    }
}