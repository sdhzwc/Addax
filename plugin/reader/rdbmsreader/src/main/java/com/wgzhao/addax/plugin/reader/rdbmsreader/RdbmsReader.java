/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.wgzhao.addax.plugin.reader.rdbmsreader;

import com.wgzhao.addax.core.base.Key;
import com.wgzhao.addax.core.exception.AddaxException;
import com.wgzhao.addax.core.plugin.RecordSender;
import com.wgzhao.addax.core.spi.Reader;
import com.wgzhao.addax.core.util.Configuration;
import com.wgzhao.addax.rdbms.reader.CommonRdbmsReader;
import com.wgzhao.addax.rdbms.util.DataBaseType;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;

import static com.wgzhao.addax.core.base.Constant.DEFAULT_FETCH_SIZE;
import static com.wgzhao.addax.core.base.Key.CONNECTION;
import static com.wgzhao.addax.core.base.Key.FETCH_SIZE;
import static com.wgzhao.addax.core.base.Key.JDBC_DRIVER;
import static com.wgzhao.addax.core.spi.ErrorCode.REQUIRED_VALUE;

public class RdbmsReader
        extends Reader
{
    private static final DataBaseType DATABASE_TYPE = DataBaseType.RDBMS_READER;

    public static class Job
            extends Reader.Job
    {

        private Configuration originalConfig;
        private CommonRdbmsReader.Job commonRdbmsReaderMaster;

        @Override
        public void init()
        {
            this.originalConfig = getPluginJobConf();
            int fetchSize = this.originalConfig.getInt(FETCH_SIZE, DEFAULT_FETCH_SIZE);
            if (fetchSize < 1) {
                throw AddaxException.asAddaxException(
                        REQUIRED_VALUE, "The fetchSize can not be less than 1");
            }
            this.originalConfig.set(FETCH_SIZE, fetchSize);
            Configuration connection = this.originalConfig.getConfiguration(CONNECTION);
            if (connection == null) {
                throw AddaxException.asAddaxException(REQUIRED_VALUE, "The config 'connection' is required and must not be empty");
            }
            String jdbcDriver = this.originalConfig.getString(JDBC_DRIVER, null);
            if (jdbcDriver == null || StringUtils.isBlank(jdbcDriver)) {
                // guess jdbc driver name from jdbc url if not set
                final String jdbcType = connection.getString(Key.JDBC_URL).split(":")[1];
                Arrays.stream(DataBaseType.values()).filter(
                        dataBaseType -> dataBaseType.getTypeName().equals(jdbcType)).findFirst().ifPresent(dataBaseType ->
                        DATABASE_TYPE.setDriverClassName(dataBaseType.getDriverClassName()));
            }
            else {
                // use custom jdbc driver
                DATABASE_TYPE.setDriverClassName(jdbcDriver);
            }
            this.commonRdbmsReaderMaster = new SubCommonRdbmsReader.Job(DATABASE_TYPE);
            this.originalConfig = this.commonRdbmsReaderMaster.init(this.originalConfig);
        }

        @Override
        public List<Configuration> split(int adviceNumber)
        {
            return this.commonRdbmsReaderMaster.split(this.originalConfig, adviceNumber);
        }

        @Override
        public void post()
        {
            this.commonRdbmsReaderMaster.post(this.originalConfig);
        }

        @Override
        public void destroy()
        {
            this.commonRdbmsReaderMaster.destroy(this.originalConfig);
        }
    }

    public static class Task
            extends Reader.Task
    {

        private Configuration readerSliceConfig;
        private CommonRdbmsReader.Task commonRdbmsReaderSlave;

        @Override
        public void init()
        {
            this.readerSliceConfig = getPluginJobConf();
            this.commonRdbmsReaderSlave = new SubCommonRdbmsReader.Task(DATABASE_TYPE);
            this.commonRdbmsReaderSlave.init(this.readerSliceConfig);
        }

        @Override
        public void startRead(RecordSender recordSender)
        {
            int fetchSize = this.readerSliceConfig.getInt(FETCH_SIZE);

            this.commonRdbmsReaderSlave.startRead(this.readerSliceConfig, recordSender, getTaskPluginCollector(), fetchSize);
        }

        @Override
        public void post()
        {
            this.commonRdbmsReaderSlave.post(this.readerSliceConfig);
        }

        @Override
        public void destroy()
        {
            this.commonRdbmsReaderSlave.destroy(this.readerSliceConfig);
        }
    }
}
