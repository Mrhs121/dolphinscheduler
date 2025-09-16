/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.plugin.datasource.api.utils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.alibaba.druid.sql.parser.Lexer;
import com.alibaba.druid.sql.parser.ParserException;
import com.alibaba.druid.sql.parser.SQLType;
import com.alibaba.druid.sql.parser.Token;

public class SQLTypeParserUtils {

    private static final Set<SQLType> QUERY_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            SQLType.SELECT,
            SQLType.SHOW,
            SQLType.SHOW_TABLES,
            SQLType.SHOW_USERS,
            SQLType.SHOW_PARTITIONS,
            SQLType.SHOW_CATALOGS,
            SQLType.SHOW_FUNCTIONS,
            SQLType.SHOW_ROLE,
            SQLType.SHOW_ROLES,
            SQLType.SHOW_PACKAGE,
            SQLType.SHOW_PACKAGES,
            SQLType.SHOW_CHANGELOGS,
            SQLType.SHOW_ACL,
            SQLType.SHOW_RECYCLEBIN,
            SQLType.SHOW_VARIABLES,
            SQLType.SHOW_HISTORY,
            SQLType.SHOW_GRANT,
            SQLType.SHOW_GRANTS,
            SQLType.SHOW_CREATE_TABLE,
            SQLType.SHOW_STATISTIC,
            SQLType.SHOW_STATISTIC_LIST,
            SQLType.SHOW_LABEL,
            SQLType.DESC,
            SQLType.EXPLAIN,
            SQLType.WITH,
            SQLType.WHO,
            SQLType.WHOAMI)));

    public static boolean isSelectSql(String sql, com.alibaba.druid.DbType dbType) {
        SQLType sqlType = getSqlTypeForSingleLineSql(sql, dbType);
        return sqlType != null && QUERY_TYPES.contains(sqlType);
    }

    public static SQLType getSqlTypeForSingleLineSql(String sql, com.alibaba.druid.DbType dbType) {
        try {
            SQLType sqlType;
            Lexer lexer = com.alibaba.druid.sql.parser.SQLParserUtils.createLexer(sql, dbType);
            sqlType = lexer.scanSQLTypeV2();
            if (sqlType == null) {
                return null;
            }

            if (sqlType == SQLType.WITH) {
                lexer = com.alibaba.druid.sql.parser.SQLParserUtils.createLexer(sql, dbType);

                int updateCnt = 0, insertCnt = 0, deleteCnt = 0;
                for_: for (Token token = null;;) {
                    Token last = token;
                    lexer.nextToken();
                    token = lexer.token();

                    if (last == Token.INSERT) {
                        if (token == Token.OVERWRITE) {
                            return SQLType.INSERT_OVERWRITE_SELECT;
                        } else if (token == Token.INTO) {
                            return SQLType.INSERT_INTO_SELECT;
                        }
                    }
                    switch (token) {
                        case EOF:
                        case ERROR:
                            break for_;
                        case INSERT:
                            insertCnt++;
                            break;
                        case DELETE:
                            deleteCnt++;
                            break;
                        case UPDATE:
                            updateCnt++;
                            break;
                        default:
                            break;
                    }
                }
                if (updateCnt == 0 && insertCnt == 0 && deleteCnt == 0) {
                    return SQLType.SELECT;
                }
            }
            return sqlType;
        } catch (ParserException ignored) {
        }
        return null;
    }
}
