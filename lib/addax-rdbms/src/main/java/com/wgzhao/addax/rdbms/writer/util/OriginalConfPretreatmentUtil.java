/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one
 *  * or more contributor license agreements.  See the NOTICE file
 *  * distributed with this work for additional information
 *  * regarding copyright ownership.  The ASF licenses this file
 *  * to you under the Apache License, Version 2.0 (the
 *  * "License"); you may not use this file except in compliance
 *  * with the License.  You may obtain a copy of the License at
 *  *
 *  *   http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing,
 *  * software distributed under the License is distributed on an
 *  * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  * KIND, either express or implied.  See the License for the
 *  * specific language governing permissions and limitations
 *  * under the License.
 *
 */

package com.wgzhao.addax.rdbms.writer.util;

import com.wgzhao.addax.core.base.Constant;
import com.wgzhao.addax.core.base.Key;
import com.wgzhao.addax.core.exception.AddaxException;
import com.wgzhao.addax.core.util.Configuration;
import com.wgzhao.addax.core.util.EncryptUtil;
import com.wgzhao.addax.core.util.ListUtil;
import com.wgzhao.addax.rdbms.util.DBUtil;
import com.wgzhao.addax.rdbms.util.DataBaseType;
import com.wgzhao.addax.rdbms.util.TableExpandUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static com.wgzhao.addax.core.spi.ErrorCode.CONFIG_ERROR;
import static com.wgzhao.addax.core.spi.ErrorCode.REQUIRED_VALUE;

/**
 * Utility class for preprocessing writer configuration before execution.
 * Handles password decryption, column validation, write mode processing, and configuration optimization.
 */
public final class OriginalConfPretreatmentUtil
{
    private static final Logger LOG = LoggerFactory.getLogger(OriginalConfPretreatmentUtil.class);
    // The database type used for processing configurations
    public static DataBaseType dataBaseType;
    private static final String jdbcUrlPath = Key.CONNECTION + "." + Key.JDBC_URL;

    private OriginalConfPretreatmentUtil()
    {
        // Private constructor to prevent instantiation
    }

    /**
     * Performs comprehensive preprocessing of writer configuration.
     * Validates credentials, handles encryption, and prepares configuration for execution.
     *
     * @param originalConfig The configuration to preprocess (modified in-place)
     * @param dataBaseType The database type for type-specific processing
     */
    public static void doPretreatment(Configuration originalConfig, DataBaseType dataBaseType)
    {
        originalConfig.getNecessaryValue(Key.USERNAME, REQUIRED_VALUE);
        String pass = originalConfig.getString(Key.PASSWORD, null);
        if (pass != null && pass.startsWith(Constant.ENC_PASSWORD_PREFIX)) {
            // Encrypted password, need to decrypt
            String decryptPassword = EncryptUtil.decrypt(
                    pass.substring(Constant.ENC_PASSWORD_PREFIX.length(), pass.length() - 1)
            );
            originalConfig.set(Key.PASSWORD, decryptPassword);
        }

        doCheckBatchSize(originalConfig);
        simplifyConf(originalConfig);
        dealColumnConf(originalConfig);
        dealWriteMode(originalConfig, dataBaseType);
    }

    /**
     * Validates and normalizes the batch size configuration.
     * Ensures batch size is within acceptable range for optimal performance.
     *
     * @param originalConfig The configuration to validate
     */
    public static void doCheckBatchSize(Configuration originalConfig)
    {
        int batchSize = originalConfig.getInt(Key.BATCH_SIZE, Constant.DEFAULT_BATCH_SIZE);
        if (batchSize < 1) {
            throw AddaxException.illegalConfigValue(Key.BATCH_SIZE, batchSize, " must be greater than 1. recommended value range is [100,1000].");
        }

        originalConfig.set(Key.BATCH_SIZE, batchSize);
    }

    /**
     * Simplifies and validates connection configuration.
     * Processes JDBC URL, driver settings, and table expansion.
     *
     * @param originalConfig The configuration to simplify
     */
    public static void simplifyConf(Configuration originalConfig)
    {
        Configuration connConf = originalConfig.getConfiguration(Key.CONNECTION);

        String driverClass = connConf.getString(Key.JDBC_DRIVER, null);
        if (driverClass != null && !driverClass.isEmpty()) {
            LOG.warn("Use specified driver class [{}]", driverClass);
            dataBaseType.setDriverClassName(driverClass);
        }
        String jdbcUrl = connConf.getString(Key.JDBC_URL);
        if (StringUtils.isBlank(jdbcUrl)) {
            throw AddaxException.missingConfig(Key.JDBC_URL);
        }

        jdbcUrl = dataBaseType.appendJDBCSuffixForWriter(jdbcUrl);
        originalConfig.set(jdbcUrlPath, jdbcUrl);

        List<String> tables = connConf.getList(Key.TABLE, String.class);

        if (null == tables || tables.isEmpty()) {
            throw AddaxException.missingConfig(Key.TABLE);
        }

        List<String> expandedTables = TableExpandUtil.expandTableConf(dataBaseType, tables);

        if (expandedTables.isEmpty()) {
            throw AddaxException.missingConfig(Key.TABLE);
        }

        originalConfig.set(Key.CONNECTION + "." + Key.TABLE, expandedTables);

        originalConfig.set(Key.TABLE_NUMBER, expandedTables.size());
    }

    /**
     * Validates and processes column configuration.
     * Handles column expansion, validation against table schema, and duplicate checking.
     *
     * @param originalConfig The configuration containing column settings
     */
    public static void dealColumnConf(Configuration originalConfig)
    {
        String jdbcUrl = originalConfig.getString(jdbcUrlPath);
        String username = originalConfig.getString(Key.USERNAME);
        String password = originalConfig.getString(Key.PASSWORD);
        String oneTable = originalConfig.getString(Key.CONNECTION + "." + Key.TABLE + "[0]");

        List<String> userConfiguredColumns = originalConfig.getList(Key.COLUMN, String.class);
        if (null == userConfiguredColumns || userConfiguredColumns.isEmpty()) {
            throw AddaxException.illegalConfigValue(Key.COLUMN, userConfiguredColumns);
        }
        else {
            List<String> allColumns;
            Connection connection = DBUtil.getConnectionWithoutRetry(dataBaseType, jdbcUrl, username, password);

            allColumns = DBUtil.getTableColumnsByConn(connection, oneTable, dataBaseType);

            LOG.info("The table [{}] has columns [{}].", oneTable, StringUtils.join(allColumns, ","));

            if (1 == userConfiguredColumns.size() && "*".equals(userConfiguredColumns.get(0))) {
                LOG.warn("There are some risks in the column configuration. Because you did not configure the columns " +
                        "to read the database table, changes in the number and types of fields in your table may affect " +
                        "the correctness of the task or even cause errors.");

                originalConfig.set(Key.COLUMN, allColumns);
            }
            else if (userConfiguredColumns.size() > allColumns.size()) {
                throw AddaxException.asAddaxException(CONFIG_ERROR,
                        "The number of columns your configured " + userConfiguredColumns.size()
                                + "are greater than the number of table columns " + allColumns.size());
            }
            else {
                // Ensure the column is not duplicated
                ListUtil.makeSureNoValueDuplicate(userConfiguredColumns, false);
                try {
                    // Check whether the user's configured columns exist in the table
                    DBUtil.getColumnMetaData(connection, oneTable, StringUtils.join(userConfiguredColumns, ","));
                }
                finally {
                    DBUtil.closeDBResources(null, null, connection);
                }
            }
        }
    }

    /**
     * Processes write mode configuration and generates appropriate SQL templates.
     * Supports insert, update, and upsert modes based on database capabilities.
     *
     * @param originalConfig The configuration containing write mode settings
     * @param dataBaseType The database type for generating appropriate SQL templates
     */
    public static void dealWriteMode(Configuration originalConfig, DataBaseType dataBaseType)
    {
        List<String> columns = originalConfig.getList(Key.COLUMN, String.class);
        String writeMode = originalConfig.getString(Key.WRITE_MODE, "INSERT");
        List<String> valueHolders = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            valueHolders.add("?");
        }

        String writeDataSqlTemplate = WriterUtil.getWriteTemplate(columns, valueHolders, writeMode, dataBaseType, false);
        LOG.info("Writing data using [{}].", writeDataSqlTemplate);
        originalConfig.set(Constant.INSERT_OR_REPLACE_TEMPLATE_MARK, writeDataSqlTemplate);
    }
}
