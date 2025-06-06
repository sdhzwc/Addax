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

package com.wgzhao.addax.plugin.reader.oraclereader;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wgzhao.addax.core.base.Key;
import com.wgzhao.addax.core.element.Column;
import com.wgzhao.addax.core.element.StringColumn;
import com.wgzhao.addax.core.exception.AddaxException;
import com.wgzhao.addax.core.plugin.RecordSender;
import com.wgzhao.addax.core.spi.Reader;
import com.wgzhao.addax.core.util.Configuration;
import com.wgzhao.addax.rdbms.reader.CommonRdbmsReader;
import com.wgzhao.addax.rdbms.reader.util.HintUtil;
import com.wgzhao.addax.rdbms.util.DataBaseType;
import oracle.spatial.geometry.JGeometry;
import org.apache.commons.lang3.StringUtils;

import java.io.UnsupportedEncodingException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import static com.wgzhao.addax.core.base.Constant.DEFAULT_FETCH_SIZE;
import static com.wgzhao.addax.core.base.Key.FETCH_SIZE;
import static com.wgzhao.addax.core.base.Key.IS_TABLE_MODE;
import static com.wgzhao.addax.core.spi.ErrorCode.CONFIG_ERROR;
import static com.wgzhao.addax.core.spi.ErrorCode.ILLEGAL_VALUE;

public class OracleReader
        extends Reader
{

    private static final DataBaseType DATABASE_TYPE = DataBaseType.Oracle;

    public static class Job
            extends Reader.Job
    {
        private Configuration originalConfig = null;
        private CommonRdbmsReader.Job commonRdbmsReaderJob;

        @Override
        public void init()
        {
            this.originalConfig = super.getPluginJobConf();

            dealFetchSize(this.originalConfig);

            this.commonRdbmsReaderJob = new CommonRdbmsReader.Job(DATABASE_TYPE);
            this.originalConfig = this.commonRdbmsReaderJob.init(this.originalConfig);
            dealHint(this.originalConfig);
        }

        @Override
        public void preCheck()
        {
            this.commonRdbmsReaderJob.preCheck(this.originalConfig, DATABASE_TYPE);
        }

        @Override
        public List<Configuration> split(int adviceNumber)
        {
            return this.commonRdbmsReaderJob.split(this.originalConfig, adviceNumber);
        }

        @Override
        public void post()
        {
            this.commonRdbmsReaderJob.post(this.originalConfig);
        }

        @Override
        public void destroy()
        {
            this.commonRdbmsReaderJob.destroy(this.originalConfig);
        }

        private void dealFetchSize(Configuration originalConfig)
        {
            int fetchSize = originalConfig.getInt(FETCH_SIZE, DEFAULT_FETCH_SIZE);
            if (fetchSize < 1) {
                throw AddaxException.asAddaxException(ILLEGAL_VALUE,
                        "The value of fetchSize [" + fetchSize + "] in OracleReader can not be less than 1.");
            }
            originalConfig.set(FETCH_SIZE, fetchSize);
        }

        private void dealHint(Configuration originalConfig)
        {
            String hint = originalConfig.getString(Key.HINT);
            if (StringUtils.isNotBlank(hint)) {
                boolean isTableMode = originalConfig.getBool(IS_TABLE_MODE);
                if (!isTableMode) {
                    throw AddaxException.asAddaxException(CONFIG_ERROR,
                            "Only querySql mode can configure HINT, please set isTableMode to false.");
                }
                HintUtil.initHintConf(DATABASE_TYPE, originalConfig);
            }
        }
    }

    public static class Task
            extends Reader.Task
    {

        private Configuration readerSliceConfig;
        private CommonRdbmsReader.Task commonRdbmsReaderTask;

        @Override
        public void init()
        {
            this.readerSliceConfig = getPluginJobConf();
            this.commonRdbmsReaderTask = new CommonRdbmsReader.Task(DATABASE_TYPE, getTaskGroupId(), getTaskId())
            {
                @Override
                protected Column createColumn(ResultSet rs, ResultSetMetaData metaData, int i)
                        throws SQLException, UnsupportedEncodingException
                {
                    int dataType = metaData.getColumnType(i);
                    if (dataType == Types.STRUCT) {
                        try {
                            JGeometry geom = JGeometry.load(rs.getBytes(i));
                            return new StringColumn(convertGeometryToJson(geom));
                        }
                        catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                    else {
                        return super.createColumn(rs, metaData, i);
                    }
                }
            };
            this.commonRdbmsReaderTask.init(this.readerSliceConfig);
        }

        @Override
        public void startRead(RecordSender recordSender)
        {
            int fetchSize = this.readerSliceConfig.getInt(FETCH_SIZE);

            this.commonRdbmsReaderTask.startRead(this.readerSliceConfig, recordSender, getTaskPluginCollector(), fetchSize);
        }

        @Override
        public void post()
        {
            this.commonRdbmsReaderTask.post(this.readerSliceConfig);
        }

        @Override
        public void destroy()
        {
            this.commonRdbmsReaderTask.destroy(this.readerSliceConfig);
        }

        private String convertGeometryToJson(JGeometry geometry)
        {
            // Get the type of the geometry object
            JSONArray result = new JSONArray();
            JGeometry[] geoElems = geometry.getElements();
            for (JGeometry geoElem : geoElems) {
                JSONObject json = new JSONObject();
                json.put("sdo_gtype", geoElem.getType() + 2000);
                json.put("sdo_srid", geoElem.getSRID());
                double[] points = geoElem.getLabelPointXYZ();
                JSONObject pointJson = new JSONObject();
                pointJson.put("x", points[0]);
                pointJson.put("y", points[1]);
                pointJson.put("z", points[2]);
                json.put("sdo_point", pointJson);
                json.put("sdo_elem_info", geoElem.getElemInfo());
                json.put("sdo_ordinates", geoElem.getOrdinatesArray());
                result.add(json);
            }
            // Return the JSON string
            return result.toString();
        }
    }
}
