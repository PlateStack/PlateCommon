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

import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.given
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.dsl.on
import org.junit.Assert.assertEquals
import org.junit.platform.runner.JUnitPlatform
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.platestack.api.plugin.PlateMetadata
import org.platestack.api.plugin.Relation
import org.platestack.api.plugin.RelationType
import org.platestack.api.plugin.version.Version
import org.platestack.api.plugin.version.VersionRange
import org.platestack.api.server.PlateStack

inline fun <reified T : Any> mock(): T = Mockito.mock(T::class.java)

var mocked = false
fun setup() {
    if(!mocked) {
        PlateStack = mock()
        mocked = true
    }
}

@RunWith(JUnitPlatform::class)
class DependencyResolutionTest: Spek({
    setup()

    given("a, b, c where c depends on b and b depends on a") {
        val a = PlateMetadata("a", "a", Version(1), emptyList())
        val b = PlateMetadata("b", "b", Version(1), listOf(Relation(RelationType.REQUIRED_BEFORE,"a","plate", listOf(VersionRange()))))
        val c = PlateMetadata("c", "c", Version(1), listOf(Relation(RelationType.REQUIRED_BEFORE,"b","plate", listOf(VersionRange()))))
        on("resolve the dependencies") {
            val resolution = DependencyResolution(a, b, c)
            it("should report no conflict") {
                assertEquals(0, resolution.conflicts.size)
            }
            it("should report no required missing") {
                assertEquals(0, resolution.missingRequired.size)
            }
            it("should report no optional missing") {
                assertEquals(0, resolution.missingOptional.size)
            }
            it("should report 2 established metadata") {
                assertEquals(2, resolution.established.size)
            }
            it("should report 1 independent metadata") {
                assertEquals(1, resolution.independents.size)
            }

            it("it should create a list in this order: a, b, c") {
                val order = resolution.createList()
                assertEquals(listOf(a, b, c), order)
            }
        }
    }
})
