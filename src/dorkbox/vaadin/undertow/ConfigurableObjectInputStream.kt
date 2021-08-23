/*
 * Copyright 2002-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.vaadin.undertow

import dorkbox.vaadin.util.ClassUtils.createCompositeInterface
import dorkbox.vaadin.util.ClassUtils.forName
import java.io.*

/**
 * Special ObjectInputStream subclass that resolves class names
 * against a specific ClassLoader.
 *
 * @author Juergen Hoeller
 * @since 2.5.5
 */
class ConfigurableObjectInputStream

/**
 * Create a new ConfigurableObjectInputStream for the given InputStream and ClassLoader.
 *
 * @param in the InputStream to read from
 * @param classLoader the ClassLoader to use for loading local classes
 * @param acceptProxyClasses whether to accept deserialization of proxy classes (may be deactivated as a security measure)
 *
 * @see java.io.ObjectInputStream.ObjectInputStream
 */
@JvmOverloads
constructor(`in`: InputStream?, private val classLoader: ClassLoader?, private val acceptProxyClasses: Boolean = true) : ObjectInputStream(`in`) {

    @Throws(IOException::class, ClassNotFoundException::class)
    override fun resolveClass(classDesc: ObjectStreamClass): Class<*> {
        return try {
            if (classLoader != null) {
                // Use the specified ClassLoader to resolve local classes.
                forName(classDesc.name, classLoader)
            } else {
                // Use the default ClassLoader...
                super.resolveClass(classDesc)
            }
        } catch (ex: ClassNotFoundException) {
            throw ex
        }
    }

    @Throws(IOException::class, ClassNotFoundException::class)
    override fun resolveProxyClass(interfaces: Array<String>): Class<*> {
        if (!acceptProxyClasses) {
            throw NotSerializableException("Not allowed to accept serialized proxy classes")
        }
        return if (classLoader != null) {
            // Use the specified ClassLoader to resolve local proxy classes.
            val resolvedInterfaces = arrayOfNulls<Class<*>?>(interfaces.size)
            for (i in interfaces.indices) {
                try {
                    resolvedInterfaces[i] = forName(interfaces[i], classLoader)
                } catch (ex: ClassNotFoundException) {
                    throw ex
                }
            }

            try {
                createCompositeInterface(resolvedInterfaces, classLoader)
            } catch (ex: IllegalArgumentException) {
                throw ClassNotFoundException(null, ex)
            }
        } else {
            // Use ObjectInputStream's default ClassLoader...
            try {
                super.resolveProxyClass(interfaces)
            } catch (ex: ClassNotFoundException) {
                throw ex
            }
        }
    }
}
