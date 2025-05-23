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

package com.wgzhao.addax.rdbms.reader.util;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static com.wgzhao.addax.core.spi.ErrorCode.CONFIG_ERROR;
import static com.wgzhao.addax.core.spi.ErrorCode.EXECUTE_FAIL;
import static com.wgzhao.addax.core.spi.ErrorCode.REQUIRED_VALUE;

public final class OriginalConfPretreatmentUtil
{
    private static final Logger LOG = LoggerFactory.getLogger(OriginalConfPretreatmentUtil.class);

    private static final String EXCLUDE_COLUMN = "excludeColumn";

    /**
     * Handle the configuration before execution
     *
     * @param dataBaseType database type
     * @param originalConfig configuration
     */
    public static void doPretreatment(DataBaseType dataBaseType, Configuration originalConfig)
    {
        // The username is mandatory for RDBMS
        originalConfig.getNecessaryValue(Key.USERNAME, REQUIRED_VALUE);

        // Some RDBMS has no password in default, so the password is optional
        Optional.ofNullable(originalConfig.getString(Key.PASSWORD))
                .ifPresentOrElse(
                        password -> {
                            if (password.startsWith(Constant.ENC_PASSWORD_PREFIX)) {
                                // Encrypted password, need to decrypt
                                var decryptPassword = EncryptUtil.decrypt(
                                        password.substring(6, password.length() - 1));
                                originalConfig.set(Key.PASSWORD, decryptPassword);
                            }
                        },
                        () -> originalConfig.set(Key.PASSWORD, "")
                );

        dealWhere(originalConfig);
        simplifyConf(dataBaseType, originalConfig);
    }

    /**
     * Handle the where clause
     *
     * @param originalConfig configuration
     */
    public static void dealWhere(Configuration originalConfig)
    {
        Optional.ofNullable(originalConfig.getString(Key.WHERE))
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .map(where -> where.endsWith(";") ? where.substring(0, where.length() - 1) : where)
                .ifPresent(improvedWhere -> originalConfig.set(Key.WHERE, improvedWhere));
    }

    /**
     * Handle configuration preliminary:
     * 1. Handle the situation where multiple jdbcUrls are configured for the same database
     * 2. Identify and mark whether to use querySql mode or table mode
     * 3. For table mode, determine the number of sub-tables and process the column to * matters
     *
     * @param originalConfig configuration
     */
    private static void simplifyConf(DataBaseType dataBaseType, Configuration originalConfig)
    {
        boolean isTableMode = recognizeTableOrQuerySqlMode(originalConfig);
        originalConfig.set(Key.IS_TABLE_MODE, isTableMode);

        dealJdbcAndTable(dataBaseType, originalConfig);
        dealColumnConf(dataBaseType, originalConfig);
    }

    /**
     * Handle the jdbcUrl and table configuration
     *
     * @param dataBaseType database type
     * @param originalConfig configuration
     */
    private static void dealJdbcAndTable(DataBaseType dataBaseType, Configuration originalConfig)
    {
        String username = originalConfig.getString(Key.USERNAME);
        String password = originalConfig.getString(Key.PASSWORD);
        boolean isTableMode = originalConfig.getBool(Key.IS_TABLE_MODE);
        boolean isPreCheck = originalConfig.getBool(Key.DRY_RUN, false);

        Configuration connConf = originalConfig.getConfiguration(Key.CONNECTION);
        List<String> preSql = originalConfig.getList(Key.PRE_SQL, String.class);

        int tableNum = 0;

        // Set driver class if specified
        Optional.ofNullable(connConf.getString(Key.JDBC_DRIVER))
                .ifPresent(driverClass -> {
                    LOG.warn("Use specified driver class: {}", driverClass);
                    dataBaseType.setDriverClassName(driverClass);
                });

        connConf.getNecessaryValue(Key.JDBC_URL, REQUIRED_VALUE);

        String jdbcUrl = connConf.getString(Key.JDBC_URL);

        if (StringUtils.isBlank(jdbcUrl)) {
            throw AddaxException.asAddaxException(REQUIRED_VALUE, "The parameter [connection.jdbcUrl] is not set.");
        }

        jdbcUrl = dataBaseType.appendJDBCSuffixForReader(jdbcUrl);

        // Validate JDBC URL with or without retry based on preCheck flag
        if (isPreCheck) {
            DBUtil.validJdbcUrlWithoutRetry(dataBaseType, jdbcUrl, username, password, preSql);
        }
        else {
            DBUtil.validJdbcUrl(dataBaseType, jdbcUrl, username, password, preSql);
        }

        // Write back the connection.jdbcUrl item
        originalConfig.set(Key.CONNECTION + "." + Key.JDBC_URL, jdbcUrl);

        if (isTableMode) {
            List<String> tables = connConf.getList(Key.TABLE, String.class);

            List<String> expandedTables = TableExpandUtil.expandTableConf(dataBaseType, tables);

            if (expandedTables.isEmpty()) {
                throw AddaxException.asAddaxException(EXECUTE_FAIL, "Failed to obtain the table " + StringUtils.join(tables, ","));
            }

            tableNum += expandedTables.size();
            originalConfig.set(Key.CONNECTION + "." + Key.TABLE, expandedTables);
        }

        originalConfig.set(Key.TABLE_NUMBER, tableNum);
    }

    /**
     * Handle the column configuration
     *
     * @param originalConfig configuration
     */
    private static void dealColumnConf(DataBaseType dataBaseType, Configuration originalConfig)
    {
        boolean isTableMode = originalConfig.getBool(Key.IS_TABLE_MODE);

        List<String> userConfiguredColumns = originalConfig.getList(Key.COLUMN, String.class);

        if (isTableMode) {
            if (userConfiguredColumns == null || userConfiguredColumns.isEmpty()) {
                throw AddaxException.asAddaxException(REQUIRED_VALUE, "The item column is required.");
            }

            String jdbcUrl = originalConfig.getString(Key.CONNECTION + "." + Key.JDBC_URL);
            String username = originalConfig.getString(Key.USERNAME);
            String password = originalConfig.getString(Key.PASSWORD);
            String tableName = originalConfig.getString(Key.CONNECTION + "." + Key.TABLE + "[0]");

            // Handle special case with '*' column
            if (userConfiguredColumns.size() == 1 && "*".equals(userConfiguredColumns.get(0))) {
                LOG.warn("""
                        There are some risks in the column configuration. Because you did not configure the columns \
                        to read the database table, changes in the number and types of fields in your table may affect \
                        the correctness of the task or even cause errors.""");

                var excludeColumns = originalConfig.getList(EXCLUDE_COLUMN, String.class);

                if (!excludeColumns.isEmpty()) {
                    // Get the all columns of table and exclude the excludeColumns
                    List<String> allColumns = DBUtil.getTableColumns(dataBaseType, jdbcUrl, username, password, tableName);
                    // warn: does it need to judge the table column is case-insensitive?
                    allColumns.removeAll(excludeColumns);
                    originalConfig.set(Key.COLUMN_LIST, allColumns);

                    // Each column in allColumns should be quoted with ``
                    var quotedColumns = allColumns.stream()
                            .map(dataBaseType::quoteColumnName)
                            .toList();

                    originalConfig.set(Key.COLUMN, String.join(",", quotedColumns));
                } else {
                    originalConfig.set(Key.COLUMN, "*");
                }
            }
            else {
                final List<String> allColumns = ListUtil.valueToLowerCase(DBUtil.getTableColumns(dataBaseType, jdbcUrl, username, password, tableName));
                LOG.info("The table [{}] has columns [{}].", tableName, StringUtils.join(allColumns, ","));

                // Quote all user configured columns
                var quotedColumns = userConfiguredColumns.stream()
                        .peek(column -> {
                            if ("*".equals(column)) {
                                throw AddaxException.asAddaxException(CONFIG_ERROR,
                                        "The item column your configured is invalid, because it includes multiply asterisk('*').");
                            }
                        })
                        .map(dataBaseType::quoteColumnName)
                        .toList();

                originalConfig.set(Key.COLUMN_LIST, quotedColumns);
                originalConfig.set(Key.COLUMN, String.join(",", quotedColumns));

                // Validate splitPk exists in the table
                Optional.ofNullable(originalConfig.getString(Key.SPLIT_PK))
                        .filter(StringUtils::isNotBlank)
                        .ifPresent(splitPk -> {
                            if (!allColumns.contains(splitPk.toLowerCase())) {
                                throw AddaxException.asAddaxException(CONFIG_ERROR,
                                        "The table " + tableName + " has not the primary key " + splitPk);
                            }
                        });
            }
        } else {
            // Column is not allowed in querySql mode
            if (userConfiguredColumns != null && !userConfiguredColumns.isEmpty()) {
                LOG.warn("You configured both column and querySql, querySql will be preferred.");
                originalConfig.remove(Key.COLUMN);
            }

            // Where is not allowed in querySql mode
            Optional.ofNullable(originalConfig.getString(Key.WHERE))
                    .filter(StringUtils::isNotBlank)
                    .ifPresent(where -> {
                        LOG.warn("You configured both querySql and where. The where will be ignored.");
                        originalConfig.remove(Key.WHERE);
                    });

            // SplitPk is not allowed in querySql mode
            Optional.ofNullable(originalConfig.getString(Key.SPLIT_PK))
                    .filter(StringUtils::isNotBlank)
                    .ifPresent(splitPk -> {
                        LOG.warn("You configured both querySql and splitPk. The splitPk will be ignored.");
                        originalConfig.remove(Key.SPLIT_PK);
                    });
        }
    }

    /**
     * Identify and mark whether to use querySql mode or table mode
     *
     * @param originalConfig configuration
     * @return true if table mode, false if querySql mode
     */
    private static boolean recognizeTableOrQuerySqlMode(Configuration originalConfig)
    {
        var connConf = originalConfig.getConfiguration(Key.CONNECTION);
        var table = connConf.getString(Key.TABLE);
        var querySql = connConf.getString(Key.QUERY_SQL);

        var isTableMode = StringUtils.isNotBlank(table);
        var isQuerySqlMode = StringUtils.isNotBlank(querySql);

        Predicate<Boolean> neitherConfigured = mode -> !mode;
        Predicate<Boolean> bothConfigured = mode -> mode;

        if (neitherConfigured.test(isTableMode) && neitherConfigured.test(isQuerySqlMode)) {
            throw AddaxException.asAddaxException(
                    REQUIRED_VALUE, "You must configure either table or querySql.");
        } else if (bothConfigured.test(isTableMode) && bothConfigured.test(isQuerySqlMode)) {
            throw AddaxException.asAddaxException(CONFIG_ERROR,
                    "You cannot configure both table and querySql at the same time.");
        }

        return isTableMode;
    }
}
