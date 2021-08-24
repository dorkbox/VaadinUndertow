/*
 * Copyright 2002-2020 the original author or authors.
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
package dorkbox.vaadin.util

import com.helger.commons.lang.ClassLoaderHelper.getDefaultClassLoader
import java.io.Closeable
import java.io.Externalizable
import java.io.Serializable
import java.lang.reflect.Proxy
import java.util.*


/**
 * Miscellaneous `java.lang.Class` utility methods.
 * Mainly for internal use within the framework.
 *
 * @author Juergen Hoeller
 * @author Keith Donald
 * @author Rob Harrop
 * @author Sam Brannen
 * @since 1.1
 * @see ReflectionUtils
 */
object ClassUtils {
    /** Suffix for array class names: `"[]"`.  */
    const val ARRAY_SUFFIX = "[]"

    /** Prefix for internal array class names: `"["`.  */
    private const val INTERNAL_ARRAY_PREFIX = "["

    /** Prefix for internal non-primitive array class names: `"[L"`.  */
    private const val NON_PRIMITIVE_ARRAY_PREFIX = "[L"

    /** The package separator character: `'.'`.  */
    private const val PACKAGE_SEPARATOR = '.'

    /** The inner class separator character: `'$'`.  */
    private const val INNER_CLASS_SEPARATOR = '$'

    /**
     * Map with primitive wrapper type as key and corresponding primitive
     * type as value, for example: Integer.class -> int.class.
     */
    private val primitiveWrapperTypeMap: MutableMap<Class<*>, Class<*>?> = IdentityHashMap(8)

    /**
     * Map with primitive type as key and corresponding wrapper
     * type as value, for example: int.class -> Integer.class.
     */
    private val primitiveTypeToWrapperMap: MutableMap<Class<*>, Class<*>> = IdentityHashMap(8)

    /**
     * Map with primitive type name as key and corresponding primitive
     * type as value, for example: "int" -> "int.class".
     */
    private val primitiveTypeNameMap: MutableMap<String, Class<*>> = HashMap(32)

    /**
     * Map with common Java language class name as key and corresponding Class as value.
     * Primarily for efficient deserialization of remote invocations.
     */
    private val commonClassCache: MutableMap<String, Class<*>> = HashMap(64)

    /**
     * Common Java language interfaces which are supposed to be ignored
     * when searching for 'primary' user-level interfaces.
     */
    private var javaLanguageInterfaces: Set<Class<*>>? = null


    init {
        primitiveWrapperTypeMap[Boolean::class.java] = Boolean::class.javaPrimitiveType
        primitiveWrapperTypeMap[Byte::class.java] = Byte::class.javaPrimitiveType
        primitiveWrapperTypeMap[Char::class.java] = Char::class.javaPrimitiveType
        primitiveWrapperTypeMap[Double::class.java] = Double::class.javaPrimitiveType
        primitiveWrapperTypeMap[Float::class.java] = Float::class.javaPrimitiveType
        primitiveWrapperTypeMap[Int::class.java] = Int::class.javaPrimitiveType
        primitiveWrapperTypeMap[Long::class.java] = Long::class.javaPrimitiveType
        primitiveWrapperTypeMap[Short::class.java] = Short::class.javaPrimitiveType
        primitiveWrapperTypeMap[Void::class.java] = Void.TYPE

        // Map entry iteration is less expensive to initialize than forEach with lambdas
        for ((key, value) in primitiveWrapperTypeMap) {
            primitiveTypeToWrapperMap[value as Class<*>] = key
            registerCommonClasses(key)
        }


        val primitiveTypes = mutableSetOf<Class<*>>()
        @Suppress("UNCHECKED_CAST")
        primitiveTypes.addAll(primitiveWrapperTypeMap.values as Collection<Class<*>>)

        Collections.addAll(primitiveTypes, BooleanArray::class.java, ByteArray::class.java, CharArray::class.java,
            DoubleArray::class.java, FloatArray::class.java, IntArray::class.java, LongArray::class.java, ShortArray::class.java)
        primitiveTypes.add(Void.TYPE)
        for (primitiveType in primitiveTypes) {
            primitiveTypeNameMap[primitiveType.name] = primitiveType
        }


        registerCommonClasses(Array<Boolean>::class.java, Array<Byte>::class.java, Array<Char>::class.java, Array<Double>::class.java,
            Array<Float>::class.java, Array<Int>::class.java, Array<Long>::class.java, Array<Short>::class.java)
        registerCommonClasses(Number::class.java, Array<Number>::class.java, String::class.java, Array<String>::class.java,
            Class::class.java, emptyArray<Class<*>>().javaClass, Any::class.java, Array<Any>::class.java)

        registerCommonClasses(Throwable::class.java, Exception::class.java, RuntimeException::class.java,
            Error::class.java, StackTraceElement::class.java, Array<StackTraceElement>::class.java)
        registerCommonClasses(Enum::class.java, Iterable::class.java, MutableIterator::class.java, Enumeration::class.java,
            MutableCollection::class.java, MutableList::class.java, MutableSet::class.java, MutableMap::class.java, MutableMap.MutableEntry::class.java, Optional::class.java)

        val javaLanguageInterfaceArray = arrayOf(
            Serializable::class.java, Externalizable::class.java,
            Closeable::class.java, AutoCloseable::class.java, Cloneable::class.java, Comparable::class.java)
        registerCommonClasses(*javaLanguageInterfaceArray)

        javaLanguageInterfaces = HashSet(listOf(*javaLanguageInterfaceArray))
    }











    /**
     * Register the given common classes with the ClassUtils cache.
     */
    private fun registerCommonClasses(vararg commonClasses: Class<*>) {
        for (clazz in commonClasses) {
            commonClassCache[clazz.name] = clazz
        }
    }

    /**
     * Replacement for `Class.forName()` that also returns Class instances
     * for primitives (e.g. "int") and array class names (e.g. "String[]").
     * Furthermore, it is also capable of resolving inner class names in Java source
     * style (e.g. "java.lang.Thread.State" instead of "java.lang.Thread$State").
     * @param name the name of the Class
     * @param classLoader the class loader to use
     * (may be `null`, which indicates the default class loader)
     * @return a class instance for the supplied name
     * @throws ClassNotFoundException if the class was not found
     * @throws LinkageError if the class file could not be loaded
     * @see Class.forName
     */
    @Throws(ClassNotFoundException::class, LinkageError::class)
    fun forName(name: String, classLoader: ClassLoader? = null): Class<*> {
        var clazz = resolvePrimitiveClassName(name)
        if (clazz == null) {
            clazz = commonClassCache[name]
        }
        if (clazz != null) {
            return clazz
        }

        // "java.lang.String[]" style arrays
        if (name.endsWith(ARRAY_SUFFIX)) {
            val elementClassName = name.substring(0, name.length - ARRAY_SUFFIX.length)
            val elementClass = forName(elementClassName, classLoader)
            return java.lang.reflect.Array.newInstance(elementClass, 0).javaClass
        }

        // "[Ljava.lang.String;" style arrays
        if (name.startsWith(NON_PRIMITIVE_ARRAY_PREFIX) && name.endsWith(";")) {
            val elementName = name.substring(NON_PRIMITIVE_ARRAY_PREFIX.length, name.length - 1)
            val elementClass = forName(elementName, classLoader)
            return java.lang.reflect.Array.newInstance(elementClass, 0).javaClass
        }

        // "[[I" or "[[Ljava.lang.String;" style arrays
        if (name.startsWith(INTERNAL_ARRAY_PREFIX)) {
            val elementName = name.substring(INTERNAL_ARRAY_PREFIX.length)
            val elementClass = forName(elementName, classLoader)
            return java.lang.reflect.Array.newInstance(elementClass, 0).javaClass
        }

        var clToUse = classLoader
        if (clToUse == null) {
            clToUse = getDefaultClassLoader()
        }

        return try {
            Class.forName(name, false, clToUse)
        } catch (ex: ClassNotFoundException) {
            val lastDotIndex = name.lastIndexOf(PACKAGE_SEPARATOR)
            if (lastDotIndex != -1) {
                val innerClassName = name.substring(0, lastDotIndex) + INNER_CLASS_SEPARATOR + name.substring(lastDotIndex + 1)
                try {
                    return Class.forName(innerClassName, false, clToUse)
                } catch (ex2: ClassNotFoundException) { // Swallow - let original exception get through
                }
            }
            throw ex
        }
    }

    /**
     * Resolve the given class name as primitive class, if appropriate,
     * according to the JVM's naming rules for primitive classes.
     *
     * Also supports the JVM's internal class names for primitive arrays.
     * Does *not* support the "[]" suffix notation for primitive arrays;
     * this is only supported by [.forName].
     * @param name the name of the potentially primitive class
     * @return the primitive class, or `null` if the name does not denote
     * a primitive class or primitive array class
     */
    fun resolvePrimitiveClassName(name: String?): Class<*>? {
        var result: Class<*>? = null
        // Most class names will be quite long, considering that they
        // SHOULD sit in a package, so a length check is worthwhile.
        if (name != null && name.length <= 7) { // Could be a primitive - likely.
            result = primitiveTypeNameMap[name]
        }
        return result
    }


    /**
     * Create a composite interface Class for the given interfaces,
     * implementing the given interfaces in one single Class.
     *
     * This implementation builds a JDK proxy class for the given interfaces.
     * @param interfaces the interfaces to merge
     * @param classLoader the ClassLoader to create the composite Class in
     * @return the merged interface as Class
     * @throws IllegalArgumentException if the specified interfaces expose
     * conflicting method signatures (or a similar constraint is violated)
     * @see java.lang.reflect.Proxy.getProxyClass
     */
    // on JDK 9
    fun createCompositeInterface(interfaces: Array<Class<*>?>, classLoader: ClassLoader?): Class<*> {
        return Proxy.getProxyClass(classLoader, *interfaces)
    }
}
