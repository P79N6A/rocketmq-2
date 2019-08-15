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
package org.apache.rocketmq.namesrv.routeinfo;

import io.netty.channel.Channel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.rocketmq.common.DataVersion;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.TopicConfig;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.constant.PermName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.common.namesrv.RegisterBrokerResult;
import org.apache.rocketmq.common.protocol.body.ClusterInfo;
import org.apache.rocketmq.common.protocol.body.TopicConfigSerializeWrapper;
import org.apache.rocketmq.common.protocol.body.TopicList;
import org.apache.rocketmq.common.protocol.route.BrokerData;
import org.apache.rocketmq.common.protocol.route.QueueData;
import org.apache.rocketmq.common.protocol.route.TopicRouteData;
import org.apache.rocketmq.common.sysflag.TopicSysFlag;
import org.apache.rocketmq.remoting.common.RemotingUtil;

public class RouteInfoManager {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.NAMESRV_LOGGER_NAME);
    private final static long BROKER_CHANNEL_EXPIRED_TIME = 1000 * 60 * 2;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final HashMap<String/* topic */, List<QueueData>> topicQueueTable;
    private final HashMap<String/* brokerName */, BrokerData> brokerAddrTable;
    private final HashMap<String/* clusterName */, Set<String/* brokerName */>> clusterAddrTable;
    private final HashMap<String/* brokerAddr */, BrokerLiveInfo> brokerLiveTable;
    private final HashMap<String/* brokerAddr */, List<String>/* Filter Server */> filterServerTable;

//    mark
    public RouteInfoManager() {
//        指定长度，减少扩容提高性能
        this.topicQueueTable = new HashMap<String, List<QueueData>>(1024);//topicQueue缓存信息
        this.brokerAddrTable = new HashMap<String, BrokerData>(128);//broker地址缓存
        this.clusterAddrTable = new HashMap<String, Set<String>>(32);//集群地址缓存
        this.brokerLiveTable = new HashMap<String, BrokerLiveInfo>(256);//可用的broker缓存
        this.filterServerTable = new HashMap<String, List<String>>(256);//过滤的server地址
    }
//
    public byte[] getAllClusterInfo() {
        ClusterInfo clusterInfoSerializeWrapper = new ClusterInfo();
//        broker数据
        clusterInfoSerializeWrapper.setBrokerAddrTable(this.brokerAddrTable);
//        broker集群
        clusterInfoSerializeWrapper.setClusterAddrTable(this.clusterAddrTable);
        return clusterInfoSerializeWrapper.encode();
    }
//
    public void deleteTopic(final String topic) {
        try {
            try {
                this.lock.writeLock().lockInterruptibly();
                this.topicQueueTable.remove(topic);
            } finally {
                this.lock.writeLock().unlock();
            }
        } catch (Exception e) {
            log.error("deleteTopic Exception", e);
        }
    }
//
    public byte[] getAllTopicList() {
        TopicList topicList = new TopicList();
        try {
            try {
                this.lock.readLock().lockInterruptibly();
                topicList.getTopicList().addAll(this.topicQueueTable.keySet());
            } finally {
                this.lock.readLock().unlock();
            }
        } catch (Exception e) {
            log.error("getAllTopicList Exception", e);
        }

        return topicList.encode();
    }
//
    public RegisterBrokerResult registerBroker(
        final String clusterName,
        final String brokerAddr,
        final String brokerName,
        final long brokerId,
        final String haServerAddr,
        final TopicConfigSerializeWrapper topicConfigWrapper,
        final List<String> filterServerList,
        final Channel channel) {
        RegisterBrokerResult result = new RegisterBrokerResult();
        try {
            try {
//                同步注册
                this.lock.writeLock().lockInterruptibly();

//                获取broker的集群地址
                Set<String> brokerNames = this.clusterAddrTable.get(clusterName);
                if (null == brokerNames) {
                    brokerNames = new HashSet<String>();
                    this.clusterAddrTable.put(clusterName, brokerNames);
                }
                brokerNames.add(brokerName);

                boolean registerFirst = false;

//               获取指定brokerName的broker数据
                BrokerData brokerData = this.brokerAddrTable.get(brokerName);
                if (null == brokerData) {
                    registerFirst = true;
                    brokerData = new BrokerData(clusterName, brokerName, new HashMap<Long, String>());
                    this.brokerAddrTable.put(brokerName, brokerData);
                }
                String oldAddr = brokerData.getBrokerAddrs().put(brokerId, brokerAddr);
                registerFirst = registerFirst || (null == oldAddr);

//                如果broker是master
                if (null != topicConfigWrapper
                    && MixAll.MASTER_ID == brokerId) {
                    if (this.isBrokerTopicConfigChanged(brokerAddr, topicConfigWrapper.getDataVersion())
                        || registerFirst) {
//                        获取topic配置
                        ConcurrentMap<String, TopicConfig> tcTable =
                            topicConfigWrapper.getTopicConfigTable();
                        if (tcTable != null) {
                            for (Map.Entry<String, TopicConfig> entry : tcTable.entrySet()) {
//                                创建或更新消息队列数据=》
                                this.createAndUpdateQueueData(brokerName, entry.getValue());
                            }
                        }
                    }
                }

//                存活的broker列表
                BrokerLiveInfo prevBrokerLiveInfo = this.brokerLiveTable.put(brokerAddr,
                    new BrokerLiveInfo(
                        System.currentTimeMillis(),
                        topicConfigWrapper.getDataVersion(),
                        channel,
                        haServerAddr));
                if (null == prevBrokerLiveInfo) {
                    log.info("new broker registered, {} HAServer: {}", brokerAddr, haServerAddr);
                }

//                过滤的broker列表
                if (filterServerList != null) {
                    if (filterServerList.isEmpty()) {
                        this.filterServerTable.remove(brokerAddr);
                    } else {
                        this.filterServerTable.put(brokerAddr, filterServerList);
                    }
                }

//                如果broker不是master
                if (MixAll.MASTER_ID != brokerId) {
//                    获取broker master地址
                    String masterAddr = brokerData.getBrokerAddrs().get(MixAll.MASTER_ID);
                    if (masterAddr != null) {
                        BrokerLiveInfo brokerLiveInfo = this.brokerLiveTable.get(masterAddr);
                        if (brokerLiveInfo != null) {
                            result.setHaServerAddr(brokerLiveInfo.getHaServerAddr());
                            result.setMasterAddr(masterAddr);
                        }
                    }
                }
            } finally {
                this.lock.writeLock().unlock();
            }
        } catch (Exception e) {
            log.error("registerBroker Exception", e);
        }

        return result;
    }
//
    public boolean isBrokerTopicConfigChanged(final String brokerAddr, final DataVersion dataVersion) {
//        查询broker的topic配置版本=》
        DataVersion prev = queryBrokerTopicConfig(brokerAddr);
        return null == prev || !prev.equals(dataVersion);
    }
//
    public DataVersion queryBrokerTopicConfig(final String brokerAddr) {
        BrokerLiveInfo prev = this.brokerLiveTable.get(brokerAddr);
        if (prev != null) {
            return prev.getDataVersion();
        }
        return null;
    }
//
    public void updateBrokerInfoUpdateTimestamp(final String brokerAddr) {
        BrokerLiveInfo prev = this.brokerLiveTable.get(brokerAddr);
        if (prev != null) {
            prev.setLastUpdateTimestamp(System.currentTimeMillis());
        }
    }
//
    private void createAndUpdateQueueData(final String brokerName, final TopicConfig topicConfig) {
        QueueData queueData = new QueueData();
        queueData.setBrokerName(brokerName);
        queueData.setWriteQueueNums(topicConfig.getWriteQueueNums());
        queueData.setReadQueueNums(topicConfig.getReadQueueNums());
        queueData.setPerm(topicConfig.getPerm());
        queueData.setTopicSynFlag(topicConfig.getTopicSysFlag());

//        查询topic的队列
        List<QueueData> queueDataList = this.topicQueueTable.get(topicConfig.getTopicName());
        if (null == queueDataList) {
            queueDataList = new LinkedList<QueueData>();
            queueDataList.add(queueData);
            this.topicQueueTable.put(topicConfig.getTopicName(), queueDataList);
            log.info("new topic registered, {} {}", topicConfig.getTopicName(), queueData);
        } else {
            boolean addNewOne = true;

            Iterator<QueueData> it = queueDataList.iterator();
            while (it.hasNext()) {
                QueueData qd = it.next();
                if (qd.getBrokerName().equals(brokerName)) {
                    if (qd.equals(queueData)) {
//                        topic存在的队列和要保存的队列一样，不需要添加
                        addNewOne = false;
                    } else {
                        log.info("topic changed, {} OLD: {} NEW: {}", topicConfig.getTopicName(), qd,
                            queueData);
//                        如果topic存在的队列和要保存的队列不一致，就删除存在的队列
                        it.remove();
                    }
                }
            }

//            需要添加
            if (addNewOne) {
                queueDataList.add(queueData);
            }
        }
    }
//
    public int wipeWritePermOfBrokerByLock(final String brokerName) {
        try {
            try {
                this.lock.writeLock().lockInterruptibly();
                return wipeWritePermOfBroker(brokerName);
            } finally {
                this.lock.writeLock().unlock();
            }
        } catch (Exception e) {
            log.error("wipeWritePermOfBrokerByLock Exception", e);
        }

        return 0;
    }
//
    private int wipeWritePermOfBroker(final String brokerName) {
        int wipeTopicCnt = 0;
        Iterator<Entry<String, List<QueueData>>> itTopic = this.topicQueueTable.entrySet().iterator();
        while (itTopic.hasNext()) {
            Entry<String, List<QueueData>> entry = itTopic.next();
            List<QueueData> qdList = entry.getValue();

            Iterator<QueueData> it = qdList.iterator();
            while (it.hasNext()) {
                QueueData qd = it.next();
                if (qd.getBrokerName().equals(brokerName)) {
                    int perm = qd.getPerm();
                    perm &= ~PermName.PERM_WRITE;
                    qd.setPerm(perm);
                    wipeTopicCnt++;
                }
            }
        }

        return wipeTopicCnt;
    }
//
    public void unregisterBroker(
        final String clusterName,
        final String brokerAddr,
        final String brokerName,
        final long brokerId) {
        try {
            try {
                this.lock.writeLock().lockInterruptibly();
//                删除存活的broker信息
                BrokerLiveInfo brokerLiveInfo = this.brokerLiveTable.remove(brokerAddr);
                log.info("unregisterBroker, remove from brokerLiveTable {}, {}",
                    brokerLiveInfo != null ? "OK" : "Failed",
                    brokerAddr
                );

//                删除过滤的broker列表
                this.filterServerTable.remove(brokerAddr);

                boolean removeBrokerName = false;
//                获取broker数据
                BrokerData brokerData = this.brokerAddrTable.get(brokerName);
                if (null != brokerData) {
//                    从broker数据中删除broker
                    String addr = brokerData.getBrokerAddrs().remove(brokerId);
                    log.info("unregisterBroker, remove addr from brokerAddrTable {}, {}",
                        addr != null ? "OK" : "Failed",
                        brokerAddr
                    );

                    if (brokerData.getBrokerAddrs().isEmpty()) {
//                        删除broker数据
                        this.brokerAddrTable.remove(brokerName);
                        log.info("unregisterBroker, remove name from brokerAddrTable OK, {}",
                            brokerName
                        );

                        removeBrokerName = true;
                    }
                }

                if (removeBrokerName) {
//                    获取broker集群
                    Set<String> nameSet = this.clusterAddrTable.get(clusterName);
                    if (nameSet != null) {
                        boolean removed = nameSet.remove(brokerName);
                        log.info("unregisterBroker, remove name from clusterAddrTable {}, {}",
                            removed ? "OK" : "Failed",
                            brokerName);

                        if (nameSet.isEmpty()) {
                            this.clusterAddrTable.remove(clusterName);
                            log.info("unregisterBroker, remove cluster from clusterAddrTable {}",
                                clusterName
                            );
                        }
                    }
//                    删除broker的topic=》
                    this.removeTopicByBrokerName(brokerName);
                }
            } finally {
                this.lock.writeLock().unlock();
            }
        } catch (Exception e) {
            log.error("unregisterBroker Exception", e);
        }
    }
//
    private void removeTopicByBrokerName(final String brokerName) {
        Iterator<Entry<String, List<QueueData>>> itMap = this.topicQueueTable.entrySet().iterator();
        while (itMap.hasNext()) {
            Entry<String, List<QueueData>> entry = itMap.next();

            String topic = entry.getKey();
            List<QueueData> queueDataList = entry.getValue();
            Iterator<QueueData> it = queueDataList.iterator();
            while (it.hasNext()) {
                QueueData qd = it.next();
//                获取broker的消息队列并删除
                if (qd.getBrokerName().equals(brokerName)) {
                    log.info("removeTopicByBrokerName, remove one broker's topic {} {}", topic, qd);
                    it.remove();
                }
            }

//            如果topic的消息队列为空，删除topic的消息队列
            if (queueDataList.isEmpty()) {
                log.info("removeTopicByBrokerName, remove the topic all queue {}", topic);
                itMap.remove();
            }
        }
    }
//
    public TopicRouteData pickupTopicRouteData(final String topic) {
        TopicRouteData topicRouteData = new TopicRouteData();
        boolean foundQueueData = false;
        boolean foundBrokerData = false;
        Set<String> brokerNameSet = new HashSet<String>();
        List<BrokerData> brokerDataList = new LinkedList<BrokerData>();
        topicRouteData.setBrokerDatas(brokerDataList);

        HashMap<String, List<String>> filterServerMap = new HashMap<String, List<String>>();
        topicRouteData.setFilterServerTable(filterServerMap);

        try {
            try {
                this.lock.readLock().lockInterruptibly();
//                获取topic的消息队列
                List<QueueData> queueDataList = this.topicQueueTable.get(topic);
                if (queueDataList != null) {
                    topicRouteData.setQueueDatas(queueDataList);
                    foundQueueData = true;

                    Iterator<QueueData> it = queueDataList.iterator();
                    while (it.hasNext()) {
                        QueueData qd = it.next();
//                        从消息队列中获取broker
                        brokerNameSet.add(qd.getBrokerName());
                    }

                    for (String brokerName : brokerNameSet) {
//                        获取broker数据
                        BrokerData brokerData = this.brokerAddrTable.get(brokerName);
                        if (null != brokerData) {
                            BrokerData brokerDataClone = new BrokerData(brokerData.getCluster(), brokerData.getBrokerName(), (HashMap<Long, String>) brokerData
                                .getBrokerAddrs().clone());
                            brokerDataList.add(brokerDataClone);
                            foundBrokerData = true;
                            for (final String brokerAddr : brokerDataClone.getBrokerAddrs().values()) {
//                                获取过滤的broker地址
                                List<String> filterServerList = this.filterServerTable.get(brokerAddr);
                                filterServerMap.put(brokerAddr, filterServerList);
                            }
                        }
                    }
                }
            } finally {
                this.lock.readLock().unlock();
            }
        } catch (Exception e) {
            log.error("pickupTopicRouteData Exception", e);
        }

        log.debug("pickupTopicRouteData {} {}", topic, topicRouteData);

        if (foundBrokerData && foundQueueData) {
            return topicRouteData;
        }

        return null;
    }
//
    public void scanNotActiveBroker() {
//        迭代活动的列表
        Iterator<Entry<String, BrokerLiveInfo>> it = this.brokerLiveTable.entrySet().iterator();
        while (it.hasNext()) {
            Entry<String, BrokerLiveInfo> next = it.next();
//            获取最后更新时间
            long last = next.getValue().getLastUpdateTimestamp();
            if ((last + BROKER_CHANNEL_EXPIRED_TIME) < System.currentTimeMillis()) {
//                超过存活时间，关闭channel，移除活动列表
                RemotingUtil.closeChannel(next.getValue().getChannel());
                it.remove();
                log.warn("The broker channel expired, {} {}ms", next.getKey(), BROKER_CHANNEL_EXPIRED_TIME);
//                执行channel关闭事件
                this.onChannelDestroy(next.getKey(), next.getValue().getChannel());
            }
        }
    }
//
    public void onChannelDestroy(String remoteAddr, Channel channel) {
        String brokerAddrFound = null;
        if (channel != null) {
            try {
                try {
                    this.lock.readLock().lockInterruptibly();
//                    遍历存活的broker按channel删除
                    Iterator<Entry<String, BrokerLiveInfo>> itBrokerLiveTable =
                        this.brokerLiveTable.entrySet().iterator();
                    while (itBrokerLiveTable.hasNext()) {
                        Entry<String, BrokerLiveInfo> entry = itBrokerLiveTable.next();
                        if (entry.getValue().getChannel() == channel) {
                            brokerAddrFound = entry.getKey();
                            break;
                        }
                    }
                } finally {
                    this.lock.readLock().unlock();
                }
            } catch (Exception e) {
                log.error("onChannelDestroy Exception", e);
            }
        }

        if (null == brokerAddrFound) {
            brokerAddrFound = remoteAddr;
        } else {
            log.info("the broker's channel destroyed, {}, clean it's data structure at once", brokerAddrFound);
        }

        if (brokerAddrFound != null && brokerAddrFound.length() > 0) {

            try {
                try {
                    this.lock.writeLock().lockInterruptibly();
//                    删除存活的broker
                    this.brokerLiveTable.remove(brokerAddrFound);
//                    删除过滤的broker
                    this.filterServerTable.remove(brokerAddrFound);
                    String brokerNameFound = null;
                    boolean removeBrokerName = false;
                    Iterator<Entry<String, BrokerData>> itBrokerAddrTable =
                        this.brokerAddrTable.entrySet().iterator();
                    while (itBrokerAddrTable.hasNext() && (null == brokerNameFound)) {
                        BrokerData brokerData = itBrokerAddrTable.next().getValue();

                        Iterator<Entry<Long, String>> it = brokerData.getBrokerAddrs().entrySet().iterator();
                        while (it.hasNext()) {
                            Entry<Long, String> entry = it.next();
                            Long brokerId = entry.getKey();
                            String brokerAddr = entry.getValue();
                            if (brokerAddr.equals(brokerAddrFound)) {
                                brokerNameFound = brokerData.getBrokerName();
//                                从broker数据中删除broker
                                it.remove();
                                log.info("remove brokerAddr[{}, {}] from brokerAddrTable, because channel destroyed",
                                    brokerId, brokerAddr);
                                break;
                            }
                        }

                        if (brokerData.getBrokerAddrs().isEmpty()) {
                            removeBrokerName = true;
                            itBrokerAddrTable.remove();
                            log.info("remove brokerName[{}] from brokerAddrTable, because channel destroyed",
                                brokerData.getBrokerName());
                        }
                    }

                    if (brokerNameFound != null && removeBrokerName) {
                        Iterator<Entry<String, Set<String>>> it = this.clusterAddrTable.entrySet().iterator();
                        while (it.hasNext()) {
                            Entry<String, Set<String>> entry = it.next();
                            String clusterName = entry.getKey();
                            Set<String> brokerNames = entry.getValue();
//                            从broker集群地址缓存中删除broker
                            boolean removed = brokerNames.remove(brokerNameFound);
                            if (removed) {
                                log.info("remove brokerName[{}], clusterName[{}] from clusterAddrTable, because channel destroyed",
                                    brokerNameFound, clusterName);

                                if (brokerNames.isEmpty()) {
                                    log.info("remove the clusterName[{}] from clusterAddrTable, because channel destroyed and no broker in this cluster",
                                        clusterName);
                                    it.remove();
                                }

                                break;
                            }
                        }
                    }

//                    从topic队列中删除broker
                    if (removeBrokerName) {
                        Iterator<Entry<String, List<QueueData>>> itTopicQueueTable =
                            this.topicQueueTable.entrySet().iterator();
                        while (itTopicQueueTable.hasNext()) {
                            Entry<String, List<QueueData>> entry = itTopicQueueTable.next();
                            String topic = entry.getKey();
                            List<QueueData> queueDataList = entry.getValue();

                            Iterator<QueueData> itQueueData = queueDataList.iterator();
                            while (itQueueData.hasNext()) {
                                QueueData queueData = itQueueData.next();
                                if (queueData.getBrokerName().equals(brokerNameFound)) {
//                                    删除broker的消息队列数据
                                    itQueueData.remove();
                                    log.info("remove topic[{} {}], from topicQueueTable, because channel destroyed",
                                        topic, queueData);
                                }
                            }

                            if (queueDataList.isEmpty()) {
                                itTopicQueueTable.remove();
                                log.info("remove topic[{}] all queue, from topicQueueTable, because channel destroyed",
                                    topic);
                            }
                        }
                    }
                } finally {
                    this.lock.writeLock().unlock();
                }
            } catch (Exception e) {
                log.error("onChannelDestroy Exception", e);
            }
        }
    }
//
    public void printAllPeriodically() {
        try {
            try {
                this.lock.readLock().lockInterruptibly();
                log.info("--------------------------------------------------------");
                {
                    log.info("topicQueueTable SIZE: {}", this.topicQueueTable.size());
                    Iterator<Entry<String, List<QueueData>>> it = this.topicQueueTable.entrySet().iterator();
                    while (it.hasNext()) {
                        Entry<String, List<QueueData>> next = it.next();
                        log.info("topicQueueTable Topic: {} {}", next.getKey(), next.getValue());
                    }
                }

                {
                    log.info("brokerAddrTable SIZE: {}", this.brokerAddrTable.size());
                    Iterator<Entry<String, BrokerData>> it = this.brokerAddrTable.entrySet().iterator();
                    while (it.hasNext()) {
                        Entry<String, BrokerData> next = it.next();
                        log.info("brokerAddrTable brokerName: {} {}", next.getKey(), next.getValue());
                    }
                }

                {
                    log.info("brokerLiveTable SIZE: {}", this.brokerLiveTable.size());
                    Iterator<Entry<String, BrokerLiveInfo>> it = this.brokerLiveTable.entrySet().iterator();
                    while (it.hasNext()) {
                        Entry<String, BrokerLiveInfo> next = it.next();
                        log.info("brokerLiveTable brokerAddr: {} {}", next.getKey(), next.getValue());
                    }
                }

                {
                    log.info("clusterAddrTable SIZE: {}", this.clusterAddrTable.size());
                    Iterator<Entry<String, Set<String>>> it = this.clusterAddrTable.entrySet().iterator();
                    while (it.hasNext()) {
                        Entry<String, Set<String>> next = it.next();
                        log.info("clusterAddrTable clusterName: {} {}", next.getKey(), next.getValue());
                    }
                }
            } finally {
                this.lock.readLock().unlock();
            }
        } catch (Exception e) {
            log.error("printAllPeriodically Exception", e);
        }
    }
//
    public byte[] getSystemTopicList() {
        TopicList topicList = new TopicList();
        try {
            try {
                this.lock.readLock().lockInterruptibly();
                for (Map.Entry<String, Set<String>> entry : clusterAddrTable.entrySet()) {
//                    这里有点疑问，broker集群的clusterName、brokerName添加到topic列表中是什么意思，系统topic就是这些名字吗
                    topicList.getTopicList().add(entry.getKey());
                    topicList.getTopicList().addAll(entry.getValue());
                }

                if (brokerAddrTable != null && !brokerAddrTable.isEmpty()) {
                    Iterator<String> it = brokerAddrTable.keySet().iterator();
                    while (it.hasNext()) {
                        BrokerData bd = brokerAddrTable.get(it.next());
                        HashMap<Long, String> brokerAddrs = bd.getBrokerAddrs();
                        if (brokerAddrs != null && !brokerAddrs.isEmpty()) {
                            Iterator<Long> it2 = brokerAddrs.keySet().iterator();
//                            从broker数据中获取broker地址
                            topicList.setBrokerAddr(brokerAddrs.get(it2.next()));
                            break;
                        }
                    }
                }
            } finally {
                this.lock.readLock().unlock();
            }
        } catch (Exception e) {
            log.error("getAllTopicList Exception", e);
        }

        return topicList.encode();
    }
//
    public byte[] getTopicsByCluster(String cluster) {
        TopicList topicList = new TopicList();
        try {
            try {
                this.lock.readLock().lockInterruptibly();
//                获取集群的broker
                Set<String> brokerNameSet = this.clusterAddrTable.get(cluster);
                for (String brokerName : brokerNameSet) {
                    Iterator<Entry<String, List<QueueData>>> topicTableIt =
                        this.topicQueueTable.entrySet().iterator();
                    while (topicTableIt.hasNext()) {
                        Entry<String, List<QueueData>> topicEntry = topicTableIt.next();
                        String topic = topicEntry.getKey();
                        List<QueueData> queueDatas = topicEntry.getValue();
                        for (QueueData queueData : queueDatas) {
                            if (brokerName.equals(queueData.getBrokerName())) {
//                                从topic的消息队列中获取topic
                                topicList.getTopicList().add(topic);
                                break;
                            }
                        }
                    }
                }
            } finally {
                this.lock.readLock().unlock();
            }
        } catch (Exception e) {
            log.error("getAllTopicList Exception", e);
        }

        return topicList.encode();
    }
//
    public byte[] getUnitTopics() {
        TopicList topicList = new TopicList();
        try {
            try {
                this.lock.readLock().lockInterruptibly();
                Iterator<Entry<String, List<QueueData>>> topicTableIt =
                    this.topicQueueTable.entrySet().iterator();
                while (topicTableIt.hasNext()) {
                    Entry<String, List<QueueData>> topicEntry = topicTableIt.next();
                    String topic = topicEntry.getKey();
                    List<QueueData> queueDatas = topicEntry.getValue();
                    if (queueDatas != null && queueDatas.size() > 0
                        && TopicSysFlag.hasUnitFlag(queueDatas.get(0).getTopicSynFlag())) {
                        topicList.getTopicList().add(topic);
                    }
                }
            } finally {
                this.lock.readLock().unlock();
            }
        } catch (Exception e) {
            log.error("getAllTopicList Exception", e);
        }

        return topicList.encode();
    }
//
    public byte[] getHasUnitSubTopicList() {
        TopicList topicList = new TopicList();
        try {
            try {
                this.lock.readLock().lockInterruptibly();
                Iterator<Entry<String, List<QueueData>>> topicTableIt =
                    this.topicQueueTable.entrySet().iterator();
                while (topicTableIt.hasNext()) {
                    Entry<String, List<QueueData>> topicEntry = topicTableIt.next();
                    String topic = topicEntry.getKey();
                    List<QueueData> queueDatas = topicEntry.getValue();
                    if (queueDatas != null && queueDatas.size() > 0
                        && TopicSysFlag.hasUnitSubFlag(queueDatas.get(0).getTopicSynFlag())) {
                        topicList.getTopicList().add(topic);
                    }
                }
            } finally {
                this.lock.readLock().unlock();
            }
        } catch (Exception e) {
            log.error("getAllTopicList Exception", e);
        }

        return topicList.encode();
    }
//
    public byte[] getHasUnitSubUnUnitTopicList() {
        TopicList topicList = new TopicList();
        try {
            try {
                this.lock.readLock().lockInterruptibly();
                Iterator<Entry<String, List<QueueData>>> topicTableIt =
                    this.topicQueueTable.entrySet().iterator();
                while (topicTableIt.hasNext()) {
                    Entry<String, List<QueueData>> topicEntry = topicTableIt.next();
                    String topic = topicEntry.getKey();
                    List<QueueData> queueDatas = topicEntry.getValue();
                    if (queueDatas != null && queueDatas.size() > 0
                        && !TopicSysFlag.hasUnitFlag(queueDatas.get(0).getTopicSynFlag())
                        && TopicSysFlag.hasUnitSubFlag(queueDatas.get(0).getTopicSynFlag())) {
                        topicList.getTopicList().add(topic);
                    }
                }
            } finally {
                this.lock.readLock().unlock();
            }
        } catch (Exception e) {
            log.error("getAllTopicList Exception", e);
        }

        return topicList.encode();
    }
}
//
class BrokerLiveInfo {
    private long lastUpdateTimestamp;
    private DataVersion dataVersion;
    private Channel channel;
    private String haServerAddr;

    public BrokerLiveInfo(long lastUpdateTimestamp, DataVersion dataVersion, Channel channel,
        String haServerAddr) {
        this.lastUpdateTimestamp = lastUpdateTimestamp;
        this.dataVersion = dataVersion;
        this.channel = channel;
        this.haServerAddr = haServerAddr;
    }

    public long getLastUpdateTimestamp() {
        return lastUpdateTimestamp;
    }

    public void setLastUpdateTimestamp(long lastUpdateTimestamp) {
        this.lastUpdateTimestamp = lastUpdateTimestamp;
    }

    public DataVersion getDataVersion() {
        return dataVersion;
    }

    public void setDataVersion(DataVersion dataVersion) {
        this.dataVersion = dataVersion;
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public String getHaServerAddr() {
        return haServerAddr;
    }

    public void setHaServerAddr(String haServerAddr) {
        this.haServerAddr = haServerAddr;
    }

    @Override
    public String toString() {
        return "BrokerLiveInfo [lastUpdateTimestamp=" + lastUpdateTimestamp + ", dataVersion=" + dataVersion
            + ", channel=" + channel + ", haServerAddr=" + haServerAddr + "]";
    }
}
