/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.api.common.eventtime;

import org.apache.flink.annotation.Public;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.util.clock.Clock;
import org.apache.flink.util.clock.SystemClock;

import java.time.Duration;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * A WatermarkGenerator that adds idleness detection to another WatermarkGenerator. If no events
 * come within a certain time (timeout duration) then this generator marks the stream as idle, until
 * the next watermark is generated.
 */
@Public
public class WatermarksWithIdleness<T> implements WatermarkGenerator<T> {

    private final WatermarkGenerator<T> watermarks;

    private final IdlenessTimer idlenessTimer;

    /**
     * Creates a new WatermarksWithIdleness generator to the given generator idleness detection with
     * the given timeout.
     *
     * @param watermarks The original watermark generator.
     * @param idleTimeout The timeout for the idleness detection.
     */
    public WatermarksWithIdleness(WatermarkGenerator<T> watermarks, Duration idleTimeout) {
        this(watermarks, idleTimeout, SystemClock.getInstance());
    }

    @VisibleForTesting
    WatermarksWithIdleness(WatermarkGenerator<T> watermarks, Duration idleTimeout, Clock clock) {
        checkNotNull(idleTimeout, "idleTimeout");
        checkArgument(
                !(idleTimeout.isZero() || idleTimeout.isNegative()),
                "idleTimeout must be greater than zero");
        this.watermarks = checkNotNull(watermarks, "watermarks");
        this.idlenessTimer = new IdlenessTimer(clock, idleTimeout);
    }

    @Override
    public void onEvent(T event, long eventTimestamp, WatermarkOutput output) {
        watermarks.onEvent(event, eventTimestamp, output);
        idlenessTimer.activity();
    }

    @Override
    public void onPeriodicEmit(WatermarkOutput output) {
        if (idlenessTimer.checkIfIdle()) {
            output.markIdle();
        } else {
            watermarks.onPeriodicEmit(output);
        }
    }

    // ------------------------------------------------------------------------

    @VisibleForTesting
    static final class IdlenessTimer {

        /** The clock used to measure elapsed time. */
        private final Clock clock;

        /** Counter to detect change. No problem if it overflows. */
        private long counter;

        /** The value of the counter at the last activity check. */
        private long lastCounter;

        /**
         * The first time (relative to {@link Clock#relativeTimeNanos()}) when the activity check
         * found that no activity happened since the last check. Special value: 0 = no timer.
         */
        private long startOfInactivityNanos;

        /** The duration before the output is marked as idle. */
        private final long maxIdleTimeNanos;

        IdlenessTimer(Clock clock, Duration idleTimeout) {
            this.clock = clock;

            long idleNanos;
            try {
                idleNanos = idleTimeout.toNanos();
            } catch (ArithmeticException ignored) {
                // long integer overflow
                idleNanos = Long.MAX_VALUE;
            }

            this.maxIdleTimeNanos = idleNanos;
        }

        public void activity() {
            counter++;
        }

        // 多易教育: 此方法在onPeriodicEmit()中调用，因为onEvent的时候说明收到了数据那一定不是idle
        public boolean checkIfIdle() {
            // 多易教育:  只要收到数据 onEvent() 中就会count++ ,它的变化则表示活跃
            // 多易教育:  如果count 不等于 lastCounter，说明有变化，自然就不是idle
            // 多易教育:  此时将 startOfInactivityNanos重置回0，以便于下一次计算idle时长
            if (counter != lastCounter) {
                // activity since the last check. we reset the timer
                lastCounter = counter;
                startOfInactivityNanos = 0L;
                return false;
            } else // timer started but has not yet reached idle timeout
                // 多易教育: 如果counter == lastCounter ，说明这期间没有数据，是idle，并且startOfInactivityNanos==0，则说明是active之后的第一次idle
            if (startOfInactivityNanos == 0L) {
                // first time that we see no activity since the last periodic probe
                // begin the timer
                startOfInactivityNanos = clock.relativeTimeNanos();  // 多易教育: 记录下此刻的时间，作为 maxIdleTime的计时起点
                return false;
            } else {
                // 多易教育: 走到这里，说明已经不是第一次发现idle了，则比较此刻与idle计时起点，看是否达到idle标记阈值
                return clock.relativeTimeNanos() - startOfInactivityNanos > maxIdleTimeNanos;
            }
        }
    }
}
