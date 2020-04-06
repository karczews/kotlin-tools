/*
 * Copyright (c) 2020 - 2020 TomTom N.V. All rights reserved.
 *
 * This software is the proprietary copyright of TomTom N.V. and its subsidiaries and may be
 * used for internal evaluation purposes or commercial use strictly subject to separate
 * licensee agreement between you and TomTom. If you are the licensee, you are only permitted
 * to use this Software in accordance with the terms of your license agreement. If you are
 * not the licensee then you are not authorised to use this software in any manner and should
 * immediately return it to TomTom N.V.
 */
package com.tomtom.kotlin.traceevents

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

/**
 * GENERAL DOCUMENTATION ON TRACE EVENTS
 * -------------------------------------
 *
 * See [/docs/logging-and-tracing.md]
 *
 * TRACE EVENTS CONSUMERS
 * ----------------------
 *
 * There are 2 types of trace events consumers:
 *
 * 1. Generic trace event consumers, derived both from [GenericTraceEventConsumer] and from a
 *  [TraceEventListener] interface.
 *
 * These consumers receive every event thrown in the system. They receive the event information
 * as part of their [GenericTraceEventConsumer.consumeTraceEvent] implementation.
 *
 * Generic consumers typically forward events to another system, such as the Android [Log], store
 * them in a database, or perhaps even send them across application (or machine) boundaries.
 *
 * 2. Specific trace event consumers, that implement a specific [TraceEventListener] interface.
 *
 * For example, you could implement the `MyTraceEvents` interface (see above) in a class called
 * `MyTraceEventsConsumer` and register it as a trace events consumer. From then on, whenever
 * a function from the MyTraceEvents interface is called, the corresponding implementation in
 * `MyTraceEventsConsumer` will be called (asynchronously).
 *
 * Specific consumers typically provide specific handler code for specific events. They react
 * on specific events, rather than forward them. For example, switching on a red light on an
 * alarm dashboard, when the event `temperatureTooHigh()` is received.
 *
 * ADVANCED EXAMPLES
 * -----------------
 *
 * Advanced examples of using this trace event mechanism are:
 *
 * - Sending events to a simulation system, which simulates the environment of the system.
 * For example, an event that the cabin temperature has been set, may be processed by a
 * simulator which uses a trace event consumer to receive such messages.
 *
 * - Displaying events on a dashboard, to gain more insight in the current status of the system,
 * rather than having only a scrolling log to look at.
 *
 * - Collecting or sending system usage data for analytics. Developers can define all sorts of
 * semantic events, and the system may collect them easily in a database, for later processing.
 *
 * HOW TO USE THE TRACER (FACTORY) CLASS
 * -------------------------------------
 *
 * The [Tracer.Factory] creates a `tracer` object that implements the event trace functions defined
 * in a [TraceEventListener] interface. The function implementations send the serialized function
 * call and arguments to an event queue, to be processed asynchronously.
 *
 * The event queue is processed asynchronously by trace event consumers (as a co-routine)
 * to make sure their processing never blocks the main thread. Processing of trace events may
 * be throttled to make sure sending many threads in succession does not overload the system.
 * If the event queue overflows, events are lost, rather than blocking the system. (Note that
 * if the event queue overflows, something weird is going on like sending 1000s of messages per
 * second - which you probably shouldn't do; note that this situation is logged to Android [Log]).
 *
 * The events processor is enabled at start-up, but may be suspended at any time using
 * [Tracer.enableTraceEventLogging]. When the event processor is suspended, trace events in the
 * event queue are discarded and lost until the event processor is enabled again and new events
 * are processed again.
 *
 * Example of usage:
 *
 * ```
 * class MyClass {
 *
 *     // Create an event logger for events. The events are defined in interface Tracer.
 *     private val tracer = Tracer.Factory.create<MyClassEvents>(this::class)
 *
 *     // Define a type-safe event interface (no implementation required).
 *     interface MyClassEvents : TraceEventListener {
 *         fun routePlanned(origin: Location, destination: Location, user: User?)
 *     }
 *
 *     // Now, you can trace (log) the event in a function like this:
 *     fun someFunction() {
 *         ...
 *
 *         // This asynchronously sends the event to the event processor. The event processor
 *         // sends it to trace event handlers. By default, the logging event handler is provided,
 *         // which just sends the event to the system log.
 *
 *         tracer.routePlanned(from, to, loggedInUser)
 *
 *         ...
 *     }
 * }
 * ```
 */
class Tracer private constructor(
    private val ownerClass: KClass<*>
) : InvocationHandler {

    private val tagOwnerClass = tagFromOwnerClassName(ownerClass.java.name)

    class Factory {
        companion object {

            /**
             * Get an event logger for a specific class.
             *
             * @param ownerClass Class to get an event logger for, specified as `this::class`.
             * @return The "tracer", to be used as `tracer.someEvent()`.
             */
            inline fun <reified T : TraceEventListener> create(ownerClass: KClass<*>) =
                createForListener<T>(ownerClass, T::class)

            @Suppress("UNCHECKED_CAST")
            fun <T : TraceEventListener> createForListener(
                ownerClass: KClass<*>,
                traceEventListener: KClass<out TraceEventListener>
            ) = Proxy.newProxyInstance(
                ownerClass.java.classLoader,
                arrayOf<Class<*>?>(traceEventListener.java),
                Tracer(ownerClass)
            ) as T
        }
    }

    /**
     * This is the 'invoke' function that gets called whenever the interface of an event logger is
     * called. The invoke function itself needs to return as soon as possible and cause little
     * overhead for the system. Any actions taken as a result of the event should be scheduled onto
     * another thread (and throttled if needed).
     *
     * @param proxy Proxied object.
     * @param method Method being called.
     * @param args Additional arguments to method, may be null.
     * @return Always null; the signature of events functions must be void/Unit.
     */
    override fun invoke(proxy: Any, method: Method, args: Array<Any>?): Any? {

        /**
         * The [proxy] is always a [TraceEventListener] as [Factory.create] creates a proxy
         * object for a subclass of that interface.
         */
        proxy as TraceEventListener
        val logLevel =
            method.getDeclaredAnnotation(LogLevel::class.java)?.logLevel ?: Log.Level.DEBUG

        /**
         * Skip event when the method is a standard (possibly auto-generated) class method.
         * Methods like `toString()` may be implicitly called in a debugger session. Don't search
         * for consumers for those.
         */
        if (!enabled || method.name in arrayOf("equals", "hashCode", "toString")) {
            return null
        }

        // Send the event to the event processor consumer, non-blocking.
        val now = LocalDateTime.now()
        val event = TraceEvent(
            now,
            logLevel,
            ownerClass.java.name,
            method.declaringClass.name,
            method.name,
            args ?: arrayOf()
        )

        /**
         * Log the event to the standard logger here, rather than on a consumer thread, to make
         * sure the order of events and other log messages remains logical.
         */
        if (syncLoggingEnabled) {
            if (predefinedLogFunctionNames.contains(event.functionName)) {
                if (!usePredefinedLogFunction(tagOwnerClass, event.functionName, args)) {

                    // Signal the listener an incorrect signature was found.
                    proxy.incorrectLogSignatureFound()
                }
            } else {

                // Only format the message for non-standard Log events. Use the annotated log level.
                Log.log(logLevel, tagOwnerClass, "event=${createLogMessage(event)}")
            }
        }
        offerTraceEvent(event, now)
        return null
    }

    private fun offerTraceEvent(
        event: TraceEvent,
        now: LocalDateTime?
    ) {
        if (!traceEventChannel.offer(event)) {
            if (syncLoggingEnabled) {

                // Don't repeat the event if it was logged already by the logger. If the event
                // was a simple log event, don't even mention the overflow (not useful).
                if (!predefinedLogFunctionNames.contains(event.functionName)) {
                    Log.log(Log.Level.DEBUG, tagOwnerClass, "Event lost, event=(see previous line)")
                }
            } else {

                // Only format the message for lost events that weren't logged already.
                Log.log(
                    Log.Level.WARN,
                    tagOwnerClass,
                    "Event lost, event=${createLogMessage(event)}"
                )
            }
            ++nrLostTraceEventsSinceLastMsg
            ++nrLostTraceEventsTotal
        }

        // If we lost events, write a log message to indicate so, but at most once every x seconds.
        if (nrLostTraceEventsSinceLastMsg > 0 &&
            timeLastLostTraceEvent.plusSeconds(LIMIT_WARN_SECS).isBefore(now)
        ) {
            Log.log(
                Log.Level.WARN,
                tagOwnerClass,
                "Trace event channel is full, " +
                    "nrLostTraceEventsSinceLastMsg=$nrLostTraceEventsSinceLastMsg, " +
                    "nrLostTraceEventsTotal=$nrLostTraceEventsTotal"
            )
            nrLostTraceEventsSinceLastMsg = 0L
            timeLastLostTraceEvent = now
        }
    }

    /**
     * Trace event handler that writes events to standard logger. This logger is supplied as
     * a default trace event handler for asynchronous logging. It is enabled with
     * [Tracer.setTraceEventLoggingMode].
     */
    internal class LoggingTraceEventConsumer : GenericTraceEventConsumer {

        override suspend fun consumeTraceEvent(traceEvent: TraceEvent) {
            val tagOwnerClass = tagFromOwnerClassName(traceEvent.ownerClass)
            if (predefinedLogFunctionNames.contains(traceEvent.functionName)) {

                // Don't reformat the message if this is a standard log message.
                if (!usePredefinedLogFunction(
                        tagOwnerClass,
                        traceEvent.functionName, traceEvent.args
                    )
                ) {

                    // Signal the listener an incorrect signature was found.
                    incorrectLogSignatureFound()
                }
            } else {
            }
        }
    }

    companion object {
        val TAG = Tracer::class.simpleName!!

        private const val FUN_VERBOSE = "v"
        private const val FUN_DEBUG = "d"
        private const val FUN_INFO = "i"
        private const val FUN_WARN = "w"
        private const val FUN_ERROR = "e"

        internal val predefinedLogFunctionNames =
            setOf(FUN_VERBOSE, FUN_DEBUG, FUN_INFO, FUN_WARN, FUN_ERROR)

        /**
         * Set to true to start processing, false to discard events.
         */
        internal var enabled = true

        /**
         *  If true, always to log on main thread.
         */
        internal var syncLoggingEnabled = true
        internal val loggingTraceEventConsumer = LoggingTraceEventConsumer()

        /**
         * The co-routine scope of the event processor is internal, not private, to
         * increase testability of this module. For example, in test cases you may want
         * to set this to the co-routine scope of the test cases.
         */
        internal var eventProcessorScope = CoroutineScope(Dispatchers.IO)

        /**
         * This is the event processor job, running in co-routine scope
         * [eventProcessorScope].
         */
        internal lateinit var eventProcessorJob: Job

        private const val CHANNEL_CAPACITY = 10000
        internal val traceEventChannel = Channel<TraceEvent>(CHANNEL_CAPACITY)
        internal val traceEventConsumers = TraceEventConsumerCollection()

        private const val LIMIT_WARN_SECS = 10L
        private var nrLostTraceEventsSinceLastMsg = 0L
        private var nrLostTraceEventsTotal = 0L
        private var timeLastLostTraceEvent = LocalDateTime.now().minusSeconds(LIMIT_WARN_SECS)

        enum class LoggingMode { SYNC, ASYNC }

        init {

            // Always switch on synchronous logging by default.
            setTraceEventLoggingMode(LoggingMode.SYNC)
            enableTraceEventLogging(true)
        }

        /**
         * This internal method cancels the event processor to assist in the testability of
         * this module. It cancels the event processor, which will be restarted when a
         * new consumer is added.
         */
        internal suspend fun cancelAndJoinEventProcessor() {
            if (::eventProcessorJob.isInitialized) {
                eventProcessorJob.cancelAndJoin()
            }
            Log.log(Log.Level.DEBUG, TAG, "Cancelled trace event processor")
        }

        fun addTraceEventConsumer(traceEventConsumer: TraceEventConsumer) {
            traceEventConsumers.add(traceEventConsumer)

            /**
             * The event processor is started only when the first consumer is registered and stays
             * active for the lifetime of the application.
             */
            if (!::eventProcessorJob.isInitialized || eventProcessorJob.isCancelled) {
                eventProcessorJob =
                    eventProcessorScope.launch(CoroutineName("processTraceEvents")) {
                        processTraceEvents()
                    }
            }
        }

        fun removeTraceEventConsumer(traceEventConsumer: TraceEventConsumer) {
            traceEventConsumers.remove(traceEventConsumer)
        }

        fun removeAllTraceEventConsumers() {
            traceEventConsumers.all().asSequence().forEach { removeTraceEventConsumer(it) }
        }

        /**
         * Blocking call to eat all events from the event queue until empty. The event processor is
         * temporarily disabled (and reactivated afterwards).
         */
        suspend fun flushTraceEvents() {

            // Suspend processor, start discarding events.
            val wasEnabled = enabled
            enabled = false

            eventProcessorScope.launch(CoroutineName("flushTraceEvents")) {
                while (traceEventChannel.poll() != null) {
                    // Loop until queue is empty.
                }
            }.join()

            // Re-activate event processor if needed.
            enabled = wasEnabled
        }

        fun enableTraceEventLogging(enable: Boolean) {
            enabled = enable
        }

        fun setTraceEventLoggingMode(loggingMode: LoggingMode) {
            if (loggingMode == LoggingMode.ASYNC) {
                // Disabled sync logging and enable async logging.
                syncLoggingEnabled = false
                addTraceEventConsumer(loggingTraceEventConsumer)
            } else {
                // Disabled async logging and enable sync logging.
                syncLoggingEnabled = true
                removeTraceEventConsumer(loggingTraceEventConsumer)
            }
        }

        /**
         * Event processor which takes elements from the event queue and processes them one by one.
         * Event queue handling runs in same scope as [flushTraceEvents].
         */
        private suspend fun processTraceEvents() {
            Log.log(Log.Level.DEBUG, TAG, "Started trace event processor")
            for (traceEvent in traceEventChannel) {

                /**
                 * If the event processor is disabled, it simply discards all events
                 * from the queue, until it is enabled again.
                 */
                if (enabled) {
                    traceEventConsumers.consumeTraceEvent(traceEvent)
                }
            }
        }

        internal fun usePredefinedLogFunction(
            tag: String,
            functionName: String,
            args: Array<out Any>?
        ): Boolean {

            /**
             * The first 2 arguments of a log function must be String and Throwable. This
             * can only happen if you override the log functions with an incorrect signature.
             * Nevertheless, we want to be robust against this sort of mistake as we cannot
             * resolve this compile-time.
             */
            if (args == null || args.isEmpty() || args[0]::class != String::class ||
                (args.size == 2 && args[1]::class.isSubclassOf(Throwable::class)) || args.size > 2
            ) {
                Log.log(
                    Log.Level.ERROR,
                    TAG,
                    "Incorrect log call, expected arguments (String, Throwable), " +
                        "args=${args?.joinToString {
                            it.javaClass.simpleName + ":" + it.toString()
                        }}"
                )
                return false
            }
            val message = args[0] as String
            val e: Throwable? = args.let { if (args.size == 2) args[1] as Throwable else null }
            val level = when (functionName) {
                FUN_VERBOSE -> Log.Level.VERBOSE
                FUN_DEBUG -> Log.Level.DEBUG
                FUN_INFO -> Log.Level.INFO
                FUN_WARN -> Log.Level.WARN
                else -> Log.Level.ERROR
            }
            Log.log(level, tag, message, e)
            return true
        }

        internal fun tagFromOwnerClassName(ownerClassName: String): String {
            val indexPeriod = ownerClassName.lastIndexOf('.')
            if (indexPeriod >= 0 && ownerClassName.length > indexPeriod) {
                return ownerClassName.substring(indexPeriod + 1)
            } else {
                return ownerClassName
            }
        }

        internal fun createLogMessage(traceEvent: TraceEvent): String {
            return "[${traceEvent.dateTime.format(DateTimeFormatter.ISO_DATE_TIME)}] " +
                "${traceEvent.functionName}(${traceEvent.args.joinToString()}) " +
                "- $traceEvent.ownerClass"
        }
    }
}