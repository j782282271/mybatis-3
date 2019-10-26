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
package org.apache.ibatis.executor;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementUtil;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.logging.jdbc.ConnectionLogger;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.apache.ibatis.executor.ExecutionPlaceholder.EXECUTION_PLACEHOLDER;

/**
 * 1）一级缓存模版方法
 * 2）使用一级缓存处理部分延迟加载相关逻辑，如：
 * 先来的不需要延迟加载的子sql，即立即执行的子sql，执行完后会加入到一级缓存中
 * 后来的相同sql，但是需要延迟加载，发现了一级缓存中已经有该sql的执行结果了。
 * 会在最外层sql执行完毕后，统一将缓存的内容替换为需要延迟加载的位置(见方法：DefaultResultSetHandler.getNestedQueryMappingValue)
 *
 * @author Clinton Begin
 */
public abstract class BaseExecutor implements Executor {

    private static final Log log = LogFactory.getLog(BaseExecutor.class);

    protected Transaction transaction;
    /**
     * wrapper就是this
     */
    protected Executor wrapper;

    /**
     * 延迟加载队列
     */
    protected ConcurrentLinkedQueue<DeferredLoad> deferredLoads;
    /**
     * 一级缓存，用于缓存当前Executor查询结果集映射得到的结果对象
     * 一级缓存是会话级别的缓存，mybatis中每创建一个sqlSession，就表示开启一次数据库会话。
     * sqlSession封装了Executor，当调用Executor.close方法时，一级缓存就不再可用。
     * 例如在一个事务内，反复执行同一条相同的sql查询，如果不缓存就会造成浪费。
     * localCache作为一级缓存，每次执行sql时都会查询是否有该sql的缓存值，如果有则使用，不进行查库
     */
    protected PerpetualCache localCache;
    /**
     * 一级缓存，用于缓存输出类型参数
     */
    protected PerpetualCache localOutputParameterCache;
    protected Configuration configuration;

    /**
     * 用来记录嵌套查询层数
     */
    protected int queryStack = 0;

    private boolean closed;

    /**
     * configuration.newExecutor方法会调用本方法创建Executor
     */
    protected BaseExecutor(Configuration configuration, Transaction transaction) {
        this.transaction = transaction;
        this.deferredLoads = new ConcurrentLinkedQueue<DeferredLoad>();
        this.localCache = new PerpetualCache("LocalCache");
        this.localOutputParameterCache = new PerpetualCache("LocalOutputParameterCache");
        this.closed = false;
        this.configuration = configuration;
        this.wrapper = this;
    }

    @Override
    public Transaction getTransaction() {
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        return transaction;
    }

    /**
     * 清空所有属性，本类不再可用，并根据参数决定是否回滚
     */
    @Override
    public void close(boolean forceRollback) {
        try {
            try {
                rollback(forceRollback);
            } finally {
                if (transaction != null) {
                    transaction.close();
                }
            }
        } catch (SQLException e) {
            // Ignore.  There's nothing that can be done at this point.
            log.warn("Unexpected exception on closing transaction.  Cause: " + e);
        } finally {
            transaction = null;
            deferredLoads = null;
            localCache = null;
            localOutputParameterCache = null;
            closed = true;
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public int update(MappedStatement ms, Object parameter) throws SQLException {
        ErrorContext.instance().resource(ms.getResource()).activity("executing an update").object(ms.getId());
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        //清空缓存，因为执行完update，数据中的内容可能已经发生变化，所以缓存无效了
        clearLocalCache();
        return doUpdate(ms, parameter);
    }

    /**
     * batchExecutor可以缓存多条sql，在适合的时候，将这些sql统一发到数据库执行
     * 本方法主要针对于批处理多条sql语句，会调用doFlushStatements，处理缓存中的多条sql语句
     * commit/rollback方法首先都会调用flushStatements方法，再处理事务相关操作
     */
    @Override
    public List<BatchResult> flushStatements() throws SQLException {
        return flushStatements(false);
    }

    public List<BatchResult> flushStatements(boolean isRollBack) throws SQLException {
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        return doFlushStatements(isRollBack);
    }

    @Override
    public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {
        BoundSql boundSql = ms.getBoundSql(parameter);
        CacheKey key = createCacheKey(ms, parameter, rowBounds, boundSql);
        return query(ms, parameter, rowBounds, resultHandler, key, boundSql);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
        ErrorContext.instance().resource(ms.getResource()).activity("executing a query").object(ms.getId());
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        if (queryStack == 0 && ms.isFlushCacheRequired()) {
            //非嵌套查询，且select节点，配置的flushCache=true，才会清空一级缓存
            //flushCache是影响一级缓存对象生命周期的一个方面
            clearLocalCache();
        }
        List<E> list;
        try {
            queryStack++;
            list = resultHandler == null ? (List<E>) localCache.getObject(key) : null;
            if (list != null) {
                handleLocallyCachedOutputParameters(ms, key, parameter, boundSql);
            } else {
                list = queryFromDatabase(ms, parameter, rowBounds, resultHandler, key, boundSql);
            }
        } finally {
            queryStack--;
        }
        if (queryStack == 0) {
            //一次查询中可能存在多次嵌套查询，当这些嵌套查询sql语句相同且参数相同，则只调用第一次该sql查询，然后将其缓存起来，
            // 后面的嵌套查询就可以服用前面的缓存值，就不需要再次查询了
            for (DeferredLoad deferredLoad : deferredLoads) {
                deferredLoad.load();
            }
            // issue #601
            deferredLoads.clear();
            if (configuration.getLocalCacheScope() == LocalCacheScope.STATEMENT) {
                //根据localCacheScope决定是否清空一级缓存
                // issue #482
                clearLocalCache();
            }
        }
        return list;
    }

    @Override
    public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
        BoundSql boundSql = ms.getBoundSql(parameter);
        return doQueryCursor(ms, parameter, rowBounds, boundSql);
    }

    /**
     * 延迟加载对象，放入到延迟加载队列
     * DefaultResultSetHandler.getNestedQueryMappingValue方法中，创建延迟加载对象并返回DEFERED，之前会将调用本方法，保存延迟加载内容
     */
    @Override
    public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        DeferredLoad deferredLoad = new DeferredLoad(resultObject, property, key, localCache, configuration, targetType);
        if (deferredLoad.canLoad()) {
            //缓存中有已完全加载到缓存的对象，则直接使用缓存中的结果load到外层对象的属性上
            deferredLoad.load();
        } else {
            //缓存中无已完全加载到缓存的对象，则加入延迟加载队列
            deferredLoads.add(new DeferredLoad(resultObject, property, key, localCache, configuration, targetType));
        }
    }

    /**
     * 创建缓存key
     * 使用resultMapId、offfset、limit、sql、sql使用到的参数值
     */
    @Override
    public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        CacheKey cacheKey = new CacheKey();
        cacheKey.update(ms.getId());
        cacheKey.update(Integer.valueOf(rowBounds.getOffset()));
        cacheKey.update(Integer.valueOf(rowBounds.getLimit()));
        cacheKey.update(boundSql.getSql());
        List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        TypeHandlerRegistry typeHandlerRegistry = ms.getConfiguration().getTypeHandlerRegistry();
        // mimic DefaultParameterHandler logic
        //根据boundSql.getParameterMappings()指定的属性（这些属性为sql语句中用到的属性），到入参中找到对应值，将这些值全部加入到CacheKey中
        for (int i = 0; i < parameterMappings.size(); i++) {
            ParameterMapping parameterMapping = parameterMappings.get(i);
            if (parameterMapping.getMode() != ParameterMode.OUT) {
                Object value;
                String propertyName = parameterMapping.getProperty();
                if (boundSql.hasAdditionalParameter(propertyName)) {
                    value = boundSql.getAdditionalParameter(propertyName);
                } else if (parameterObject == null) {
                    value = null;
                } else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
                    value = parameterObject;
                } else {
                    MetaObject metaObject = configuration.newMetaObject(parameterObject);
                    value = metaObject.getValue(propertyName);
                }
                cacheKey.update(value);
            }
        }
        if (configuration.getEnvironment() != null) {
            //环境隔离
            cacheKey.update(configuration.getEnvironment().getId());
        }
        return cacheKey;
    }

    @Override
    public boolean isCached(MappedStatement ms, CacheKey key) {
        return localCache.getObject(key) != null;
    }

    @Override
    public void commit(boolean required) throws SQLException {
        if (closed) {
            throw new ExecutorException("Cannot commit, transaction is already closed");
        }
        clearLocalCache();
        flushStatements();
        if (required) {
            transaction.commit();
        }
    }

    /**
     * 清空缓存，并根据参数决定是否回滚
     */
    @Override
    public void rollback(boolean required) throws SQLException {
        if (!closed) {
            try {
                clearLocalCache();
                flushStatements(true);
            } finally {
                if (required) {
                    transaction.rollback();
                }
            }
        }
    }

    /**
     * 清空一级缓存
     */
    @Override
    public void clearLocalCache() {
        if (!closed) {
            localCache.clear();
            localOutputParameterCache.clear();
        }
    }

    protected abstract int doUpdate(MappedStatement ms, Object parameter)
            throws SQLException;

    protected abstract List<BatchResult> doFlushStatements(boolean isRollback)
            throws SQLException;

    protected abstract <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
            throws SQLException;

    protected abstract <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql)
            throws SQLException;

    protected void closeStatement(Statement statement) {
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException e) {
                // ignore
            }
        }
    }

    /**
     * Apply a transaction timeout.
     *
     * @param statement a current statement
     * @throws SQLException if a database access error occurs, this method is called on a closed <code>Statement</code>
     * @see StatementUtil#applyTransactionTimeout(Statement, Integer, Integer)
     * @since 3.4.0
     */
    protected void applyTransactionTimeout(Statement statement) throws SQLException {
        StatementUtil.applyTransactionTimeout(statement, statement.getQueryTimeout(), transaction.getTimeout());
    }

    /**
     * 针对存储过程调用的处理
     * 一级缓存命中时，获取出参，并设置到用户传入的实参parameter中
     */
    private void handleLocallyCachedOutputParameters(MappedStatement ms, CacheKey key, Object parameter, BoundSql boundSql) {
        if (ms.getStatementType() == StatementType.CALLABLE) {
            final Object cachedParameter = localOutputParameterCache.getObject(key);
            if (cachedParameter != null && parameter != null) {
                //上次查询到结果的的入参
                final MetaObject metaCachedParameter = configuration.newMetaObject(cachedParameter);
                //本次查询的入参
                final MetaObject metaParameter = configuration.newMetaObject(parameter);
                //本次查询输入参数中的出参，赋值为上次查询到结果的的入参对应的属性值
                for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
                    if (parameterMapping.getMode() != ParameterMode.IN) {
                        final String parameterName = parameterMapping.getProperty();
                        final Object cachedValue = metaCachedParameter.getValue(parameterName);
                        metaParameter.setValue(parameterName, cachedValue);
                    }
                }
            }
        }
    }

    /**
     * 调用子类方法，不走缓存，直接查询数据库
     * 查询结果保存到缓存中
     */
    private <E> List<E> queryFromDatabase(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
        List<E> list;
        localCache.putObject(key, EXECUTION_PLACEHOLDER);
        try {
            list = doQuery(ms, parameter, rowBounds, resultHandler, boundSql);
        } finally {
            localCache.removeObject(key);
        }
        localCache.putObject(key, list);
        if (ms.getStatementType() == StatementType.CALLABLE) {
            localOutputParameterCache.putObject(key, parameter);
        }
        return list;
    }

    /**
     * 从transaction中获取连接，包装成带有logger的Connection子类
     */
    protected Connection getConnection(Log statementLog) throws SQLException {
        Connection connection = transaction.getConnection();
        if (statementLog.isDebugEnabled()) {
            return ConnectionLogger.newInstance(connection, statementLog, queryStack);
        } else {
            return connection;
        }
    }

    @Override
    public void setExecutorWrapper(Executor wrapper) {
        this.wrapper = wrapper;
    }

    /**
     * 一次查询中可能存在多次嵌套查询
     * 当这些嵌套查询sql语句相同且参数相同，则被缓存到
     */
    private static class DeferredLoad {
        /**
         * 外层对象对应的MetaObject
         */
        private final MetaObject resultObject;
        /**
         * 延迟加载的属性名
         */
        private final String property;
        /**
         * 延迟加载的属性类型
         */
        private final Class<?> targetType;
        /**
         * 延迟加载的结果对象在一级缓存中的CacheKey
         */
        private final CacheKey key;
        /**
         * 一级缓存与BaseExecutor的localCache指向同一个对象
         */
        private final PerpetualCache localCache;
        private final ObjectFactory objectFactory;
        /**
         * 负责结果类型的转换
         */
        private final ResultExtractor resultExtractor;

        // issue #781
        public DeferredLoad(MetaObject resultObject, String property, CacheKey key,
                            PerpetualCache localCache, Configuration configuration, Class<?> targetType) {
            this.resultObject = resultObject;
            this.property = property;
            this.key = key;
            this.localCache = localCache;
            this.objectFactory = configuration.getObjectFactory();
            this.resultExtractor = new ResultExtractor(configuration, objectFactory);
            this.targetType = targetType;
        }

        /**
         * 缓存项是否已完全加载到缓存中了
         */
        public boolean canLoad() {
            return localCache.getObject(key) != null && localCache.getObject(key) != EXECUTION_PLACEHOLDER;
        }

        /**
         * 加载一级缓存中的缓存值，将其赋到外层对象resultObject的属性上
         */
        public void load() {
            //获取缓存值
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) localCache.getObject(key);
            //将缓存值转换为期望类型
            Object value = resultExtractor.extractObjectFromList(list, targetType);
            //设置外层对象对一个属性值
            resultObject.setValue(property, value);
        }
    }
}
