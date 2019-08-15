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

package org.apache.rocketmq.common.protocol.body;

import org.apache.rocketmq.common.UtilAll;
// 处理队列信息
public class ProcessQueueInfo {

//    提交的offset
    private long commitOffset;

//    缓存的消息最小的offset
    private long cachedMsgMinOffset;

//    缓存消息的最大的offset
    private long cachedMsgMaxOffset;

//    缓存的消息记录数
    private int cachedMsgCount;

//    缓存的消息大小
    private int cachedMsgSizeInMiB;

//    事务消息最小的offset
    private long transactionMsgMinOffset;
//    事务消息最大的offset
    private long transactionMsgMaxOffset;
//    事务消息大小
    private int transactionMsgCount;

    private boolean locked;
    private long tryUnlockTimes;
    private long lastLockTimestamp;

//    是否删除
    private boolean droped;
    private long lastPullTimestamp;
    private long lastConsumeTimestamp;

    public long getCommitOffset() {
        return commitOffset;
    }

    public void setCommitOffset(long commitOffset) {
        this.commitOffset = commitOffset;
    }

    public long getCachedMsgMinOffset() {
        return cachedMsgMinOffset;
    }

    public void setCachedMsgMinOffset(long cachedMsgMinOffset) {
        this.cachedMsgMinOffset = cachedMsgMinOffset;
    }

    public long getCachedMsgMaxOffset() {
        return cachedMsgMaxOffset;
    }

    public void setCachedMsgMaxOffset(long cachedMsgMaxOffset) {
        this.cachedMsgMaxOffset = cachedMsgMaxOffset;
    }

    public int getCachedMsgCount() {
        return cachedMsgCount;
    }

    public void setCachedMsgCount(int cachedMsgCount) {
        this.cachedMsgCount = cachedMsgCount;
    }

    public long getTransactionMsgMinOffset() {
        return transactionMsgMinOffset;
    }

    public void setTransactionMsgMinOffset(long transactionMsgMinOffset) {
        this.transactionMsgMinOffset = transactionMsgMinOffset;
    }

    public long getTransactionMsgMaxOffset() {
        return transactionMsgMaxOffset;
    }

    public void setTransactionMsgMaxOffset(long transactionMsgMaxOffset) {
        this.transactionMsgMaxOffset = transactionMsgMaxOffset;
    }

    public int getTransactionMsgCount() {
        return transactionMsgCount;
    }

    public void setTransactionMsgCount(int transactionMsgCount) {
        this.transactionMsgCount = transactionMsgCount;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public long getTryUnlockTimes() {
        return tryUnlockTimes;
    }

    public void setTryUnlockTimes(long tryUnlockTimes) {
        this.tryUnlockTimes = tryUnlockTimes;
    }

    public long getLastLockTimestamp() {
        return lastLockTimestamp;
    }

    public void setLastLockTimestamp(long lastLockTimestamp) {
        this.lastLockTimestamp = lastLockTimestamp;
    }

    public boolean isDroped() {
        return droped;
    }

    public void setDroped(boolean droped) {
        this.droped = droped;
    }

    public long getLastPullTimestamp() {
        return lastPullTimestamp;
    }

    public void setLastPullTimestamp(long lastPullTimestamp) {
        this.lastPullTimestamp = lastPullTimestamp;
    }

    public long getLastConsumeTimestamp() {
        return lastConsumeTimestamp;
    }

    public void setLastConsumeTimestamp(long lastConsumeTimestamp) {
        this.lastConsumeTimestamp = lastConsumeTimestamp;
    }

    public int getCachedMsgSizeInMiB() {
        return cachedMsgSizeInMiB;
    }

    public void setCachedMsgSizeInMiB(final int cachedMsgSizeInMiB) {
        this.cachedMsgSizeInMiB = cachedMsgSizeInMiB;
    }

    @Override
    public String toString() {
        return "ProcessQueueInfo [commitOffset=" + commitOffset + ", cachedMsgMinOffset="
            + cachedMsgMinOffset + ", cachedMsgMaxOffset=" + cachedMsgMaxOffset
            + ", cachedMsgCount=" + cachedMsgCount + ", cachedMsgSizeInMiB=" + cachedMsgSizeInMiB
            + ", transactionMsgMinOffset=" + transactionMsgMinOffset
            + ", transactionMsgMaxOffset=" + transactionMsgMaxOffset + ", transactionMsgCount="
            + transactionMsgCount + ", locked=" + locked + ", tryUnlockTimes=" + tryUnlockTimes
            + ", lastLockTimestamp=" + UtilAll.timeMillisToHumanString(lastLockTimestamp) + ", droped="
            + droped + ", lastPullTimestamp=" + UtilAll.timeMillisToHumanString(lastPullTimestamp)
            + ", lastConsumeTimestamp=" + UtilAll.timeMillisToHumanString(lastConsumeTimestamp) + "]";
    }
}
