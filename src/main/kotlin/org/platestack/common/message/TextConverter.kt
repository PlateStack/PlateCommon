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

package org.platestack.common.message

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.platestack.api.json.WrappedJsonElement
import org.platestack.api.json.addProperty
import org.platestack.api.json.set
import org.platestack.api.json.toJson
import org.platestack.api.message.ClickEvent
import org.platestack.api.message.HoverEvent
import org.platestack.api.message.HoverEvent.*
import org.platestack.api.message.Text
import org.platestack.api.message.Text.*

interface TextConverter {
    fun Text.toJson(json: JsonObject = JsonObject()): JsonObject = json.also { _ ->
        style.color?.let { json["color"] = it.id }
        style.formatMap.forEach { format, value -> json[format.id] = value }
        insertion?.let { json["insertion"] = it }

        when(this) {
            is Compound -> toJson(json)
            is RawText -> toJson(json)
            is Translation -> toJson(json)
            is KeyBinding -> toJson(json)
            is Score -> toJson(json)
            is Selector -> toJson(json)
            is FormattedMessage -> toJson(json)
            else -> TODO("Unsupported type ${this.javaClass.name}")
        }

        clickEvent?.let { json["clickEvent"] = it.toJson() }
        hoverEvent?.let { json["hoverEvent"] = it.toJson() }

        if(extra.isNotEmpty()) {
            val array = JsonArray()
            json["extra"] = array
            extra.forEach {
                array.add(it.toJson(JsonObject()))
            }
        }
    }

    fun Compound.toJson(json: JsonObject) = Unit

    fun RawText.toJson(json: JsonObject) {
        json["text"] = text
    }

    fun FormattedMessage.toJson(json: JsonObject) {
        json["text"] = message.toJson()
    }

    fun KeyBinding.toJson(json: JsonObject) {
        json["keybind"] = key
    }

    fun Translation.toJson(json: JsonObject) {
        json["translate"] = key
        if(with.isNotEmpty()) {
            json["with"] = JsonArray().apply {
                with.forEach {
                    add(it.toJson(JsonObject()))
                }
            }
        }
    }

    fun Score.toJson(json: JsonObject) {
        json["score"] = JsonObject().apply {
            addProperty("name", name)
            addProperty("objective", objective)
            value?.let { addProperty("value", it) }
        }
    }

    fun Selector.toJson(json: JsonObject) {
        json["selector"] = selector.toJson()
    }

    fun HoverEvent.toJson(json: JsonObject = JsonObject()): JsonObject = json.also { _ ->
        json["action"] = action
        when(this) {
            is ShowText -> toJson(json)
            is ShowItem -> toJson(json)
            is ShowAchievement -> toJson(json)
            is ShowEntity -> toJson(json)
        }
    }

    fun ShowText.toJson(json: JsonObject) {
        json["value"] = text.toJson()
    }

    fun ShowItem.toJson(json: JsonObject) {
        json["value"] = WrappedJsonElement(serializedItem)
    }

    fun ShowAchievement.toJson(json: JsonObject) {
        json["value"] = id
    }

    fun ShowEntity.toJson(json: JsonObject) {
        json["value"] = WrappedJsonElement(serializedEntity)
    }

    fun ClickEvent.toJson(json: JsonObject = JsonObject()) = json.also { _ ->
        json["action"] = action
        json["value"] = value.toJson()
    }
}
