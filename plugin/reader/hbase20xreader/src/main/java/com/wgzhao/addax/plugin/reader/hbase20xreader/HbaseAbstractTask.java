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

package com.wgzhao.addax.plugin.reader.hbase20xreader;

import com.wgzhao.addax.common.base.HBaseConstant;
import com.wgzhao.addax.common.base.HBaseKey;
import com.wgzhao.addax.common.element.BoolColumn;
import com.wgzhao.addax.common.element.Column;
import com.wgzhao.addax.common.element.DateColumn;
import com.wgzhao.addax.common.element.DoubleColumn;
import com.wgzhao.addax.common.element.LongColumn;
import com.wgzhao.addax.common.element.StringColumn;
import com.wgzhao.addax.common.element.Record;
import com.wgzhao.addax.common.exception.AddaxException;
import com.wgzhao.addax.common.util.Configuration;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.wgzhao.addax.common.spi.ErrorCode.ILLEGAL_VALUE;
import static com.wgzhao.addax.common.spi.ErrorCode.NOT_SUPPORT_TYPE;

public abstract class HbaseAbstractTask
{
    private final static Logger LOG = LoggerFactory.getLogger(HbaseAbstractTask.class);

    private final byte[] startKey;
    private final byte[] endKey;

    protected Table htable;
    protected String encoding;
    protected int scanCacheSize;
    protected int scanBatchSize;

    protected Result lastResult = null;
    protected Scan scan;
    protected ResultScanner resultScanner;

    public HbaseAbstractTask(Configuration configuration)
    {

        this.htable = Hbase20xHelper.getTable(configuration);

        this.encoding = configuration.getString(HBaseKey.ENCODING, HBaseConstant.DEFAULT_ENCODING);
        this.startKey = Hbase20xHelper.convertInnerStartRowkey(configuration);
        this.endKey = Hbase20xHelper.convertInnerEndRowkey(configuration);
        this.scanCacheSize = configuration.getInt(HBaseKey.SCAN_CACHE_SIZE, HBaseConstant.DEFAULT_SCAN_CACHE_SIZE);
        this.scanBatchSize = configuration.getInt(HBaseKey.SCAN_BATCH_SIZE, HBaseConstant.DEFAULT_SCAN_BATCH_SIZE);
    }

    public abstract boolean fetchLine(Record record)
            throws Exception;

    //不同模式设置不同,如多版本模式需要设置版本
    public abstract void initScan(Scan scan);

    public void prepare()
            throws Exception
    {
        this.scan = new Scan();
        this.scan.setSmall(false);
        this.scan.withStartRow(startKey);
        this.scan.withStopRow(endKey);
        LOG.info("The task set startRowkey=[{}], endRowkey=[{}].", Bytes.toStringBinary(this.startKey), Bytes.toStringBinary(this.endKey));
        this.scan.setCaching(this.scanCacheSize);
        this.scan.setBatch(this.scanBatchSize);
        this.scan.setCacheBlocks(false);
        initScan(this.scan);

        this.resultScanner = this.htable.getScanner(this.scan);
    }

    public void close()
    {
        Hbase20xHelper.closeResultScanner(this.resultScanner);
        Hbase20xHelper.closeTable(this.htable);
    }

    protected Result getNextHbaseRow()
            throws IOException
    {
        Result result;
        try {
            result = resultScanner.next();
        }
        catch (IOException e) {
            if (lastResult != null) {
                this.scan.withStopRow(lastResult.getRow());
            }
            resultScanner = this.htable.getScanner(scan);
            result = resultScanner.next();
            if (lastResult != null && Bytes.equals(lastResult.getRow(), result.getRow())) {
                result = resultScanner.next();
            }
        }
        lastResult = result;
        // may be null
        return result;
    }

    public Column convertBytesToAssignType(ColumnType columnType, byte[] byteArray, String dateformat)
            throws Exception
    {
        Column column;
        switch (columnType) {
            case BOOLEAN:
                column = new BoolColumn(ArrayUtils.isEmpty(byteArray) ? null : Bytes.toBoolean(byteArray));
                break;
            case SHORT:
                column = new LongColumn(ArrayUtils.isEmpty(byteArray) ? null : String.valueOf(Bytes.toShort(byteArray)));
                break;
            case INT:
                column = new LongColumn(ArrayUtils.isEmpty(byteArray) ? null : Bytes.toInt(byteArray));
                break;
            case LONG:
                column = new LongColumn(ArrayUtils.isEmpty(byteArray) ? null : Bytes.toLong(byteArray));
                break;
            case FLOAT:
                column = new DoubleColumn(ArrayUtils.isEmpty(byteArray) ? null : Bytes.toFloat(byteArray));
                break;
            case DOUBLE:
                column = new DoubleColumn(ArrayUtils.isEmpty(byteArray) ? null : Bytes.toDouble(byteArray));
                break;
            case STRING:
                column = new StringColumn(ArrayUtils.isEmpty(byteArray) ? null : new String(byteArray, encoding));
                break;
            case BINARY_STRING:
                column = new StringColumn(ArrayUtils.isEmpty(byteArray) ? null : Bytes.toStringBinary(byteArray));
                break;
            case DATE:
                String dateValue = Bytes.toStringBinary(byteArray);
                column = new DateColumn(ArrayUtils.isEmpty(byteArray) ? null : DateUtils.parseDate(dateValue, dateformat));
                break;
            default:
                throw AddaxException.asAddaxException(ILLEGAL_VALUE, "The column type '" + columnType + "' is not supported");
        }
        return column;
    }

    public Column convertValueToAssignType(ColumnType columnType, String constantValue, String dateformat)
            throws Exception
    {
        Column column;
        switch (columnType) {
            case BOOLEAN:
                column = new BoolColumn(constantValue);
                break;
            case SHORT:
            case INT:
            case LONG:
                column = new LongColumn(constantValue);
                break;
            case FLOAT:
            case DOUBLE:
                column = new DoubleColumn(constantValue);
                break;
            case STRING:
                column = new StringColumn(constantValue);
                break;
            case DATE:
                column = new DateColumn(DateUtils.parseDate(constantValue, dateformat));
                break;
            default:
                throw AddaxException.asAddaxException(NOT_SUPPORT_TYPE, "The column type '" + columnType + "' is not supported");
        }
        return column;
    }
}
