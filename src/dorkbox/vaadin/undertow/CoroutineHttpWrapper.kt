/*
 * Copyright 2020 Dorkbox LLC
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
import io.undertow.server.Connectors
import io.undertow.server.HandlerWrapper
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.server.session.Session
import io.undertow.server.session.SessionListener
import io.undertow.util.AttachmentKey
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.sendBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


val ACTOR = AttachmentKey.create<SendChannel<HttpServerExchange>>(SendChannel::class.java)!!

/**
 * This runs just BEFORE the main servlet handler, and lets us run the servlet logic on an actor. Each servlet has it's own actor,
 * and can queue 8 "servlet" requests at a time
 */
class CoroutineHttpWrapper(private val sessionCookieName: String, private val capacity: Int, concurrencyFactor: Int) : HandlerWrapper {

    private val logger = logger()
    var actorsPerSession = ConcurrentHashMap<String, SendChannel<HttpServerExchange>>(8, 0.9f, concurrencyFactor)

    private lateinit var defaultActor: SendChannel<HttpServerExchange>

    // Our UI Needs to run on a different thread pool so that if the UI is blocked, it doesn't block our cache.
    private val uiThreadPool = Executors.newCachedThreadPool(DaemonThreadFactory("HttpWrapper")) as ExecutorService
    private val uiDispatcher = uiThreadPool.asCoroutineDispatcher()

    private val handler = CoroutineExceptionHandler { _, exception ->
        logger.error { "Uncaught Coroutine Error: $exception" }
    }


    private val scope = CoroutineScope(uiDispatcher + handler)
    val job = Job()
//    val mutex = Mutex()

    @kotlinx.coroutines.ObsoleteCoroutinesApi
    private fun createActor(handler: HttpHandler) = scope.actor<HttpServerExchange>(context = job, capacity = capacity) {
        logger.info { "Starting actor $this" }

        for (msg in channel) {
            Connectors.executeRootHandler(handler, msg)
        }

        logger.info("stopping actor $this")
    }


    override fun wrap(handler: HttpHandler): HttpHandler {
        @kotlinx.coroutines.ObsoleteCoroutinesApi
        defaultActor = createActor(handler)

        return HttpHandler { exchange ->
            exchange.startBlocking()
            // IO is on a single thread

            // how to check if we are running in the actor?

            if (exchange.isInIoThread) {
                // check if we've already created the actor for this exchange
                var actor: SendChannel<HttpServerExchange> = exchange.getAttachment(ACTOR) ?: defaultActor

                if (actor == defaultActor) {
                    actor = getOrCreateActor(handler, exchange)
                    exchange.putAttachment(ACTOR, actor)
                }

                // suppressed because we DO NOT use threading, we use coroutines, which are semantically different
                @Suppress("DEPRECATION")
                exchange.dispatch() // this marks the exchange as having been DISPATCHED
                actor.sendBlocking(exchange)
            }
            else {
                // if we are not in the IO thread, just process as normal
                handler.handleRequest(exchange)
            }
        }
    }

    private fun getOrCreateActor(handler: HttpHandler, exchange: HttpServerExchange): SendChannel<HttpServerExchange> {
        // we key off of the session ID for this. If this is changed, then we have to use whatever is different

        @Suppress("MoveVariableDeclarationIntoWhen")
        val sessionCookie = exchange.getRequestCookie(sessionCookieName)
        return when (sessionCookie) {
            null -> defaultActor
            else -> {
                // we have a session ID, so use that to create/use an actor
                var maybeActor = actorsPerSession[sessionCookie.value]
                if (maybeActor == null) {
                    @kotlinx.coroutines.ObsoleteCoroutinesApi
                    maybeActor = createActor(handler)


                    // pass coroutine info through java?
                    // https://stackoverflow.com/questions/51808992/kotlin-suspend-fun/51811597#51811597

                    // NOTE: For vaadin, we also have to create the lock via QUASAR !!!! since vaadin uses explicit locking (on a lock object) VaadinService.lockSession
                    //  /*
                    //             * No lock found in the session attribute. Ensure only one lock is
                    //             * created and used by everybody by doing double checked locking.
                    //             * Assumes there is a memory barrier for the attribute (i.e. that
                    //             * the CPU flushes its caches and reads the value directly from main
                    //             * memory).
                    //             */
                    //            synchronized (VaadinService.class) {
                    //                lock = getSessionLock(wrappedSession);
                    //                if (lock == null) {
                    //                    lock = new ReentrantLock();
                    //                    setSessionLock(wrappedSession, lock);
                    //                }
                    //            }

                    actorsPerSession[sessionCookie.value] = maybeActor
                    exchange.putAttachment(ACTOR, maybeActor)
                }

                maybeActor
            }
        }
    }

    suspend fun stop() = coroutineScope {
        job.cancelAndJoin()
        actorsPerSession.clear()
    }
}


/**
 * Destroys the actor when the session is destroyed
 */
class ActorSessionCleanup(private val map: MutableMap<String, SendChannel<HttpServerExchange>>) : SessionListener {
    override fun sessionDestroyed(session: Session,
                                  exchange: HttpServerExchange?,
                                  reason: SessionListener.SessionDestroyedReason) {

        // destroy the Actor and remove the session object
        map.remove(session.id)?.close()
    }
}
