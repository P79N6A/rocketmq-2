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
package org.apache.rocketmq.client.consumer;

import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.log.ClientLogger;
import org.apache.rocketmq.common.ThreadFactoryImpl;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.common.protocol.heartbeat.MessageModel;

/**
 * Schedule service for pull consumer
 */
//
public class MQPullConsumerScheduleService {
    private final InternalLogger log = ClientLogger.getLog();
    private final MessageQueueListener messageQueueListener = new MessageQueueListenerImpl();
    private final ConcurrentMap<MessageQueue, PullTaskImpl> taskTable =
        new ConcurrentHashMap<MessageQueue, PullTaskImpl>();
    private DefaultMQPullConsumer defaultMQPullConsumer;
    private int pullThreadNums = 20;
    private ConcurrentMap<String /* topic */, PullTaskCallback> callbackTable =
        new ConcurrentHashMap<String, PullTaskCallback>();
    private ScheduledThreadPoolExecutor scheduledThreadPoolExecutor;

    public MQPullConsumerScheduleService(final String consumerGroup) {
        this.defaultMQPullConsumer = new DefaultMQPullConsumer(consumerGroup);
        this.defaultMQPullConsumer.setMessageModel(MessageModel.CLUSTERING);
    }

    public void putTask(String topic, Set<MessageQueue> mqNewSet) {
//        遍历拉取任务信息
        Iterator<Entry<MessageQueue, PullTaskImpl>> it = this.taskTable.entrySet().iterator();
        while (it.hasNext()) {
            Entry<MessageQueue, PullTaskImpl> next = it.next();
            if (next.getKey().getTopic().equals(topic)) {
                if (!mqNewSet.contains(next.getKey())) {
                    next.getValue().setCancelled(true);
                    it.remove();
                }
            }
        }

        for (MessageQueue mq : mqNewSet) {
//            消息队列不在消息拉取服务缓存中创建消息队列的消息拉取服务并立即开始执行消息拉取服务
            if (!this.taskTable.containsKey(mq)) {
                PullTaskImpl command = new PullTaskImpl(mq);
                this.taskTable.put(mq, command);
                this.scheduledThreadPoolExecutor.schedule(command, 0, TimeUnit.MILLISECONDS);

            }
        }
    }

    public void start() throws MQClientException {
        final String group = this.defaultMQPullConsumer.getConsumerGroup();
        this.scheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(
            this.pullThreadNums,
            new ThreadFactoryImpl("PullMsgThread-" + group)
        );

        this.defaultMQPullConsumer.setMessageQueueListener(this.messageQueueListener);

        this.defaultMQPullConsumer.start();

        log.info("MQPullConsumerScheduleService start OK, {} {}",
            this.defaultMQPullConsumer.getConsumerGroup(), this.callbackTable);
    }

    public void registerPullTaskCallback(final String topic, final PullTaskCallback callback) {
        this.callbackTable.put(topic, callback);
        this.defaultMQPullConsumer.registerMessageQueueListener(topic, null);
    }

    public void shutdown() {
        if (this.scheduledThreadPoolExecutor != null) {
            this.scheduledThreadPoolExecutor.shutdown();
        }

        if (this.defaultMQPullConsumer != null) {
            this.defaultMQPullConsumer.shutdown();
        }
    }

    public ConcurrentMap<String, PullTaskCallback> getCallbackTable() {
        return callbackTable;
    }

    public void setCallbackTable(ConcurrentHashMap<String, PullTaskCallback> callbackTable) {
        this.callbackTable = callbackTable;
    }

    public int getPullThreadNums() {
        return pullThreadNums;
    }

    public void setPullThreadNums(int pullThreadNums) {
        this.pullThreadNums = pullThreadNums;
    }

    public DefaultMQPullConsumer getDefaultMQPullConsumer() {
        return defaultMQPullConsumer;
    }

    public void setDefaultMQPullConsumer(DefaultMQPullConsumer defaultMQPullConsumer) {
        this.defaultMQPullConsumer = defaultMQPullConsumer;
    }

    public MessageModel getMessageModel() {
        return this.defaultMQPullConsumer.getMessageModel();
    }

    public void setMessageModel(MessageModel messageModel) {
        this.defaultMQPullConsumer.setMessageModel(messageModel);
    }

    class MessageQueueListenerImpl implements MessageQueueListener {
        @Override
        public void messageQueueChanged(String topic, Set<MessageQueue> mqAll, Set<MessageQueue> mqDivided) {
//            获取默认mq拉去消息服务consumer的消息消费类型是集群消费还是广播消费，集群消费是为了集群中节点可以负载均衡
//            默认值是集群消费
            MessageModel messageModel =
                MQPullConsumerScheduleService.this.defaultMQPullConsumer.getMessageModel();
            switch (messageModel) {
//                广播消费=》
                case BROADCASTING:
                    MQPullConsumerScheduleService.this.putTask(topic, mqAll);
                    break;
//                    集群消费=》
                case CLUSTERING:
                    MQPullConsumerScheduleService.this.putTask(topic, mqDivided);
                    break;
                default:
                    break;
            }
        }
    }

    class PullTaskImpl implements Runnable {
        private final MessageQueue messageQueue;
        private volatile boolean cancelled = false;

        public PullTaskImpl(final MessageQueue messageQueue) {
            this.messageQueue = messageQueue;
        }

        @Override
        public void run() {
            String topic = this.messageQueue.getTopic();
            if (!this.isCancelled()) {
                PullTaskCallback pullTaskCallback =
                    MQPullConsumerScheduleService.this.callbackTable.get(topic);
                if (pullTaskCallback != null) {
                    final PullTaskContext context = new PullTaskContext();
                    context.setPullConsumer(MQPullConsumerScheduleService.this.defaultMQPullConsumer);
                    try {
//                        执行消息拉取的回调方法，这个需要客户端实现
                        pullTaskCallback.doPullTask(this.messageQueue, context);
                    } catch (Throwable e) {
                        context.setPullNextDelayTimeMillis(1000);
                        log.error("doPullTask Exception", e);
                    }

//                    出现异常，1000ms后重新拉取
                    if (!this.isCancelled()) {
                        MQPullConsumerScheduleService.this.scheduledThreadPoolExecutor.schedule(this,
                            context.getPullNextDelayTimeMillis(), TimeUnit.MILLISECONDS);
                    } else {
                        log.warn("The Pull Task is cancelled after doPullTask, {}", messageQueue);
                    }
                } else {
                    log.warn("Pull Task Callback not exist , {}", topic);
                }
            } else {
                log.warn("The Pull Task is cancelled, {}", messageQueue);
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }

        public MessageQueue getMessageQueue() {
            return messageQueue;
        }
    }
}
