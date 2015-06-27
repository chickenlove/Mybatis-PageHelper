/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 abel533@gmail.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.github.pagehelper;

import com.github.orderbyhelper.sqlsource.*;
import com.github.pagehelper.parser.Parser;
import com.github.pagehelper.sqlsource.PageDynamicSqlSource;
import com.github.pagehelper.sqlsource.PageProviderSqlSource;
import com.github.pagehelper.sqlsource.PageRawSqlSource;
import com.github.pagehelper.sqlsource.PageStaticSqlSource;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.builder.annotation.ProviderSqlSource;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.scripting.xmltags.DynamicSqlSource;
import org.apache.ibatis.scripting.xmltags.MixedSqlNode;
import org.apache.ibatis.scripting.xmltags.SqlNode;
import org.apache.ibatis.session.Configuration;

import javax.activation.UnsupportedDataTypeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 创建新的MappedStatement
 *
 * @author liuzh
 */
public class MSUtils implements Constant {
    private static final List<ResultMapping> EMPTY_RESULTMAPPING = new ArrayList<ResultMapping>(0);

    private Parser parser;

    public MSUtils(Parser parser) {
        this.parser = parser;
    }

    /**
     * 处理count查询的MappedStatement
     *
     * @param ms
     * @param sqlSource
     * @param args
     */
    public void processCountMappedStatement(MappedStatement ms, SqlSource sqlSource, Object[] args) {
        args[0] = getMappedStatement(ms, sqlSource, args[1], SUFFIX_COUNT);
    }

    /**
     * 处理分页查询的MappedStatement
     *
     * @param ms
     * @param sqlSource
     * @param page
     * @param args
     */
    public void processPageMappedStatement(MappedStatement ms, SqlSource sqlSource, Page page, Object[] args) {
        args[0] = getMappedStatement(ms, sqlSource, args[1], SUFFIX_PAGE);
        //处理入参
        args[1] = setPageParameter((MappedStatement) args[0], args[1], page);
    }


    /**
     * 设置分页参数
     *
     * @param parameterObject
     * @param page
     * @return
     */
    public Map setPageParameter(MappedStatement ms, Object parameterObject, Page page) {
        BoundSql boundSql = ms.getBoundSql(parameterObject);
        return parser.setPageParameter(ms, parameterObject, boundSql, page);
    }

    /**
     * 获取ms - 在这里对新建的ms做了缓存，第一次新增，后面都会使用缓存值
     *
     * @param ms
     * @param sqlSource
     * @param suffix
     * @return
     */
    public MappedStatement getMappedStatement(MappedStatement ms, SqlSource sqlSource, Object parameterObject, String suffix) {
        MappedStatement qs = null;
        if (ms.getId().endsWith(SUFFIX_PAGE) || ms.getId().endsWith(SUFFIX_COUNT)) {
            throw new RuntimeException("分页插件配置错误:请不要在系统中配置多个分页插件(使用Spring时,mybatis-config.xml和Spring<bean>配置方式，请选择其中一种，不要同时配置多个分页插件)！");
        }
        if (parser.isSupportedMappedStatementCache()) {
            try {
                qs = ms.getConfiguration().getMappedStatement(ms.getId() + suffix);
            } catch (Exception e) {
                //ignore
            }
        }
        if (qs == null) {
            //创建一个新的MappedStatement
            qs = newMappedStatement(ms, getSqlSource(sqlSource, suffix == SUFFIX_COUNT), suffix);
            if (parser.isSupportedMappedStatementCache()) {
                try {
                    ms.getConfiguration().addMappedStatement(qs);
                } catch (Exception e) {
                    //ignore
                }
            }
        }
        return qs;
    }

    /**
     * 新建count查询和分页查询的MappedStatement
     *
     * @param ms
     * @param sqlSource
     * @param suffix
     * @return
     */
    public MappedStatement newMappedStatement(MappedStatement ms, SqlSource sqlSource, String suffix) {
        String id = ms.getId() + suffix;
        MappedStatement.Builder builder = new MappedStatement.Builder(ms.getConfiguration(), id, sqlSource, ms.getSqlCommandType());
        builder.resource(ms.getResource());
        builder.fetchSize(ms.getFetchSize());
        builder.statementType(ms.getStatementType());
        builder.keyGenerator(ms.getKeyGenerator());
        if (ms.getKeyProperties() != null && ms.getKeyProperties().length != 0) {
            StringBuilder keyProperties = new StringBuilder();
            for (String keyProperty : ms.getKeyProperties()) {
                keyProperties.append(keyProperty).append(",");
            }
            keyProperties.delete(keyProperties.length() - 1, keyProperties.length());
            builder.keyProperty(keyProperties.toString());
        }
        builder.timeout(ms.getTimeout());
        builder.parameterMap(ms.getParameterMap());
        if (suffix == SUFFIX_PAGE) {
            builder.resultMaps(ms.getResultMaps());
        } else {
            //count查询返回值int
            List<ResultMap> resultMaps = new ArrayList<ResultMap>();
            ResultMap resultMap = new ResultMap.Builder(ms.getConfiguration(), id, int.class, EMPTY_RESULTMAPPING).build();
            resultMaps.add(resultMap);
            builder.resultMaps(resultMaps);
        }
        builder.resultSetType(ms.getResultSetType());
        builder.cache(ms.getCache());
        builder.flushCacheRequired(ms.isFlushCacheRequired());
        builder.useCache(ms.isUseCache());

        return builder.build();
    }

    /**
     * 获取新的sqlSource
     *
     * @param sqlSource
     * @param count
     * @return
     */
    public SqlSource getSqlSource(SqlSource sqlSource, boolean count) {
        SqlSource tempSqlSource = sqlSource;
        if (sqlSource instanceof OrderBySqlSource) {
            tempSqlSource = ((OrderBySqlSource) tempSqlSource).getOriginal();
        }
        if (tempSqlSource instanceof StaticSqlSource) {
            return new PageStaticSqlSource((StaticSqlSource) tempSqlSource, parser, count);
        } else if (tempSqlSource instanceof RawSqlSource) {
            return new PageRawSqlSource((RawSqlSource) tempSqlSource, parser, count);
        } else if (tempSqlSource instanceof ProviderSqlSource) {
            return new PageProviderSqlSource((ProviderSqlSource) tempSqlSource, parser, count);
        } else if (tempSqlSource instanceof DynamicSqlSource) {
            return new PageDynamicSqlSource((DynamicSqlSource) tempSqlSource, parser, count);
        } else {
            throw new RuntimeException("分页插件不支持当前的SqlSource类型:[" + tempSqlSource.getClass() + "]");
        }
    }
}
