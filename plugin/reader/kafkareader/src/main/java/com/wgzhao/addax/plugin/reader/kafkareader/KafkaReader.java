/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package com.wgzhao.addax.plugin.reader.kafkareader;

import com.alibaba.fastjson2.JSONObject;
import com.wgzhao.addax.core.element.Column;
import com.wgzhao.addax.core.element.DateColumn;
import com.wgzhao.addax.core.element.DoubleColumn;
import com.wgzhao.addax.core.element.LongColumn;
import com.wgzhao.addax.core.element.Record;
import com.wgzhao.addax.core.element.StringColumn;
import com.wgzhao.addax.core.exception.AddaxException;
import com.wgzhao.addax.core.plugin.RecordSender;
import com.wgzhao.addax.core.spi.Reader;
import com.wgzhao.addax.core.util.Configuration;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static com.wgzhao.addax.core.spi.ErrorCode.CONFIG_ERROR;
import static com.wgzhao.addax.core.spi.ErrorCode.REQUIRED_VALUE;

public class KafkaReader
        extends Reader
{
    public static class Job
            extends Reader.Job
    {
        private Configuration conf = null;

        @Override
        public void init()
        {
            this.conf = getPluginJobConf();
            conf.getNecessaryValue(KafkaKey.BROKER_LIST, REQUIRED_VALUE);
            conf.getNecessaryValue(KafkaKey.TOPIC, REQUIRED_VALUE);
        }

        @Override
        public void destroy()
        {

        }

        @Override
        public List<Configuration> split(int adviceNumber)
        {
            // only one split
            return Collections.singletonList(conf.clone());
        }
    }

    public static class Task
            extends Reader.Task
    {
        private final static Logger logger = LoggerFactory.getLogger(Task.class);
        private final static String GROUP_ID = "addax-kafka-grp";
        private final static String CLIENT_ID = "addax-kafka-reader";

        Configuration configuration;
        KafkaConsumer<String, Object> kafkaConsumer;
        private List<String> columns;
        private String missKeyValue;
        private long maxMessageNumber;

        @Override
        public void init()
        {
            this.configuration = getPluginJobConf();
            Properties properties = new Properties();
            String brokeLists = configuration.getString(KafkaKey.BROKER_LIST);
            String topic = configuration.getString(KafkaKey.TOPIC);
            this.columns = configuration.getList(KafkaKey.COLUMN, String.class);
            this.missKeyValue = configuration.getString(KafkaKey.MISSING_KEY_VALUE, null);
            this.maxMessageNumber = configuration.getLong(KafkaKey.MAX_MESSAGE_NUMBER, Long.MAX_VALUE);
            properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokeLists);
            properties.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID + "-" + RandomStringUtils.insecure().nextAlphanumeric(5));
            properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            properties.put(ConsumerConfig.CLIENT_ID_CONFIG, CLIENT_ID);
            Map<String, Object> custConf = this.configuration.getMap(KafkaKey.PROPERTIES);
            if (custConf != null && !custConf.isEmpty()) {
                properties.putAll(custConf);
            }
            this.kafkaConsumer = new KafkaConsumer<>(properties);
            kafkaConsumer.subscribe(Collections.singletonList(topic));
        }

        @Override
        public void destroy()
        {
            kafkaConsumer.close();
        }

        @Override
        public void startRead(RecordSender recordSender)
        {
            while (maxMessageNumber > 0) {
                ConsumerRecords<String, Object> items = kafkaConsumer.poll(Duration.ofSeconds(2));
                sendData(items, recordSender);
                recordSender.flush();
                maxMessageNumber -= items.count();
            }
        }

        private Column guessColumnType(Object obj)
        {
            if (obj instanceof Long) {
                return new LongColumn((Long) obj);
            }
            if (obj instanceof Date) {
                return new DateColumn((Date) obj);
            }
            if (obj instanceof Double) {
                return new DoubleColumn((Double) obj);
            }
            return new StringColumn(obj.toString());
        }

        private void sendData(ConsumerRecords<String, Object> items, RecordSender recordSender)
        {
            for (ConsumerRecord<String, Object> item : items) {
                Record record = recordSender.createRecord();
                logger.debug("topic = {}, partition = {}, offset = {}, kafkaConsumer = {}, country = {}%n",
                        item.topic(), item.partition(), item.offset(),
                        item.key(), item.value());
                final JSONObject jsonObject = JSONObject.parseObject(item.value().toString());
                if (columns.size() == 1 && "*".equals(columns.get(0))) {
                    //assume all json value type is string
                    for (String key : jsonObject.keySet()) {
                        record.addColumn(new StringColumn(jsonObject.getString(key)));
                    }
                }
                else {
                    for (String col : columns) {
                        if (!jsonObject.containsKey(col)) {
                            if (this.missKeyValue == null) {
                                throw AddaxException.asAddaxException(CONFIG_ERROR,
                                        "The column " + col + " not exists");
                            }
                            record.addColumn(new StringColumn(this.missKeyValue));
                        }
                        else {
                            record.addColumn(guessColumnType(jsonObject.get(col)));
                        }
                    }
                }
                recordSender.sendToWriter(record);
            }
        }
    }
}
