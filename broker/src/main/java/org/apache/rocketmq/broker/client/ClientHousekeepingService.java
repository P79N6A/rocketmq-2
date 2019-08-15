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
package org.apache.rocketmq.broker.client;

import io.netty.channel.Channel;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.broker.BrokerController;
import org.apache.rocketmq.common.ThreadFactoryImpl;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.ChannelEventListener;
//
public class ClientHousekeepingService implements ChannelEventListener {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.BROKER_LOGGER_NAME);
    private final BrokerController brokerController;

    private ScheduledExecutorService scheduledExecutorService = Executors
        .newSingleThreadScheduledExecutor(new ThreadFactoryImpl("ClientHousekeepingScheduledThread"));

    public ClientHousekeepingService(final BrokerController brokerController) {
        this.brokerController = brokerController;
    }
//
    public void start() {

        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
//                    扫描异常的channel=》
                    ClientHousekeepingService.this.scanExceptionChannel();
                } catch (Throwable e) {
                    log.error("Error occurred when scan not active client channels.", e);
                }
            }
        }, 1000 * 10, 1000 * 10, TimeUnit.MILLISECONDS);
    }
//
    private void scanExceptionChannel() {
//        生产者异常channel=》
        this.brokerController.getProducerManager().scanNotActiveChannel();
//        消费者异常channel=》
        this.brokerController.getConsumerManager().scanNotActiveChannel();
//        过滤的服务异常channel=》
        this.brokerController.getFilterServerManager().scanNotActiveChannel();
    }

    public void shutdown() {
        this.scheduledExecutorService.shutdown();
    }

    @Override
    public void onChannelConnect(String remoteAddr, Channel channel) {

    }

    @Override
    public void onChannelClose(String remoteAddr, Channel channel) {
//        生产者channel关闭事件=》
        this.brokerController.getProducerManager().doChannelCloseEvent(remoteAddr, channel);
//        消费者channel关闭事件=》
        this.brokerController.getConsumerManager().doChannelCloseEvent(remoteAddr, channel);
//        过滤的broker channel关闭事件
        this.brokerController.getFilterServerManager().doChannelCloseEvent(remoteAddr, channel);
    }

    @Override
    public void onChannelException(String remoteAddr, Channel channel) {
//        生产者channel销毁=》
        this.brokerController.getProducerManager().doChannelCloseEvent(remoteAddr, channel);
//        消费者channel销毁=》
        this.brokerController.getConsumerManager().doChannelCloseEvent(remoteAddr, channel);
//        过滤broker channel销毁=》
        this.brokerController.getFilterServerManager().doChannelCloseEvent(remoteAddr, channel);
    }

    @Override
    public void onChannelIdle(String remoteAddr, Channel channel) {
//        处理生产者关闭事件=》
        this.brokerController.getProducerManager().doChannelCloseEvent(remoteAddr, channel);
//        处理消费者关闭事件=》
        this.brokerController.getConsumerManager().doChannelCloseEvent(remoteAddr, channel);
//        过滤的server关闭事件处理=》
        this.brokerController.getFilterServerManager().doChannelCloseEvent(remoteAddr, channel);
    }
}
