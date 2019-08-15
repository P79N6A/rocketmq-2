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

import java.io.IOException;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
//
public abstract class ConfigManager {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.COMMON_LOGGER_NAME);

    public abstract String encode();
//
    public boolean load() {
        String fileName = null;
        try {
//            System.getProperty("user.home") + File.separator + "store" File.separator + "config" + File.separator + "topics.json" 获取topic配置文件
            fileName = this.configFilePath();
            String jsonString = MixAll.file2String(fileName);

            if (null == jsonString || jsonString.length() == 0) {
//                文件备份=》
                return this.loadBak();
            } else {
//                配置反序列化=》
                this.decode(jsonString);
                log.info("load " + fileName + " OK");
                return true;
            }
        } catch (Exception e) {
            log.error("load " + fileName + " failed, and try to load backup file", e);
            return this.loadBak();
        }
    }

    public abstract String configFilePath();
//
    private boolean loadBak() {
        String fileName = null;
        try {
            fileName = this.configFilePath();
//            从备份配置文件中读取配置
            String jsonString = MixAll.file2String(fileName + ".bak");
            if (jsonString != null && jsonString.length() > 0) {
//                配置反序列化=》
                this.decode(jsonString);
                log.info("load " + fileName + " OK");
                return true;
            }
        } catch (Exception e) {
            log.error("load " + fileName + " Failed", e);
            return false;
        }

        return true;
    }

    public abstract void decode(final String jsonString);
//
    public synchronized void persist() {
//        持久化的是json存储，序列化的时候按版本号维护的数据 =》
        String jsonString = this.encode(true);
        if (jsonString != null) {
//             user.home/store/config/topics.json 文件存储 =》
            String fileName = this.configFilePath();
            try {
//                保存文件 =》
                MixAll.string2File(jsonString, fileName);
            } catch (IOException e) {
                log.error("persist file " + fileName + " exception", e);
            }
        }
    }

    public abstract String encode(final boolean prettyFormat);
}
