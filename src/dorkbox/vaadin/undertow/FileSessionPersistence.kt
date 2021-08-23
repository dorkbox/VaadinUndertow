/*
 * Copyright 2012-2019 the original author or authors.
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

import io.undertow.servlet.UndertowServletLogger
import io.undertow.servlet.api.SessionPersistenceManager
import io.undertow.servlet.api.SessionPersistenceManager.PersistentSession
import java.io.*

/**
 * [SessionPersistenceManager] that stores session information in a file.
 *
 * @author Phillip Webb
 * @author Peter Leibiger
 * @author Raja Kolli
 */
class FileSessionPersistence(private val dir: File) : SessionPersistenceManager {
    override fun persistSessions(deploymentName: String, sessionData: Map<String, PersistentSession>) {
        try {
            save(sessionData, getSessionFile(deploymentName))
        } catch (ex: Exception) {
            UndertowServletLogger.ROOT_LOGGER.failedToPersistSessions(ex)
        }
    }

    @Throws(IOException::class)
    private fun save(sessionData: Map<String, PersistentSession>, file: File) {
        ObjectOutputStream(FileOutputStream(file)).use { stream -> save(sessionData, stream) }
    }

    @Throws(IOException::class)
    private fun save(sessionData: Map<String, PersistentSession>, stream: ObjectOutputStream) {
        val session: MutableMap<String, Serializable> = LinkedHashMap()
        sessionData.forEach { (key: String, value: PersistentSession) -> session[key] = SerializablePersistentSession(value) }
        stream.writeObject(session)
    }

    override fun loadSessionAttributes(deploymentName: String, classLoader: ClassLoader): Map<String, PersistentSession>? {
        try {
            val file = getSessionFile(deploymentName)
            if (file.exists()) {
                return load(file, classLoader)
            }
        } catch (ex: Exception) {
            UndertowServletLogger.ROOT_LOGGER.failedtoLoadPersistentSessions(ex)
        }

        return null
    }

    @Throws(IOException::class, ClassNotFoundException::class)
    private fun load(file: File, classLoader: ClassLoader): Map<String, PersistentSession> {
        ConfigurableObjectInputStream(FileInputStream(file), classLoader).use { stream -> return load(stream) }
    }

    @Throws(ClassNotFoundException::class, IOException::class)
    private fun load(stream: ObjectInputStream): Map<String, PersistentSession> {
        val session = readSession(stream)
        val time = System.currentTimeMillis()
        val result: MutableMap<String, PersistentSession> = LinkedHashMap()
        session.forEach { (key: String, value: SerializablePersistentSession) ->
            val entrySession = value.persistentSession
            if (entrySession.expiration.time > time) {
                result[key] = entrySession
            }
        }
        return result
    }

    @Throws(ClassNotFoundException::class, IOException::class)
    private fun readSession(stream: ObjectInputStream): Map<String, SerializablePersistentSession> {
        @Suppress("UNCHECKED_CAST")
        return stream.readObject() as Map<String, SerializablePersistentSession>
    }

    private fun getSessionFile(deploymentName: String): File {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "$deploymentName.session")
    }

    override fun clear(deploymentName: String) {
        getSessionFile(deploymentName).delete()
    }

    /**
     * Session data in a serializable form.
     */
    internal class SerializablePersistentSession(session: PersistentSession) : Serializable {
        private val expiration = session.expiration
        private val sessionData = LinkedHashMap(session.sessionData)

        val persistentSession: PersistentSession
            get() = PersistentSession(expiration, sessionData)

        companion object {
            private const val serialVersionUID = 0L
        }
    }
}
