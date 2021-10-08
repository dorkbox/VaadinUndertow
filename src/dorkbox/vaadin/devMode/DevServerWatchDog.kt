/*
 * Copyright 2000-2020 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package dorkbox.vaadin.devMode

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * THIS IS COPIED DIRECTLY FROM VAADIN 14.7.1 (flow 2.7.1)
 *
 * CHANGES FROM DEFAULT ARE MANAGED AS DIFFERENT REVISIONS.
 *
 * The initial commit is exactly as-is from vaadin.
 *
 * This file is NOT extensible/configurable AT-ALL, so this is required...
 * Opens a server socket which is supposed to be opened until dev mode is active
 * inside JVM.
 *
 *
 * If this socket is closed then there is no anymore Java "client" for the
 * webpack dev server and it should be stopped.
 *
 *
 * For internal use only. May be renamed or removed in a future release.
 *
 * @author Vaadin Ltd
 * @since 2.0
 */
internal class DevServerWatchDog {
    private class WatchDogServer internal constructor() : Runnable {
        internal var server: ServerSocket? = null

        override fun run() {
            while (!server!!.isClosed) {
                try {
                    val accept = server!!.accept()
                    accept.soTimeout = 0
                    enterReloadMessageReadLoop(accept)
                } catch (e: IOException) {
                    logger.debug(
                        "Error occurred during accept a connection", e
                    )
                }
            }
        }

        fun stop() {
            val server = server
            if (server != null) {
                try {
                    server.close()
                } catch (e: IOException) {
                    logger.debug(
                        "Error occurred during close the server socket", e
                    )
                }
            }
        }

        private val logger: Logger
            private get() = LoggerFactory.getLogger(WatchDogServer::class.java)

        @Throws(IOException::class)
        private fun enterReloadMessageReadLoop(accept: Socket) {
            val `in` = BufferedReader(
                InputStreamReader(
                    accept.getInputStream(), StandardCharsets.UTF_8
                )
            )

            var line: String
            while (`in`.readLine().also { line = it } != null) {
                val devModeHandler: DevModeHandler? = DevModeHandler.devModeHandler
                if ("reload" == line) {
                    devModeHandler?.liveReload?.reload()
                }
            }
        }

        init {
            try {
                val server = ServerSocket(0)
                this.server = server

                server.soTimeout = 0
                if (logger.isDebugEnabled) {
                    logger.debug(
                        "Watchdog server has started on port {}",
                        server.localPort
                    )
                }
            } catch (e: IOException) {
                throw RuntimeException("Could not open a server socket", e)
            }
        }
    }

    private val watchDogServer: WatchDogServer
    val watchDogPort: Int
        get() = watchDogServer.server!!.localPort

    fun stop() {
        watchDogServer.stop()
    }

    init {
        watchDogServer = WatchDogServer()
        val serverThread = Thread(watchDogServer)
        serverThread.isDaemon = true
        serverThread.start()
    }
}
