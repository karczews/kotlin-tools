/*
 * Copyright (C) 2020-2020, TomTom (http://tomtom.com).
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
package com.tomtom.kotlin.traceevents

import com.tomtom.kotlin.traceevents.TraceLog.LogLevel
import java.time.LocalDateTime

/**
 * Data class to store a single trace event.
 *
 * @param dateTime Event time.
 * @param logLevel Log level (from [TraceLogLevel] annotation).
 * @param tracerClassName Class logging the event.
 * @param taggingClassName Tagging class passed to event, for convenience of debugging.
 * @param interfaceName Interface name, derived from [TraceEventListener].
 * @param stackTraceHolder Throwable from which a stack trace can be produced. `null` when unavailable.
 * @param eventName Function name in interface, which represents the trace event name.
 * @param args Trace event arguments. Specified as array, to avoid expensive array/list conversions.
 */
data class TraceEvent(
    val dateTime: LocalDateTime,
    val logLevel: LogLevel,
    val tracerClassName: String,
    val taggingClassName: String,
    val interfaceName: String,
    val stackTraceHolder: Throwable?,
    val eventName: String,
    val args: Array<Any?>
) {
    /**
     * Need to override the `equals` and `hashCode` functions, as the class contains
     * an `Array`. Otherwise, `equals` would always return `false`.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TraceEvent

        if (dateTime != other.dateTime) return false
        if (logLevel != other.logLevel) return false
        if (tracerClassName != other.tracerClassName) return false
        if (taggingClassName != other.taggingClassName) return false
        if (interfaceName != other.interfaceName) return false
        if (stackTraceHolder != other.stackTraceHolder) return false
        if (eventName != other.eventName) return false
        if (!args.contentDeepEquals(other.args)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = dateTime.hashCode()
        result = 31 * result + logLevel.hashCode()
        result = 31 * result + tracerClassName.hashCode()
        result = 31 * result + taggingClassName.hashCode()
        result = 31 * result + interfaceName.hashCode()
        result = 31 * result + (stackTraceHolder?.hashCode() ?: 0)
        result = 31 * result + eventName.hashCode()
        result = 31 * result + args.contentDeepHashCode()
        return result
    }
}
