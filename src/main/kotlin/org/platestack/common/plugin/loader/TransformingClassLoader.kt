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

open class TransformingClassLoader(source: ClassLoader, val transformer: Transformer) : ClassLoader(source) {

    init {
        checkNotNull(parent.parent) { "The source classloader must have a parent!" }
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            // First, check if the class has already been loaded
            var c = findLoadedClass(name)
            if (c == null) {
                val t0 = System.nanoTime()
                c = try {
                    parent.parent.loadClass(name)
                } catch (e: ClassNotFoundException) {
                    // ClassNotFoundException thrown if class not found
                    // from the parent class loader
                    null
                }

                if (c == null) {
                    // If still not found, then invoke findClass in order
                    // to find the class.
                    val t1 = System.nanoTime()
                    c = findClass(name)

                    // this is the defining class loader; record the stats
                    // TODO Should we really call it?
                    sun.misc.PerfCounter.getParentDelegationTime().addTime(t1 - t0)
                    sun.misc.PerfCounter.getFindClassTime().addElapsedTimeFrom(t1)
                    sun.misc.PerfCounter.getFindClasses().increment()
                }
            }
            if (resolve) {
                resolveClass(c)
            }
            return c
        }
    }

    override fun findClass(name: String): Class<*> {
        val bytes = parent.getResourceAsStream(name.replace('.','/')+".class")?.use { transformer(this, name, it) }
                ?: throw ClassNotFoundException(name)
        try {
            return defineClass(name, bytes, 0, bytes.size)
        }
        catch (e: Exception) {
            throw e
        }
    }
}
