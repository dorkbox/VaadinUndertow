/*
 * Copyright 2024 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dorkbox.vaadin.undertow

import dorkbox.vaadin.util.logger
import io.undertow.UndertowMessages
import io.undertow.server.handlers.resource.Resource
import io.undertow.server.handlers.resource.ResourceChangeListener
import io.undertow.server.handlers.resource.ResourceManager

class ResourceCollectionManager(private val resources: List<ResourceManager>) : ResourceManager {
    private val logger = logger()

    private val changeListenerSupported : Boolean by lazy {
        var supported = true
        resources.forEach { resourceManager ->
            if (!resourceManager.isResourceChangeListenerSupported) {
                supported = false
                return@forEach
            }
        }
        supported
    }

    override fun getResource(path: String?): Resource? {
        resources.forEach { it ->
            val resource = it.getResource(path)
            if (resource != null) {
                return resource
            }
        }

        return null
    }

    override fun isResourceChangeListenerSupported(): Boolean {
        return changeListenerSupported
    }

    override fun removeResourceChangeListener(listener: ResourceChangeListener?) {
        if (changeListenerSupported) {
            resources.forEach { resourceManager ->
                resourceManager.removeResourceChangeListener(listener)
            }
        } else {
            throw UndertowMessages.MESSAGES.resourceChangeListenerNotSupported()
        }
    }


    override fun registerResourceChangeListener(listener: ResourceChangeListener?) {
        if (changeListenerSupported) {
            resources.forEach { resourceManager ->
                resourceManager.registerResourceChangeListener(listener)
            }
        }  else {
            throw UndertowMessages.MESSAGES.resourceChangeListenerNotSupported()
        }
    }

    override fun close() {
        resources.forEach { it ->
            try {
                it.close()
            } catch (e: Exception) {
                logger.error("Error closing resourceManager", e)
            }
        }
    }
}
