/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.common;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/**
 * Add reset feature for @see java.util.concurrent.CountDownLatch2
 */
//
public class CountDownLatch2 {
    private final Sync sync;

    /**
     * Constructs a {@code CountDownLatch2} initialized with the given count.
     *
     * @param count the number of times {@link #countDown} must be invoked before threads can pass through {@link
     * #await}
     * @throws IllegalArgumentException if {@code count} is negative
     */
    public CountDownLatch2(int count) {
        if (count < 0)
            throw new IllegalArgumentException("count < 0");
        this.sync = new Sync(count);
    }

    /**
     * Causes the current thread to wait until the latch has counted down to
     * zero, unless the thread is {@linkplain Thread#interrupt interrupted}.
     *
     * <p>If the current count is zero then this method returns immediately.
     *
     * <p>If the current count is greater than zero then the current
     * thread becomes disabled for thread scheduling purposes and lies
     * dormant until one of two things happen:
     * <ul>
     * <li>The count reaches zero due to invocations of the
     * {@link #countDown} method; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread.
     * </ul>
     *
     * <p>If the current thread:
     * <ul>
     * <li>has its interrupted status set on entry to this method; or
     * <li>is {@linkplain Thread#interrupt interrupted} while waiting,
     * </ul>
     * then {@link InterruptedException} is thrown and the current thread's
     * interrupted status is cleared.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     * 使当前线程等待直到闩锁计数为零，除非线程被中断。
    如果当前计数为0，则此方法立即返回。
    如果当前计数大于零，那么出于线程调度的目的，当前线程将被禁用并处于休眠状态，直到发生以下两种情况之一:
    由于倒计时方法的调用，计数达到零;或
    其他一些线程中断当前线程。
    如果当前线程:
    在进入此方法时设置其中断状态;或
    被打断而等待,
    然后抛出InterruptedException，清除当前线程的中断状态。
     */
    public void await() throws InterruptedException {
        sync.acquireSharedInterruptibly(1);
    }

    /**
     * Causes the current thread to wait until the latch has counted down to
     * zero, unless the thread is {@linkplain Thread#interrupt interrupted},
     * or the specified waiting time elapses.
     *
     * <p>If the current count is zero then this method returns immediately
     * with the value {@code true}.
     *
     * <p>If the current count is greater than zero then the current
     * thread becomes disabled for thread scheduling purposes and lies
     * dormant until one of three things happen:
     * <ul>
     * <li>The count reaches zero due to invocations of the
     * {@link #countDown} method; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread; or
     * <li>The specified waiting time elapses.
     * </ul>
     *
     * <p>If the count reaches zero then the method returns with the
     * value {@code true}.
     *
     * <p>If the current thread:
     * <ul>
     * <li>has its interrupted status set on entry to this method; or
     * <li>is {@linkplain Thread#interrupt interrupted} while waiting,
     * </ul>
     * then {@link InterruptedException} is thrown and the current thread's
     * interrupted status is cleared.
     *
     * <p>If the specified waiting time elapses then the value {@code false}
     * is returned.  If the time is less than or equal to zero, the method
     * will not wait at all.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the {@code timeout} argument
     * @return {@code true} if the count reached zero and {@code false} if the waiting time elapsed before the count
     * reached zero
     * @throws InterruptedException if the current thread is interrupted while waiting
     *
    导致当前线程等待，直到闩锁计数为零，除非线程中断，或指定的等待时间结束。
    如果当前计数为0，则此方法立即返回值为true。
    如果当前计数大于零，那么出于线程调度的目的，当前线程将被禁用并处于休眠状态，直到发生以下三种情况之一:
    由于倒计时方法的调用，计数达到零;或
    其他一些线程中断当前线程;或
    指定的等待时间过去了。
    如果计数为0，那么该方法返回值为true。
    如果当前线程:
    在进入此方法时设置其中断状态;或
    被打断而等待,
    然后抛出InterruptedException，清除当前线程的中断状态。
    如果指定的等待时间过期，则返回值false。如果时间小于或等于零，该方法将不会等待。
     */
    public boolean await(long timeout, TimeUnit unit)
        throws InterruptedException {
        return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
    }

    /**
     * Decrements the count of the latch, releasing all waiting threads if
     * the count reaches zero.
     *
     * <p>If the current count is greater than zero then it is decremented.
     * If the new count is zero then all waiting threads are re-enabled for
     * thread scheduling purposes.
     *
     * <p>If the current count equals zero then nothing happens.
     * 减少锁存器的数量，当数量达到零时释放所有等待线程。
     如果当前计数大于零，那么它就会递减。如果新计数为零，那么为了线程调度目的，将重新启用所有等待线程。
     如果当前计数等于0，那么什么也不会发生。
     */
    public void countDown() {
        sync.releaseShared(1);
    }

    /**
     * Returns the current count.
     *
     * <p>This method is typically used for debugging and testing purposes.
     *
     * @return the current count
     * 返回当前计数。
    这种方法通常用于调试和测试目的。
     */
    public long getCount() {
        return sync.getCount();
    }

    public void reset() {
        sync.reset();
    }

    /**
     * Returns a string identifying this latch, as well as its state.
     * The state, in brackets, includes the String {@code "Count ="}
     * followed by the current count.
     *
     * @return a string identifying this latch, as well as its state
     */
    public String toString() {
        return super.toString() + "[Count = " + sync.getCount() + "]";
    }

    /**
     * Synchronization control For CountDownLatch2.
     * Uses AQS state to represent count.
     */
    private static final class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 4982264981922014374L;

        private final int startCount;

        Sync(int count) {
            this.startCount = count;
            setState(count);
        }

        int getCount() {
            return getState();
        }

        protected int tryAcquireShared(int acquires) {
            return (getState() == 0) ? 1 : -1;
        }

        protected boolean tryReleaseShared(int releases) {
            // Decrement count; signal when transition to zero
            for (; ; ) {
                int c = getState();
                if (c == 0)
                    return false;
                int nextc = c - 1;
                if (compareAndSetState(c, nextc))
                    return nextc == 0;
            }
        }

        protected void reset() {
            setState(startCount);
        }
    }
}
