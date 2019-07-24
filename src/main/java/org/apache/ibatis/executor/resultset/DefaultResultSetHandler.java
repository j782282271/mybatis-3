/**
 * Copyright 2009-2016 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.executor.resultset;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.cursor.defaults.DefaultCursor;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.loader.ResultLoader;
import org.apache.ibatis.executor.loader.ResultLoaderMap;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.executor.result.DefaultResultHandler;
import org.apache.ibatis.executor.result.ResultMapException;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.*;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.lang.reflect.Constructor;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

/**
 * 配置内容：https://blog.csdn.net/abcd898989/article/details/51189977
 * 解析过程可参考配置内容的含义
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Iwao AVE!
 */
public class DefaultResultSetHandler implements ResultSetHandler {

    private static final Object DEFERED = new Object();

    private final Executor executor;
    private final Configuration configuration;
    private final MappedStatement mappedStatement;
    private final RowBounds rowBounds;
    private final ParameterHandler parameterHandler;
    private final ResultHandler<?> resultHandler;
    private final BoundSql boundSql;
    private final TypeHandlerRegistry typeHandlerRegistry;
    private final ObjectFactory objectFactory;
    private final ReflectorFactory reflectorFactory;

    /**
     * 处理嵌套ResultMap过程中，每个嵌套的对象都会被存储到这里
     */
    private final Map<CacheKey, Object> nestedResultObjects = new HashMap<CacheKey, Object>();
    /**
     * 用于处理：循环引用key:resultMapId
     */
    private final Map<String, Object> ancestorObjects = new HashMap<String, Object>();
    private final Map<String, String> ancestorColumnPrefix = new HashMap<String, String>();
    private Object previousRowValue;

    /**
     * 多结果集相关
     * <resultMap id="blogResult" type="Blog">
     * <association  property ="author" JavaType ="Author" resultSet ="authors" column ="author_ id" foreignColumn ="id" >
     * </>
     * 在解析blogResult这resultSet时，遇到了含有resultSet ="authors"的association这一属性时，无法处理该属性，先存储到nextResultMaps中，其中key为resultSet的值(authors)
     * 当解析完blogResult这resultSet时，会继续解析下一个resultSet，解析下一个resultSet时，会将解析结果存储到blogResult的author属性中
     */
    private final Map<String, ResultMapping> nextResultMaps = new HashMap<String, ResultMapping>();

    /**
     * 多结果集相关
     */
    private final Map<CacheKey, List<PendingRelation>> pendingRelations = new HashMap<CacheKey, List<PendingRelation>>();

    /**
     * key为ResultMapId:前缀
     */
    private final Map<String, List<UnMappedColumnAutoMapping>> autoMappingsCache = new HashMap<String, List<UnMappedColumnAutoMapping>>();

    private static class PendingRelation {
        public MetaObject metaObject;
        public ResultMapping propertyMapping;
    }

    /**
     * resultMap中没有显示指定需要映射的属性
     */
    private static class UnMappedColumnAutoMapping {
        /**
         * columnName是带前缀的，property不带前缀，且有可能因为是否使用驼峰特性而不同
         */
        private final String column;
        private final String property;
        private final TypeHandler<?> typeHandler;
        private final boolean primitive;

        public UnMappedColumnAutoMapping(String column, String property, TypeHandler<?> typeHandler, boolean primitive) {
            this.column = column;
            this.property = property;
            this.typeHandler = typeHandler;
            this.primitive = primitive;
        }
    }

    public DefaultResultSetHandler(Executor executor, MappedStatement mappedStatement, ParameterHandler parameterHandler, ResultHandler<?> resultHandler, BoundSql boundSql,
                                   RowBounds rowBounds) {
        this.executor = executor;
        this.configuration = mappedStatement.getConfiguration();
        this.mappedStatement = mappedStatement;
        this.rowBounds = rowBounds;
        this.parameterHandler = parameterHandler;
        this.boundSql = boundSql;
        this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
        this.objectFactory = configuration.getObjectFactory();
        this.reflectorFactory = configuration.getReflectorFactory();
        this.resultHandler = resultHandler;
    }

    /**
     * 从cs中取出ResultSet
     */
    @Override
    public void handleOutputParameters(CallableStatement cs) throws SQLException {
        //用户输入参数
        final Object parameterObject = parameterHandler.getParameterObject();
        final MetaObject metaParam = configuration.newMetaObject(parameterObject);
        //参数信息，他与cs的返回resultSet是数量相同的，位置对应
        final List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        for (int i = 0; i < parameterMappings.size(); i++) {
            final ParameterMapping parameterMapping = parameterMappings.get(i);
            //输出类型参数
            if (parameterMapping.getMode() == ParameterMode.OUT || parameterMapping.getMode() == ParameterMode.INOUT) {
                if (ResultSet.class.equals(parameterMapping.getJavaType())) {
                    //参数类型为ResultSet，需要进行映射
                    handleRefCursorOutputParameter((ResultSet) cs.getObject(i + 1), parameterMapping, metaParam);
                } else {
                    final TypeHandler<?> typeHandler = parameterMapping.getTypeHandler();
                    metaParam.setValue(parameterMapping.getProperty(), typeHandler.getResult(cs, i + 1));
                }
            }
        }
    }

    /**
     * 处理ResultSet与ResultMap之间的映射
     * 将映射得到的结果对象，放到metaParam中
     */
    private void handleRefCursorOutputParameter(ResultSet rs, ParameterMapping parameterMapping, MetaObject metaParam) throws SQLException {
        if (rs == null) {
            return;
        }
        try {
            final String resultMapId = parameterMapping.getResultMapId();
            final ResultMap resultMap = configuration.getResultMap(resultMapId);
            final DefaultResultHandler resultHandler = new DefaultResultHandler(objectFactory);
            final ResultSetWrapper rsw = new ResultSetWrapper(rs, configuration);
            handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
            metaParam.setValue(parameterMapping.getProperty(), resultHandler.getResultList());
        } finally {
            // issue #228 (close resultsets)
            closeResultSet(rs);
        }
    }

    /**
     * @return List<Object> 一个resultSet一个元素，每个元素都是list
     */
    @Override
    public List<Object> handleResultSets(Statement stmt) throws SQLException {
        ErrorContext.instance().activity("handling results").object(mappedStatement.getId());

        final List<Object> multipleResults = new ArrayList<Object>();

        int resultSetCount = 0;
        //获取第一个resultSet对象，每个resultSet对象可以有多行记录
        //存储过程执行后会有多个resultSet对象，存储过程的ResultMap也可以有多个，按照顺序与resultSet对应
        //普通非存储过程单sql，只有一个resultMap，一个ResultSet
        ResultSetWrapper rsw = getFirstResultSet(stmt);

        List<ResultMap> resultMaps = mappedStatement.getResultMaps();
        int resultMapCount = resultMaps.size();
        validateResultMapsCount(rsw, resultMapCount);
        while (rsw != null && resultMapCount > resultSetCount) {
            ResultMap resultMap = resultMaps.get(resultSetCount);
            handleResultSet(rsw, resultMap, multipleResults, null);
            rsw = getNextResultSet(stmt);
            cleanUpAfterHandlingResultSet();
            resultSetCount++;
        }

        //存储过程CallableStatement，相关的多结果集处理
        //如果mapper.xml的select标签配置了resultSets属性，且该属性比resultMap属性多，说明，多出来的这个resultSet可能会被其它的resultMap引用，如：
//        <select id="selectBlog" resultSets="blogs,authors" resultMap="blogResult" statementType="CALLABLE">
//                {call getBlogsAndAuthors(#{id,jdbcType=INTEGER,mode=IN})}
//        </select>

//        getBlogsAndAuthors为：
//        SELECT * FROM BLOG WHERE ID = #{id}
//        SELECT * FROM AUTHOR WHERE ID = #{id}

//        <resultMap id="blogResult" type="Blog">
//          <id property="id" column="id" />
//          <result property="title" column="title"/>
//          <association property="author" javaType="Author" resultSet="authors" column="author_id" foreignColumn="id">
//            <id property="id" column="id"/>
//            <result property="username" column="username"/>
//            <result property="password" column="password"/>
//            <result property="email" column="email"/>
//            <result property="bio" column="bio"/>
//          </association>
//        </resultMap>
        String[] resultSets = mappedStatement.getResultSets();
        if (resultSets != null) {
            while (rsw != null && resultSetCount < resultSets.length) {
                //resultSetCount < resultSets.length说明仍有resultSet未处理
                ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
                if (parentMapping != null) {
                    String nestedResultMapId = parentMapping.getNestedResultMapId();
                    ResultMap resultMap = configuration.getResultMap(nestedResultMapId);
                    handleResultSet(rsw, resultMap, null, parentMapping);
                }
                rsw = getNextResultSet(stmt);
                cleanUpAfterHandlingResultSet();
                resultSetCount++;
            }
        }

        return collapseSingleResultList(multipleResults);
    }

    /**
     * 暂时没看
     */
    @Override
    public <E> Cursor<E> handleCursorResultSets(Statement stmt) throws SQLException {
        ErrorContext.instance().activity("handling cursor results").object(mappedStatement.getId());

        ResultSetWrapper rsw = getFirstResultSet(stmt);

        List<ResultMap> resultMaps = mappedStatement.getResultMaps();

        int resultMapCount = resultMaps.size();
        validateResultMapsCount(rsw, resultMapCount);
        if (resultMapCount != 1) {
            throw new ExecutorException("Cursor results cannot be mapped to multiple resultMaps");
        }

        ResultMap resultMap = resultMaps.get(0);
        return new DefaultCursor<E>(this, resultMap, rsw, rowBounds);
    }

    private ResultSetWrapper getFirstResultSet(Statement stmt) throws SQLException {
        ResultSet rs = stmt.getResultSet();
        while (rs == null) {
            // move forward to get the first resultset in case the driver
            // doesn't return the resultSet as the first result (HSQLDB 2.1)
            if (stmt.getMoreResults()) {
                rs = stmt.getResultSet();
            } else {
                if (stmt.getUpdateCount() == -1) {
                    // no more results. Must be no resultSet
                    break;
                }
            }
        }
        return rs != null ? new ResultSetWrapper(rs, configuration) : null;
    }

    private ResultSetWrapper getNextResultSet(Statement stmt) throws SQLException {
        // Making this method tolerant of bad JDBC drivers
        try {
            //支持多结果集
            if (stmt.getConnection().getMetaData().supportsMultipleResultSets()) {
                // Crazy Standard JDBC way of determining if there are more results
                if (!((!stmt.getMoreResults()) && (stmt.getUpdateCount() == -1))) {
                    ResultSet rs = stmt.getResultSet();
                    return rs != null ? new ResultSetWrapper(rs, configuration) : null;
                }
            }
        } catch (Exception e) {
            // Intentionally ignored.
        }
        return null;
    }

    private void closeResultSet(ResultSet rs) {
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            // ignore
        }
    }

    /**
     * 一个resultSet，有多行，执行同一个resultSet的第n行，会保留前n-1行的相关解析出来的数据到nestedResultObjects、ancestorColumnPrefix中
     * 如果开始解析下一个resultSet，则会清空nestedResultObjects、ancestorColumnPrefix
     */
    private void cleanUpAfterHandlingResultSet() {
        nestedResultObjects.clear();
        ancestorColumnPrefix.clear();
    }

    private void validateResultMapsCount(ResultSetWrapper rsw, int resultMapCount) {
        if (rsw != null && resultMapCount < 1) {
            throw new ExecutorException("A query was run and no Result Maps were found for the Mapped Statement '" + mappedStatement.getId()
                    + "'.  It's likely that neither a Result Type nor a Result Map was specified.");
        }
    }

    /**
     * 处理一个resultSet
     *
     * @param multipleResults ResultSet转换为对象后(通常是list)存入multipleResults
     * @param parentMapping
     */
    private void handleResultSet(ResultSetWrapper rsw, ResultMap resultMap, List<Object> multipleResults, ResultMapping parentMapping) throws SQLException {
        try {
            if (parentMapping != null) {
                //处理多结果集走到此分支
                handleRowValues(rsw, resultMap, null, RowBounds.DEFAULT, parentMapping);
            } else {
                if (resultHandler == null) {
                    DefaultResultHandler defaultResultHandler = new DefaultResultHandler(objectFactory);
                    handleRowValues(rsw, resultMap, defaultResultHandler, rowBounds, null);
                    multipleResults.add(defaultResultHandler.getResultList());
                } else {
                    //resultHandler不为空，解析的结果会被放在resultHandler中，不需要放到multipleResults中
                    handleRowValues(rsw, resultMap, resultHandler, rowBounds, null);
                }
            }
        } finally {
            // issue #228 (close resultsets)
            closeResultSet(rsw.getResultSet());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> collapseSingleResultList(List<Object> multipleResults) {
        return multipleResults.size() == 1 ? (List<Object>) multipleResults.get(0) : multipleResults;
    }

    public void handleRowValues(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
        if (resultMap.hasNestedResultMaps()) {
            ensureNoRowBounds();
            checkResultHandler();
            //含有嵌套resultMap的处理
            handleRowValuesForNestedResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
        } else {
            handleRowValuesForSimpleResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
        }
    }

    private void ensureNoRowBounds() {
        if (configuration.isSafeRowBoundsEnabled() && rowBounds != null && (rowBounds.getLimit() < RowBounds.NO_ROW_LIMIT || rowBounds.getOffset() > RowBounds.NO_ROW_OFFSET)) {
            throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely constrained by RowBounds. "
                    + "Use safeRowBoundsEnabled=false setting to bypass this check.");
        }
    }

    protected void checkResultHandler() {
        if (resultHandler != null && configuration.isSafeResultHandlerEnabled() && !mappedStatement.isResultOrdered()) {
            throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely used with a custom ResultHandler. "
                    + "Use safeResultHandlerEnabled=false setting to bypass this check "
                    + "or ensure your statement returns ordered data and set resultOrdered=true on it.");
        }
    }

    private void handleRowValuesForSimpleResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping)
            throws SQLException {
        //用于记录当前行解析出来的object
        DefaultResultContext<Object> resultContext = new DefaultResultContext<Object>();
        //跳过rowBounds.NO_ROW_OFFSET前的所有记录
        skipRows(rsw.getResultSet(), rowBounds);
        while (shouldProcessMoreRows(resultContext, rowBounds) && rsw.getResultSet().next()) {
            //确定要使用的resultMap
            ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(rsw.getResultSet(), resultMap, null);
            //创建根据rsw和ResultMap确定结果对象
            Object rowValue = getRowValue(rsw, discriminatedResultMap);
            storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
        }
    }

    /**
     * 保存解析好的对象
     */
    private void storeObject(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue, ResultMapping parentMapping, ResultSet rs) throws SQLException {
        if (parentMapping != null) {
            //多结果集连接到父对象中，注意多结果集不是嵌套映射
            linkToParents(rs, parentMapping, rowValue);
        } else {
            //如果是普通映射保存到resultHandler中
            callResultHandler(resultHandler, resultContext, rowValue);
        }
    }

    /**
     * 存储当前行结果到resultContext中
     * 存储当前行结果到resultHandler中
     */
    @SuppressWarnings("unchecked" /* because ResultHandler<?> is always ResultHandler<Object>*/)
    private void callResultHandler(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue) {
        resultContext.nextResultObject(rowValue);
        ((ResultHandler<Object>) resultHandler).handleResult(resultContext);
    }

    private boolean shouldProcessMoreRows(ResultContext<?> context, RowBounds rowBounds) throws SQLException {
        return !context.isStopped() && context.getResultCount() < rowBounds.getLimit();
    }

    private void skipRows(ResultSet rs, RowBounds rowBounds) throws SQLException {
        if (rs.getType() != ResultSet.TYPE_FORWARD_ONLY) {
            if (rowBounds.getOffset() != RowBounds.NO_ROW_OFFSET) {
                rs.absolute(rowBounds.getOffset());
            }
        } else {
            for (int i = 0; i < rowBounds.getOffset(); i++) {
                rs.next();
            }
        }
    }

    private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap) throws SQLException {
        //延迟加载Map,一行一个ResultLoaderMap
        final ResultLoaderMap lazyLoader = new ResultLoaderMap();
        //创建结果对象，根据resultMap中的type确定
        Object resultObject = createResultObject(rsw, resultMap, lazyLoader, null);
        //为结果对象的属性赋值
        if (resultObject != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
            //创建MetaObject用于反射赋值
            final MetaObject metaObject = configuration.newMetaObject(resultObject);
            //成功映射任意属性则foundValues==true
            //构造函数不为空，说明在createResultObject中肯定用了该构造函数去构造对象了，同时也为构造函数中涉及的属性赋值了，所以foundValues==true
            boolean foundValues = !resultMap.getConstructorResultMappings().isEmpty();
            //是否需要自动映射
            if (shouldApplyAutomaticMappings(resultMap, false)) {
                //自动映射resultMap未指定的列
                foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, null) || foundValues;
            }
            //映射resultMap中指定的列
            foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, null) || foundValues;
            foundValues = lazyLoader.size() > 0 || foundValues;
            //如果没有成功映射任何属性则，根据mybatis-config.xml中的returnInstanceForEmptyRow，决定是否返回空结果对象还是null
            resultObject = foundValues ? resultObject : null;
            return resultObject;
        }
        return resultObject;
    }

    /**
     * 是否开启自动映射
     */
    private boolean shouldApplyAutomaticMappings(ResultMap resultMap, boolean isNested) {
        if (resultMap.getAutoMapping() != null) {
            //是否配置自动开启映射功能
            return resultMap.getAutoMapping();
        } else {
            //没有配置autoMapping属性，则根据setting中的autoMappingBehavior值确定是否开启自动映射
            if (isNested) {
                return AutoMappingBehavior.FULL == configuration.getAutoMappingBehavior();
            } else {
                return AutoMappingBehavior.NONE != configuration.getAutoMappingBehavior();
            }
        }
    }

    /**
     * 映射resultMap中指定的列
     */
    private boolean applyPropertyMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, ResultLoaderMap lazyLoader, String columnPrefix)
            throws SQLException {
        //获取明确映射关系的列集合（以columnPrefix开头）
        final List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
        boolean foundValues = false;
        final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
        for (ResultMapping propertyMapping : propertyMappings) {
            String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
            if (propertyMapping.getNestedResultMapId() != null) {
                // the user added a column attribute to a nested result map, ignore it
                column = null;
            }
            if (propertyMapping.isCompositeResult()//column为这种类型"{prop1=col1,prop2=col2}",一般与嵌套查询结合使用，标识将来col1、col2的列值传给内层查询使用
                    || (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) //基本类型的属性映射
                    || propertyMapping.getResultSet() != null) {//多结果集的的场景处理，该属性来自于另一个结果集
                //完成映射，并得到属性值
                Object value = getPropertyMappingValue(rsw.getResultSet(), metaObject, propertyMapping, lazyLoader, columnPrefix);
                // issue #541 make property optional
                final String property = propertyMapping.getProperty();
                // issue #377, call setter on nulls
                if (value != DEFERED
                        && property != null
                        && (value != null || (configuration.isCallSettersOnNulls() && !metaObject.getSetterType(property).isPrimitive()))) {
                    metaObject.setValue(property, value);
                }
                if (property != null && (value != null || value == DEFERED)) {
                    foundValues = true;
                }
            }
        }
        return foundValues;
    }

    /**
     * 从rs中获取propertyMapping属性值
     *
     * @param metaResultObject 为结果对象，
     * @param propertyMapping  为metaResultObject的一个属性
     * @param lazyLoader       延迟加载map，如果嵌套查询，创建了延迟加载代理类，会被放到ResultLoaderMap中
     */
    private Object getPropertyMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
            throws SQLException {
        if (propertyMapping.getNestedQueryId() != null) {
            //嵌套查询
            return getNestedQueryMappingValue(rs, metaResultObject, propertyMapping, lazyLoader, columnPrefix);
        } else if (propertyMapping.getResultSet() != null) {
            //多结果集处理，指定了resultSet的propertyMapping
            // TODO is that OK?
            addPendingChildRelation(rs, metaResultObject, propertyMapping);
            return DEFERED;
        } else {
            //常规属性，直接用TypeHandler获取java值就行
            final TypeHandler<?> typeHandler = propertyMapping.getTypeHandler();
            final String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
            return typeHandler.getResult(rs, column);
        }
    }

//前缀的作用:select XXX as prefix_realProperty,如下co_前缀，同一行记录，用于了两个相同的resultMap,根据前缀要求不同产生了不同的对象
//<select id="selectBlog" resultMap="blogResult">
//    select
//    B.id            as blog_id,
//    B.title         as blog_title,
//    A.id            as author_id,
//    A.username      as author_username,
//    A.password      as author_password,
//    A.email         as author_email,
//    A.bio           as author_bio,
//    CA.id           as co_author_id,
//    CA.username     as co_author_username,
//    CA.password     as co_author_password,
//    CA.email        as co_author_email,
//    CA.bio          as co_author_bio
//    from Blog B
//    left outer join Author A on B.author_id = A.id
//    left outer join Author CA on B.co_author_id = CA.id
//    where B.id = #{id}
//</select>
//<resultMap id="authorResult" type="Author">
//  <id property="id" column="author_id"/>
//  <result property="username" column="author_username"/>
//  <result property="password" column="author_password"/>
//  <result property="email" column="author_email"/>
//  <result property="bio" column="author_bio"/>
//</resultMap>
//<resultMap id="blogResult" type="Blog">
//  <id property="id" column="blog_id" />
//  <result property="title" column="blog_title"/>
//  <association property="author" resultMap="authorResult" />
//  <association property="coAuthor"resultMap="authorResult" columnPrefix="co_" />
//</resultMap>

    /**
     * 为未映射列查找属性，并关联到UnMappedColumnAutoMapping中，UnMappedColumnAutoMapping会被缓存在autoMappingsCache中
     *
     * @param columnPrefix 用于处理含有columnPrefix前缀的属性，不含有该前缀的属性跳过
     */
    private List<UnMappedColumnAutoMapping> createAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
        final String mapKey = resultMap.getId() + ":" + columnPrefix;
        //从缓存取
        List<UnMappedColumnAutoMapping> autoMapping = autoMappingsCache.get(mapKey);
        if (autoMapping == null) {
            autoMapping = new ArrayList<UnMappedColumnAutoMapping>();
            //从rsw中获取未映射的列名集合
            final List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
            for (String columnName : unmappedColumnNames) {
                String propertyName = columnName;
                //propertyName为columnName去掉前缀，前缀只用于方便映射，区别某些同属性但是不同column的字段，与真正属性无关
                if (columnPrefix != null && !columnPrefix.isEmpty()) {
                    //指定了前缀，但是列名不以其开头，则不要该列名
                    //指定了前缀，列名以其开头，则trim掉该前缀
                    if (columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
                        propertyName = columnName.substring(columnPrefix.length());
                    } else {
                        continue;
                    }
                }
                //是否存在该属性的setter方法，如果为map一直返回true
                final String property = metaObject.findProperty(propertyName, configuration.isMapUnderscoreToCamelCase());
                if (property != null && metaObject.hasSetter(property)) {
                    final Class<?> propertyType = metaObject.getSetterType(property);
                    if (typeHandlerRegistry.hasTypeHandler(propertyType, rsw.getJdbcType(columnName))) {
                        final TypeHandler<?> typeHandler = rsw.getTypeHandler(propertyType, columnName);
                        //注意此处columnName是带前缀的，property不带前缀
                        autoMapping.add(new UnMappedColumnAutoMapping(columnName, property, typeHandler, propertyType.isPrimitive()));
                    } else {
                        //日志或者异常输出
                        configuration.getAutoMappingUnknownColumnBehavior()
                                .doAction(mappedStatement, columnName, property, propertyType);
                    }
                } else {
                    //日志或者异常输出
                    configuration.getAutoMappingUnknownColumnBehavior()
                            .doAction(mappedStatement, columnName, (property != null) ? property : propertyName, null);
                }
            }
            autoMappingsCache.put(mapKey, autoMapping);
        }
        return autoMapping;
    }

    /**
     * 将自动映射的列，从rsw中提取出来放置到metaObject中
     *
     * @param metaObject 为结果对象的meta类
     */
    private boolean applyAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
        //resultSet中存在但是ResultMap中不存在的列对应的UnMappedColumnAutoMapping集合，如果ResultMap中的ResultType为Map,则全部列都在这里
        List<UnMappedColumnAutoMapping> autoMapping = createAutomaticMappings(rsw, resultMap, metaObject, columnPrefix);
        boolean foundValues = false;
        if (autoMapping.size() > 0) {
            for (UnMappedColumnAutoMapping mapping : autoMapping) {
                //获取列值
                final Object value = mapping.typeHandler.getResult(rsw.getResultSet(), mapping.column);
                // issue #377, call setter on nulls
                //设置属性值
                if (value != null || configuration.isCallSettersOnNulls()) {
                    if (value != null || !mapping.primitive) {
                        metaObject.setValue(mapping.property, value);
                    }
                    foundValues = true;
                }
            }
        }
        return foundValues;
    }

    private void linkToParents(ResultSet rs, ResultMapping parentMapping, Object rowValue) throws SQLException {
        CacheKey parentKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getForeignColumn());
        List<PendingRelation> parents = pendingRelations.get(parentKey);
        if (parents != null) {
            for (PendingRelation parent : parents) {
                if (parent != null && rowValue != null) {
                    linkObjects(parent.metaObject, parent.propertyMapping, rowValue);
                }
            }
        }
    }

    /**
     * 添加到多结果集中，等到处理下一个sql语句（或者说是下一个resultSet）时会使用
     * 处理下一个resultSet时得到的结果会被赋值给本字段
     *
     * @param rs
     * @param metaResultObject
     * @param parentMapping    指定了resultSet的parentMapping，类似于：<association  property ="author" JavaType ="Author" resultSet ="authors" column ="author_ id" foreignColumn ="id" >
     * @throws SQLException
     */
    private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject, ResultMapping parentMapping) throws SQLException {
        CacheKey cacheKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getColumn());
        PendingRelation deferLoad = new PendingRelation();
        deferLoad.metaObject = metaResultObject;
        deferLoad.propertyMapping = parentMapping;
        List<PendingRelation> relations = pendingRelations.get(cacheKey);
        // issue #255
        if (relations == null) {
            relations = new ArrayList<DefaultResultSetHandler.PendingRelation>();
            pendingRelations.put(cacheKey, relations);
        }
        relations.add(deferLoad);
        ResultMapping previous = nextResultMaps.get(parentMapping.getResultSet());
        if (previous == null) {
            nextResultMaps.put(parentMapping.getResultSet(), parentMapping);
        } else {
            if (!previous.equals(parentMapping)) {
                throw new ExecutorException("Two different properties are mapped to the same resultSet");
            }
        }
    }

    private CacheKey createKeyForMultipleResults(ResultSet rs, ResultMapping resultMapping, String names, String columns) throws SQLException {
        CacheKey cacheKey = new CacheKey();
        cacheKey.update(resultMapping);
        if (columns != null && names != null) {
            String[] columnsArray = columns.split(",");
            String[] namesArray = names.split(",");
            for (int i = 0; i < columnsArray.length; i++) {
                Object value = rs.getString(columnsArray[i]);
                if (value != null) {
                    cacheKey.update(namesArray[i]);
                    cacheKey.update(value);
                }
            }
        }
        return cacheKey;
    }

    private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
        //记录构造函数参数类型
        final List<Class<?>> constructorArgTypes = new ArrayList<Class<?>>();
        //记录构造函数实参
        final List<Object> constructorArgs = new ArrayList<Object>();
        //创建该行记录的结果对象
        final Object resultObject = createResultObject(rsw, resultMap, constructorArgTypes, constructorArgs, columnPrefix);
        if (resultObject != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
            final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
            for (ResultMapping propertyMapping : propertyMappings) {
                // issue gcode #109 && issue #149
                //有嵌套查询属性，且延迟加载，则为当前resultObject创建延迟加载代理类，创建一次即可return
                if (propertyMapping.getNestedQueryId() != null && propertyMapping.isLazy()) {
                    return configuration.getProxyFactory().createProxy(resultObject, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
                }
            }
        }
        return resultObject;
    }

    /**
     * 进来的时候List<Class<?>> constructorArgTypes, List<Object> constructorArgs是空的
     * 会根据resultMap中的构造函数标签去add到constructorArgTypes，同时解析对应列在rsw中的值add到constructorArgs
     */
    private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix)
            throws SQLException {
        //结果对像类型
        final Class<?> resultType = resultMap.getType();
        final MetaClass metaType = MetaClass.forClass(resultType, reflectorFactory);
        //resultMap中的constructor节点集合
        final List<ResultMapping> constructorMappings = resultMap.getConstructorResultMappings();
        if (hasTypeHandlerForResultObject(rsw, resultType)) {
            //结果集只有一列或者存在resultType的TypeHandler，可以使用resultType的TypeHandler，将resultSet转为resultType
            return createPrimitiveResultObject(rsw, resultMap, columnPrefix);
        } else if (!constructorMappings.isEmpty()) {
            //resultMap中存在constructor节点，则使用反射创建结果对象
            return createParameterizedResultObject(rsw, resultType, constructorMappings, constructorArgTypes, constructorArgs, columnPrefix);
        } else if (resultType.isInterface() || metaType.hasDefaultConstructor()) {
            //resultMap中不存在constructor节点，则使用默认构造函数直接创建
            return objectFactory.create(resultType);
        } else if (shouldApplyAutomaticMappings(resultMap, false)) {
            //通过自动映射的方式查找适合的构造方法并创建结果对象
            return createByConstructorSignature(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix);
        }
        throw new ExecutorException("Do not know how to create an instance of " + resultType);
    }

    /**
     * 涉及到嵌套查询、嵌套映射处理
     * 根据resultMap中指定的constructorMappings，到rsw中获取对应列的值，去构造结果对象
     */
    Object createParameterizedResultObject(ResultSetWrapper rsw, Class<?> resultType, List<ResultMapping> constructorMappings,
                                           List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix) {
        boolean foundValues = false;
        for (ResultMapping constructorMapping : constructorMappings) {
            final Class<?> parameterType = constructorMapping.getJavaType();
            final String column = constructorMapping.getColumn();
            final Object value;
            try {
                if (constructorMapping.getNestedQueryId() != null) {
                    //嵌套查询，需要查询才能构造当前构造函数入参
                    value = getNestedQueryConstructorValue(rsw.getResultSet(), constructorMapping, columnPrefix);
                } else if (constructorMapping.getNestedResultMapId() != null) {
                    //嵌套映射，先要处理嵌套映射才能构造当前bean
                    final ResultMap resultMap = configuration.getResultMap(constructorMapping.getNestedResultMapId());
                    value = getRowValue(rsw, resultMap);
                } else {
                    //直接获取当前列java值
                    final TypeHandler<?> typeHandler = constructorMapping.getTypeHandler();
                    value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(column, columnPrefix));
                }
            } catch (ResultMapException e) {
                throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
            } catch (SQLException e) {
                throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
            }
            constructorArgTypes.add(parameterType);
            constructorArgs.add(value);
            foundValues = value != null || foundValues;
        }
        return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
    }

    /**
     * 遍历resultType的所有构造函数，找到一个包含rsw中所有属性的构造函数
     * 通过该构造函数，和rsw创建结果值
     */
    private Object createByConstructorSignature(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs,
                                                String columnPrefix) throws SQLException {
        //遍历所有构造方法，找到一个包含rsw中所有属性的构造函数
        for (Constructor<?> constructor : resultType.getDeclaredConstructors()) {
            //两个list元素完全相同才相等
            if (typeNames(constructor.getParameterTypes()).equals(rsw.getClassNames())) {
                boolean foundValues = false;
                for (int i = 0; i < constructor.getParameterTypes().length; i++) {
                    Class<?> parameterType = constructor.getParameterTypes()[i];
                    String columnName = rsw.getColumnNames().get(i);
                    TypeHandler<?> typeHandler = rsw.getTypeHandler(parameterType, columnName);
                    Object value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(columnName, columnPrefix));
                    constructorArgTypes.add(parameterType);
                    constructorArgs.add(value);
                    foundValues = value != null || foundValues;
                }
                return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
            }
        }
        throw new ExecutorException("No constructor found in " + resultType.getName() + " matching " + rsw.getClassNames());
    }

    private List<String> typeNames(Class<?>[] parameterTypes) {
        List<String> names = new ArrayList<String>();
        for (Class<?> type : parameterTypes) {
            names.add(type.getName());
        }
        return names;
    }

    /**
     * 使用typeHandler直接解析resultSet
     * 解析resultSet的那一列由resultMap.getResultMappings()的第一个列名决定
     */
    private Object createPrimitiveResultObject(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
        final Class<?> resultType = resultMap.getType();
        final String columnName;
        if (!resultMap.getResultMappings().isEmpty()) {
            final List<ResultMapping> resultMappingList = resultMap.getResultMappings();
            final ResultMapping mapping = resultMappingList.get(0);
            columnName = prependPrefix(mapping.getColumn(), columnPrefix);
        } else {
            columnName = rsw.getColumnNames().get(0);
        }
        final TypeHandler<?> typeHandler = rsw.getTypeHandler(resultType, columnName);
        return typeHandler.getResult(rsw.getResultSet(), columnName);
    }

    /**
     * 处于构造参数中的嵌套查询，应立即执行，不延迟加载
     * <resultMap id="blogResult" type="Blog">
     * *** <constructor>
     * ********<association property="author" column="author_id" javaType="Author" select="selectAuthor"/>
     * ***</constructor>
     * </resultMap>
     */
    private Object getNestedQueryConstructorValue(ResultSet rs, ResultMapping constructorMapping, String columnPrefix) throws SQLException {
        final String nestedQueryId = constructorMapping.getNestedQueryId();
        final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
        final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
        //从rs中找到构造函数的nestedQuery所需要的参数
        final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, constructorMapping, nestedQueryParameterType, columnPrefix);
        Object value = null;
        if (nestedQueryParameterObject != null) {
            final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
            final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
            final Class<?> targetType = constructorMapping.getJavaType();
            final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
            //构造函数中的嵌套查询，不会延迟加载，会立即加载
            value = resultLoader.loadResult();
        }
        return value;
    }

    /**
     * 获取嵌套查询值，如果是延迟加载则返回DEFERED对象
     */
    private Object getNestedQueryMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
            throws SQLException {
        final String nestedQueryId = propertyMapping.getNestedQueryId();
        final String property = propertyMapping.getProperty();
        final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
        final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
        final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, propertyMapping, nestedQueryParameterType, columnPrefix);
        Object value = null;
        if (nestedQueryParameterObject != null) {
            final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
            final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
            final Class<?> targetType = propertyMapping.getJavaType();
            if (executor.isCached(nestedQuery, key)) {
                executor.deferLoad(nestedQuery, metaResultObject, property, key, targetType);
                value = DEFERED;
            } else {
                final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
                if (propertyMapping.isLazy()) {
                    //延迟加载，但是返回DEFERED对象
                    //将延迟加载ResultLoader对象放到ResultLoaderMap中
                    lazyLoader.addLoader(property, metaResultObject, resultLoader);
                    value = DEFERED;
                } else {
                    //没有配置延迟加载，则立即加载
                    value = resultLoader.loadResult();
                }
            }
        }
        return value;
    }

    /**
     * 从rs中找到构造函数的nestedQuery所需要的参数，如：
     * <resultMap id="blogResult" type="Blog">
     * *** <constructor>
     * ********<association property="author" column="author_id" javaType="Author" select="selectAuthor"/>
     * ***</constructor>
     * </resultMap>
     *
     * @param rs            resultSet结果，如上图，rs为blogResult的查询结果，会从rs中找到author_id字段，作为输入，输给selectAuthor查询，column可能不止一个字段可能多个用逗号分割
     *                      如果为多个，则resultMapping.isCompositeResult()==true
     * @param resultMapping 构造函数的属性列
     * @param parameterType 该属性列的java类型
     */
    private Object prepareParameterForNestedQuery(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
        if (resultMapping.isCompositeResult()) {
            return prepareCompositeKeyParameter(rs, resultMapping, parameterType, columnPrefix);
        } else {
            return prepareSimpleKeyParameter(rs, resultMapping, parameterType, columnPrefix);
        }
    }

    /**
     * 根据parameterType和列名，找到typeHandler，从rs中提取该列的值
     */
    private Object prepareSimpleKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
        final TypeHandler<?> typeHandler;
        if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
            typeHandler = typeHandlerRegistry.getTypeHandler(parameterType);
        } else {
            typeHandler = typeHandlerRegistry.getUnknownTypeHandler();
        }
        return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
    }

    /**
     * 复合列说明见方法：MapperBuilderAssistant.parseCompositeColumnName的注释
     * 其中parameterType为javaType="Author"，如果没指定，则parameterType会创建出map类型的parameterObject
     */
    private Object prepareCompositeKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
        final Object parameterObject = instantiateParameterObject(parameterType);
        final MetaObject metaObject = configuration.newMetaObject(parameterObject);
        boolean foundValues = false;
        for (ResultMapping innerResultMapping : resultMapping.getComposites()) {
            final Class<?> propType = metaObject.getSetterType(innerResultMapping.getProperty());
            final TypeHandler<?> typeHandler = typeHandlerRegistry.getTypeHandler(propType);
            final Object propValue = typeHandler.getResult(rs, prependPrefix(innerResultMapping.getColumn(), columnPrefix));
            // issue #353 & #560 do not execute nested query if key is null
            if (propValue != null) {
                metaObject.setValue(innerResultMapping.getProperty(), propValue);
                foundValues = true;
            }
        }
        return foundValues ? parameterObject : null;
    }

    private Object instantiateParameterObject(Class<?> parameterType) {
        if (parameterType == null) {
            return new HashMap<Object, Object>();
        } else {
            return objectFactory.create(parameterType);
        }
    }

    /**
     * 根据ResultSet和resultMap确定真正的resultMap
     * 因为Discriminator可嵌套配置，所以循环
     */
    public ResultMap resolveDiscriminatedResultMap(ResultSet rs, ResultMap resultMap, String columnPrefix) throws SQLException {
        //已处理过的Discriminator id
        Set<String> pastDiscriminators = new HashSet<String>();
        Discriminator discriminator = resultMap.getDiscriminator();
        //discriminator中可能嵌套另一个discriminator，最终的类型由多个字段决定
        while (discriminator != null) {
            //有discriminator则使用其中的resultMap，获取该discriminator对应列在rs中的值
            final Object value = getDiscriminatorValue(rs, discriminator, columnPrefix);
            //根据rs中的值确定discriminatedMapId
            final String discriminatedMapId = discriminator.getMapIdFor(String.valueOf(value));
            if (configuration.hasResultMap(discriminatedMapId)) {
                resultMap = configuration.getResultMap(discriminatedMapId);
                Discriminator lastDiscriminator = discriminator;
                discriminator = resultMap.getDiscriminator();
                if (discriminator == lastDiscriminator || !pastDiscriminators.add(discriminatedMapId)) {
                    break;
                }
            } else {
                break;
            }
        }
        return resultMap;
    }

    /**
     * 根据discriminator所处的resultMapping的列名，到ResultSet中找到jdbc值，转换为java Object
     */
    private Object getDiscriminatorValue(ResultSet rs, Discriminator discriminator, String columnPrefix) throws SQLException {
        final ResultMapping resultMapping = discriminator.getResultMapping();
        final TypeHandler<?> typeHandler = resultMapping.getTypeHandler();
        return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
    }

    private String prependPrefix(String columnName, String prefix) {
        if (columnName == null || columnName.length() == 0 || prefix == null || prefix.length() == 0) {
            return columnName;
        }
        return prefix + columnName;
    }

    private void handleRowValuesForNestedResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
        final DefaultResultContext<Object> resultContext = new DefaultResultContext<Object>();
        skipRows(rsw.getResultSet(), rowBounds);
        Object rowValue = previousRowValue;
        while (shouldProcessMoreRows(resultContext, rowBounds) && rsw.getResultSet().next()) {
            final ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(rsw.getResultSet(), resultMap, null);
            final CacheKey rowKey = createRowKey(discriminatedResultMap, rsw, null);
            Object partialObject = nestedResultObjects.get(rowKey);
            // issue #577 && #542
            if (mappedStatement.isResultOrdered()) {
                //如果isResultOrdered==true，那么当主结果对象发生变化时，会清空nestedResultObjects，以节省内存，然后保存previousRowValue
                if (partialObject == null && rowValue != null) {
                    nestedResultObjects.clear();
                    //保存
                    storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
                }
                rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
            } else {
                //如果partialObject不为空，则返回rowValue就是partialObject，即外部对象
                rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
                //partialObject == null说明主结果对象发生了变化，rowValue为新的主结果，需要存储
                if (partialObject == null) {
                    //第一次进来partialObject == null,保存rowValue
                    storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
                }
            }
        }
        //shouldProcessMoreRows(resultContext, rowBounds)==true，说明了上面while条件中rsw.getResultSet().next()==fasle，即说明处理到了最后一行
        //如果mappedStatement.isResultOrdered() 开启，且最后一行不为空，则应该将rowValue存储起来，否则就落在nestedResultObjects中了
        if (rowValue != null && mappedStatement.isResultOrdered() && shouldProcessMoreRows(resultContext, rowBounds)) {
            storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
            previousRowValue = null;
        } else if (rowValue != null) {
            previousRowValue = rowValue;
        }
    }

    /**
     * 嵌套映射获取行值
     */
    private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, CacheKey combinedKey, String columnPrefix, Object partialObject) throws SQLException {
        final String resultMapId = resultMap.getId();
        Object resultObject = partialObject;
        if (resultObject != null) {
            //最外层对象存在，则创建内层对象，放到外层对象属性上
            final MetaObject metaObject = configuration.newMetaObject(resultObject);
            //将外层对象加入到ancestorObjects中
            putAncestor(resultObject, resultMapId, columnPrefix);
            //处理嵌套映射
            applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, false);
            ancestorObjects.remove(resultMapId);
        } else {//外层对象不存在，创建外层对象
            final ResultLoaderMap lazyLoader = new ResultLoaderMap();
            //创建外层对象
            resultObject = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
            if (resultObject != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
                final MetaObject metaObject = configuration.newMetaObject(resultObject);
                boolean foundValues = !resultMap.getConstructorResultMappings().isEmpty();
                //是否开启自动映射，如果开启则映射自动映射列
                if (shouldApplyAutomaticMappings(resultMap, true)) {
                    foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
                }
                //映射显示映射列
                foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
                //将外层对象加入到ancestorObjects中
                putAncestor(resultObject, resultMapId, columnPrefix);
                //处理嵌套映射，将内层结果对象设置到外层对象属性上
                foundValues = applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, true) || foundValues;
                ancestorObjects.remove(resultMapId);
                foundValues = lazyLoader.size() > 0 || foundValues;
                resultObject = foundValues ? resultObject : null;
            }
            if (combinedKey != CacheKey.NULL_CACHE_KEY) {
                //保存外层对象到nestedResultObjects中，
                nestedResultObjects.put(combinedKey, resultObject);
            }
        }
        return resultObject;
    }

    private void putAncestor(Object resultObject, String resultMapId, String columnPrefix) {
        if (!ancestorColumnPrefix.containsKey(resultMapId)) {
            ancestorColumnPrefix.put(resultMapId, columnPrefix);
        }
        ancestorObjects.put(resultMapId, resultObject);
    }

    /**
     * 处理resultMap中的嵌套映射resultMap
     *
     * @param resultMap  父resultMap
     * @param metaObject 父对象Meta
     */
    private boolean applyNestedResultMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String parentPrefix, CacheKey parentRowKey, boolean newObject) {
        boolean foundValues = false;
        for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
            //获取嵌套resultMapId,该值不为空说明存在嵌套映射要处理
            final String nestedResultMapId = resultMapping.getNestedResultMapId();
            if (nestedResultMapId != null && resultMapping.getResultSet() == null) {
                try {
                    //嵌套映射，column前缀处理，要加上父前缀
                    final String columnPrefix = getColumnPrefix(parentPrefix, resultMapping);
                    //resolveDiscriminatedResultMap解析嵌套resultMap的真正ResultMap
                    final ResultMap nestedResultMap = getNestedResultMap(rsw.getResultSet(), nestedResultMapId, columnPrefix);
                    Object ancestorObject = ancestorObjects.get(nestedResultMapId);
                    if (ancestorObject != null) {
                        //不为空说明当前子resultMap引用了父（或爷爷或者说祖先）resultMap，不需要再创建父resultMap对象，只需要引用一下即可
                        if (newObject) {
                            //将祖先的对象链接到当前resultMap（后代对象）对应的对象的属性
                            //issue #385
                            linkObjects(metaObject, resultMapping, ancestorObject);
                        }
                        //A对象中的一个属性为嵌套ResultMap，对应B对象；而B对象的一个属性又反过来指向A对象
                        //先创建A对象，将A对象放入ancestorObjects；再创建B对象，创建的时候发现了其属性为A对象，则重用A对象放到B属性中，不需要再创建A对象
                        //如果是循环引用（A引用B,B引用A）则不需要走else路径去创建新对象，而是重用之前的对象
                    } else {
                        //创建嵌套CacheKey
                        final CacheKey rowKey = createRowKey(nestedResultMap, rsw, columnPrefix);
                        //嵌套CacheKey结合父CacheKey，得到全局唯一的CacheKey
                        final CacheKey combinedKey = combineKeys(rowKey, parentRowKey);
                        Object rowValue = nestedResultObjects.get(combinedKey);
                        boolean knownValue = (rowValue != null);
                        //如果外层对象用于记录当前嵌套ResultMap的属性为集合对象，且未初始化，则会初始化该集合
                        instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject);
                        //根据<association>、<collection>等节点的nutNullColumn属性，检测结果集中相应的列是否为空
                        //如果要求的列rs中都没有，则不再创建内部对象了，结束递归
                        if (anyNotNullColumnHasValue(resultMapping, columnPrefix, rsw.getResultSet())) {
                            //嵌套递归解析
                            rowValue = getRowValue(rsw, nestedResultMap, combinedKey, columnPrefix, rowValue);
                            //knownValue==true说明相关列已经映射成了嵌套对象，假设对象A的两个属性b1/b2都指向了对象B,且这两个属性都是由同一个resultMap映射的
                            //在对同一行记录进行映射时，首先映射的b1属性会生成B对象，且赋值成功，当解析到b2属性的时候，!knownValue==true，所以b2属性则为null，不进行赋值
                            if (rowValue != null && !knownValue) {
                                //连接内部属性对象到外部对象上
                                linkObjects(metaObject, resultMapping, rowValue);
                                foundValues = true;
                            }
                        }
                    }
                } catch (SQLException e) {
                    throw new ExecutorException("Error getting nested result map values for '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
                }
            }
        }
        return foundValues;
    }

    //前缀 columnPrefix="co_" 使用示例，select出来的结果列明以co_开头的会被映射为coAuthor，不以co_开头的会被映射为author
    //<resultMap id="blogResult" type="Blog">
    //  <id property="id" column="blog_id" />
    //  <result property="title" column="blog_title"/>
    //  <association property="author"  resultMap="authorResult" />
    //  <association property="coAuthor" resultMap="authorResult" columnPrefix="co_" />
    //</resultMap>
    private String getColumnPrefix(String parentPrefix, ResultMapping resultMapping) {
        final StringBuilder columnPrefixBuilder = new StringBuilder();
        if (parentPrefix != null) {
            columnPrefixBuilder.append(parentPrefix);
        }
        if (resultMapping.getColumnPrefix() != null) {
            columnPrefixBuilder.append(resultMapping.getColumnPrefix());
        }
        return columnPrefixBuilder.length() == 0 ? null : columnPrefixBuilder.toString().toUpperCase(Locale.ENGLISH);
    }

    /**
     * 找到中不允许为空的列集合
     * 到rs中确认这些列是否有至少一个不为空的，有一个不为空就返回true
     */
    private boolean anyNotNullColumnHasValue(ResultMapping resultMapping, String columnPrefix, ResultSet rs) throws SQLException {
        Set<String> notNullColumns = resultMapping.getNotNullColumns();
        boolean anyNotNullColumnHasValue = true;
        if (notNullColumns != null && !notNullColumns.isEmpty()) {
            anyNotNullColumnHasValue = false;
            for (String column : notNullColumns) {
                rs.getObject(prependPrefix(column, columnPrefix));
                if (!rs.wasNull()) {
                    anyNotNullColumnHasValue = true;
                    break;
                }
            }
        }
        return anyNotNullColumnHasValue;
    }

    private ResultMap getNestedResultMap(ResultSet rs, String nestedResultMapId, String columnPrefix) throws SQLException {
        ResultMap nestedResultMap = configuration.getResultMap(nestedResultMapId);
        return resolveDiscriminatedResultMap(rs, nestedResultMap, columnPrefix);
    }

    /**
     * 创建cacheKey
     */
    private CacheKey createRowKey(ResultMap resultMap, ResultSetWrapper rsw, String columnPrefix) throws SQLException {
        final CacheKey cacheKey = new CacheKey();
        //resultMap的id用于计算key
        cacheKey.update(resultMap.getId());
        //优先使用ResultMapping的id用于计算key，其次使用所有属性用为key
        List<ResultMapping> resultMappings = getResultMappingsForRowKey(resultMap);
        if (resultMappings.size() == 0) {
            if (Map.class.isAssignableFrom(resultMap.getType())) {
                //当前结果集中所有列名和列值构成CacheKey
                createRowKeyForMap(rsw, cacheKey);
            } else {
                //未映射的列名和列值构成CacheKey
                createRowKeyForUnmappedProperties(resultMap, rsw, cacheKey, columnPrefix);
            }
        } else {
            //映射的列名和列值构成CacheKey
            createRowKeyForMappedProperties(resultMap, rsw, cacheKey, resultMappings, columnPrefix);
        }
        if (cacheKey.getUpdateCount() < 2) {
            return CacheKey.NULL_CACHE_KEY;
        }
        return cacheKey;
    }

    private CacheKey combineKeys(CacheKey rowKey, CacheKey parentRowKey) {
        if (rowKey.getUpdateCount() > 1 && parentRowKey.getUpdateCount() > 1) {
            CacheKey combinedKey;
            try {
                combinedKey = rowKey.clone();
            } catch (CloneNotSupportedException e) {
                throw new ExecutorException("Error cloning cache key.  Cause: " + e, e);
            }
            combinedKey.update(parentRowKey);
            return combinedKey;
        }
        return CacheKey.NULL_CACHE_KEY;
    }

    private List<ResultMapping> getResultMappingsForRowKey(ResultMap resultMap) {
        //查找resultMap中<id>、<idArg>节点对应的ResultMapping
        List<ResultMapping> resultMappings = resultMap.getIdResultMappings();
        if (resultMappings.size() == 0) {
            //所有属性ResultMapping节点
            resultMappings = resultMap.getPropertyResultMappings();
        }
        return resultMappings;
    }

    /**
     * 使用显示指定的resultMapping集合和其值(resultSet中)创建CacheKey
     */
    private void createRowKeyForMappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, List<ResultMapping> resultMappings, String columnPrefix) throws SQLException {
        for (ResultMapping resultMapping : resultMappings) {
            if (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null) {
                // Issue #392，如果存在嵌套映射，则递归调用
                final ResultMap nestedResultMap = configuration.getResultMap(resultMapping.getNestedResultMapId());
                createRowKeyForMappedProperties(nestedResultMap, rsw, cacheKey, nestedResultMap.getConstructorResultMappings(),
                        prependPrefix(resultMapping.getColumnPrefix(), columnPrefix));
            } else if (resultMapping.getNestedQueryId() == null) {
                final String column = prependPrefix(resultMapping.getColumn(), columnPrefix);
                final TypeHandler<?> th = resultMapping.getTypeHandler();
                List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
                // Issue #114
                if (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) {
                    final Object value = th.getResult(rsw.getResultSet(), column);
                    if (value != null) {
                        cacheKey.update(column);
                        cacheKey.update(value);
                    }
                }
            }
        }
    }

    private void createRowKeyForUnmappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, String columnPrefix) throws SQLException {
        final MetaClass metaType = MetaClass.forClass(resultMap.getType(), reflectorFactory);
        List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
        for (String column : unmappedColumnNames) {
            String property = column;
            if (columnPrefix != null && !columnPrefix.isEmpty()) {
                // When columnPrefix is specified, ignore columns without the prefix.
                if (column.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
                    property = column.substring(columnPrefix.length());
                } else {
                    continue;
                }
            }
            if (metaType.findProperty(property, configuration.isMapUnderscoreToCamelCase()) != null) {
                String value = rsw.getResultSet().getString(column);
                if (value != null) {
                    cacheKey.update(column);
                    cacheKey.update(value);
                }
            }
        }
    }

    private void createRowKeyForMap(ResultSetWrapper rsw, CacheKey cacheKey) throws SQLException {
        List<String> columnNames = rsw.getColumnNames();
        for (String columnName : columnNames) {
            final String value = rsw.getResultSet().getString(columnName);
            if (value != null) {
                cacheKey.update(columnName);
                cacheKey.update(value);
            }
        }
    }

    /**
     * 将rowValue链接到metaObject的resultMapping属性上
     * 如果该属性为集合，要提前初始化该集合，然后add到集合中，如果为非集合，直接设置值就行了
     */
    private void linkObjects(MetaObject metaObject, ResultMapping resultMapping, Object rowValue) {
        final Object collectionProperty = instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject);
        if (collectionProperty != null) {
            final MetaObject targetMetaObject = configuration.newMetaObject(collectionProperty);
            targetMetaObject.add(rowValue);
        } else {
            metaObject.setValue(resultMapping.getProperty(), rowValue);
        }
    }

    /**
     * 如果resultMapping指定的属性在metaObject中为集合，且为空则初始化该集合
     * 如果类型不为集合，则返回空，为集合类型返回初始化后的集合
     */
    private Object instantiateCollectionPropertyIfAppropriate(ResultMapping resultMapping, MetaObject metaObject) {
        final String propertyName = resultMapping.getProperty();
        Object propertyValue = metaObject.getValue(propertyName);
        if (propertyValue == null) {
            Class<?> type = resultMapping.getJavaType();
            if (type == null) {
                type = metaObject.getSetterType(propertyName);
            }
            try {
                if (objectFactory.isCollection(type)) {
                    propertyValue = objectFactory.create(type);
                    metaObject.setValue(propertyName, propertyValue);
                    return propertyValue;
                }
            } catch (Exception e) {
                throw new ExecutorException("Error instantiating collection property for result '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
            }
        } else if (objectFactory.isCollection(propertyValue.getClass())) {
            return propertyValue;
        }
        return null;
    }

    /**
     * resultType就是resultMap中的type，即java类型
     * 如果resultSet只有一列，返回该列是否有TypeHandler
     * 如果resultSet有多列，返回是否有该类型的TypeHandler
     */
    private boolean hasTypeHandlerForResultObject(ResultSetWrapper rsw, Class<?> resultType) {
        if (rsw.getColumnNames().size() == 1) {
            return typeHandlerRegistry.hasTypeHandler(resultType, rsw.getJdbcType(rsw.getColumnNames().get(0)));
        }
        return typeHandlerRegistry.hasTypeHandler(resultType);
    }

}
