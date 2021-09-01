package dorkbox.vaadin.devMode

import com.vaadin.flow.server.frontend.scanner.ClassFinder
import java.util.*
import javax.servlet.annotation.HandlesTypes

/**
 * THIS IS COPIED DIRECTLY FROM VAADIN 14.6.8 (flow 2.4.6)
 *
 * CHANGES FROM DEFAULT ARE MANAGED AS DIFFERENT REVISIONS.
 *
 * The initial commit is exactly as-is from vaadin.
 *
 * This file is NOT extensible/configurable AT-ALL, so this is required...
 */
internal class DevModeClassFinder(classes: Set<Class<*>?>?) : ClassFinder.DefaultClassFinder(classes) {
        companion object {
            private val APPLICABLE_CLASS_NAMES = Collections.unmodifiableSet(calculateApplicableClassNames())

            private fun calculateApplicableClassNames(): Set<String> {
                val handlesTypes: HandlesTypes = DevModeInitializer::class.java.getAnnotation(HandlesTypes::class.java)
                return handlesTypes.value.map { it.qualifiedName!! }.toSet()
            }
        }

        override fun getAnnotatedClasses(annotation: Class<out Annotation?>): Set<Class<*>> {
            ensureImplementation(annotation)
            return super.getAnnotatedClasses(annotation)
        }

        override fun <T> getSubTypesOf(type: Class<T>): Set<Class<out T>> {
            ensureImplementation(type)
            return super.getSubTypesOf(type)
        }

        private fun ensureImplementation(clazz: Class<*>) {
            require(APPLICABLE_CLASS_NAMES.contains(clazz.name)) {
                ("Unexpected class name "
                        + clazz + ". Implementation error: the class finder "
                        + "instance is not aware of this class. "
                        + "Fix @HandlesTypes annotation value for "
                        + DevModeInitializer::class.java.name)
            }
        }
    }
