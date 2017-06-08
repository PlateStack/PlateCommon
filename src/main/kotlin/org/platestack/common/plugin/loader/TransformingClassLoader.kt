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

open class TransformingClassLoader(val top: ClassLoader, val transformer: Transformer) : ClassLoader(top.parent) {

    /*
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        val `class` = findLoadedClass(name) ?: try {
            findClass(name)
        } catch (e: Exception) {
            if(e !is ClassNotFoundException && e !is SecurityException)
                throw e

            try {
                top.loadClass(name)
            }
            catch (e2: Throwable) {
                e2.addSuppressed(e)
                throw e2
            }
        }

        if(resolve)
            resolveClass(`class`)

        return `class`
    }
    */

    override fun findClass(name: String): Class<*> {
        val bytes = top.getResourceAsStream(name.replace('.','/')+".class")?.use { transformer(top, name, it) }
                ?: throw ClassNotFoundException(name)
        try {
            return defineClass(name, bytes, 0, bytes.size)
        }
        catch (e: Exception) {
            throw e
        }
    }
}
