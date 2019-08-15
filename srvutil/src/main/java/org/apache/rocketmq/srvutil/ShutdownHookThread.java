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

package org.apache.rocketmq.srvutil;

import org.apache.rocketmq.logging.InternalLogger;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link ShutdownHookThread} is the standard hook for filtersrv and namesrv modules.
 * Through {@link Callable} interface, this hook can customization operations in anywhere.ShutdownHookThread是filtersrv和namesrv模块的标准钩子。通过可调用接口，这个钩子可以在任何地方定制操作。
 */
//
public class ShutdownHookThread extends Thread {
    private volatile boolean hasShutdown = false;
    private AtomicInteger shutdownTimes = new AtomicInteger(0);
    private final InternalLogger log;
    private final Callable callback;

    /**
     * Create the standard hook thread, with a call back, by using {@link Callable} interface.使用Callable接口创建标准的钩子线程，并返回一个调用。
     *
     * @param log The log instance is used in hook thread.
     * @param callback The call back function.
     */
//
    public ShutdownHookThread(InternalLogger log, Callable callback) {
        super("ShutdownHook");
        this.log = log;
        this.callback = callback;
    }

    /**
     * Thread run method.
     * Invoke when the jvm shutdown.
     * 1. count the invocation times.
     * 2. execute the {@link ShutdownHookThread#callback}, and time it.线程运行的方法。当jvm关闭时调用。1。计算调用次数。2。执行回调，并计时。
     */
    @Override
    public void run() {
        synchronized (this) {
            log.info("shutdown hook was invoked, " + this.shutdownTimes.incrementAndGet() + " times.");
            if (!this.hasShutdown) {
                this.hasShutdown = true;
                long beginTime = System.currentTimeMillis();
                try {
                    this.callback.call();
                } catch (Exception e) {
                    log.error("shutdown hook callback invoked failure.", e);
                }
                long consumingTimeTotal = System.currentTimeMillis() - beginTime;
                log.info("shutdown hook done, consuming time total(ms): " + consumingTimeTotal);
            }
        }
    }
}
