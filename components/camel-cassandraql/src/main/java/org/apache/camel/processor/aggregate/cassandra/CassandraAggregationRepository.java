/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.processor.aggregate.cassandra;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.spi.AggregationRepository;
import org.apache.camel.support.ServiceSupport;
import org.apache.camel.utils.cassandra.CassandraSessionHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.camel.utils.cassandra.CassandraUtils.append;
import static org.apache.camel.utils.cassandra.CassandraUtils.applyConsistencyLevel;
import static org.apache.camel.utils.cassandra.CassandraUtils.concat;
import static org.apache.camel.utils.cassandra.CassandraUtils.generateDelete;
import static org.apache.camel.utils.cassandra.CassandraUtils.generateInsert;
import static org.apache.camel.utils.cassandra.CassandraUtils.generateSelect;

/**
 * Implementation of {@link AggregationRepository} using Cassandra table to store
 * exchanges.
 * Advice: use LeveledCompaction for this table and tune read/write consistency levels.
 * Warning: Cassandra is not the best tool for queuing use cases
 * See: http://www.datastax.com/dev/blog/cassandra-anti-patterns-queues-and-queue-like-datasets
 */
public abstract class CassandraAggregationRepository extends ServiceSupport implements AggregationRepository {
    /**
     * Logger
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(CassandraAggregationRepository.class);
    /**
     * Session holder
     */
    private CassandraSessionHolder sessionHolder;
    /**
     * Table name
     */
    private String table = "CAMEL_AGGREGATION";
    /**
     * Exchange Id column name
     */
    private String exchangeIdColumn = "EXCHANGE_ID";
    /**
     * Exchange column name
     */
    private String exchangeColumn = "EXCHANGE";
    /**
     * Primary key columns
     */
    private String[] pkColumns;
    /**
     * Exchange marshaller/unmarshaller
     */
    private final CassandraCamelCodec exchangeCodec = new CassandraCamelCodec();
    /**
     * Time to live in seconds used for inserts
     */
    private Integer ttl;
    /**
     * Writeconsistency level
     */
    private ConsistencyLevel writeConsistencyLevel;
    /**
     * Read consistency level
     */
    private ConsistencyLevel readConsistencyLevel;

    private PreparedStatement insertStatement;
    private PreparedStatement selectStatement;
    private PreparedStatement deleteStatement;
    /**
     * Prepared statement used to get keys and exchange ids
     */
    private PreparedStatement selectKeyIdStatement;
    /**
     * Prepared statement used to delete with key and exchange id
     */
    private PreparedStatement deleteIfIdStatement;

    public CassandraAggregationRepository() {
    }

    public CassandraAggregationRepository(Session session) {
        this.sessionHolder = new CassandraSessionHolder(session);
    }

    public CassandraAggregationRepository(Cluster cluster, String keyspace) {
        this.sessionHolder = new CassandraSessionHolder(cluster, keyspace);
    }

    /**
     * Get fixed primary key values.
     */
    protected abstract Object[] getPKValues();

    /**
     * Generate primary key values: fixed + aggregation key.
     */
    protected Object[] getPKValues(String key) {
        return append(getPKValues(), key);
    }

    /**
     * Get aggregation key colum name.
     */
    private String getKeyColumn() {
        return pkColumns[pkColumns.length - 1];
    }

    private String[] getAllColumns() {
        return append(pkColumns, exchangeIdColumn, exchangeColumn);
    }
    //--------------------------------------------------------------------------
    // Service support

    @Override
    protected void doStart() throws Exception {
        sessionHolder.start();
        initInsertStatement();
        initSelectStatement();
        initDeleteStatement();
        initSelectKeyIdStatement();
        initDeleteIfIdStatement();
    }

    @Override
    protected void doStop() throws Exception {
        sessionHolder.stop();
    }

    // -------------------------------------------------------------------------
    // Add exchange to repository

    private void initInsertStatement() {
        String cql = generateInsert(table,
                getAllColumns(),
                false, ttl).toString();
        LOGGER.debug("Generated Insert {}", cql);
        insertStatement = applyConsistencyLevel(getSession().prepare(cql), writeConsistencyLevel);
    }

    /**
     * Insert or update exchange in aggregation table.
     */
    @Override
    public Exchange add(CamelContext camelContext, String key, Exchange exchange) {
        final Object[] idValues = getPKValues(key);
        LOGGER.debug("Inserting key {} exchange {}", idValues, exchange);
        try {
            ByteBuffer marshalledExchange = exchangeCodec.marshallExchange(camelContext, exchange);
            Object[] cqlParams = concat(idValues, new Object[]{exchange.getExchangeId(), marshalledExchange});
            getSession().execute(insertStatement.bind(cqlParams));
            return exchange;
        } catch (IOException iOException) {
            throw new CassandraAggregationException("Failed to write exchange", exchange, iOException);
        }
    }

    // -------------------------------------------------------------------------
    // Get exchange from repository

    protected void initSelectStatement() {
        String cql = generateSelect(table,
                getAllColumns(),
                pkColumns).toString();
        LOGGER.debug("Generated Select {}", cql);
        selectStatement = applyConsistencyLevel(getSession().prepare(cql), readConsistencyLevel);
    }

    /**
     * Get exchange from aggregation table by aggregation key.
     */
    @Override
    public Exchange get(CamelContext camelContext, String key) {
        Object[] pkValues = getPKValues(key);
        LOGGER.debug("Selecting key {} ", pkValues);
        Row row = getSession().execute(selectStatement.bind(pkValues)).one();
        Exchange exchange = null;
        if (row != null) {
            try {
                exchange = exchangeCodec.unmarshallExchange(camelContext, row.getBytes(exchangeColumn));
            } catch (IOException iOException) {
                throw new CassandraAggregationException("Failed to read exchange", exchange, iOException);
            } catch (ClassNotFoundException classNotFoundException) {
                throw new CassandraAggregationException("Failed to read exchange", exchange, classNotFoundException);
            }
        }
        return exchange;
    }

    // -------------------------------------------------------------------------
    // Confirm exchange in repository
    private void initDeleteIfIdStatement() {
        StringBuilder cqlBuilder = generateDelete(table, pkColumns, false);
        cqlBuilder.append(" if ").append(exchangeIdColumn).append("=?");
        String cql = cqlBuilder.toString();
        LOGGER.debug("Generated Delete If Id {}", cql);
        deleteIfIdStatement = applyConsistencyLevel(getSession().prepare(cql), writeConsistencyLevel);
    }

    /**
     * Remove exchange by Id from aggregation table.
     */
    @Override
    public void confirm(CamelContext camelContext, String exchangeId) {
        Object[] pkValues = getPKValues();
        String keyColumn = getKeyColumn();
        LOGGER.debug("Selecting Ids {} ", pkValues);
        List<Row> rows = selectKeyIds();
        for (Row row : rows) {
            if (row.getString(exchangeIdColumn).equals(exchangeId)) {
                String key = row.getString(keyColumn);
                Object[] cqlParams = append(pkValues, key, exchangeId);
                LOGGER.debug("Deleting If Id {} ", cqlParams);
                getSession().execute(deleteIfIdStatement.bind(cqlParams));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Remove exchange from repository

    private void initDeleteStatement() {
        String cql = generateDelete(table, pkColumns, false).toString();
        LOGGER.debug("Generated Delete {}", cql);
        deleteStatement = applyConsistencyLevel(getSession().prepare(cql), writeConsistencyLevel);
    }

    /**
     * Remove exchange by aggregation key from aggregation table.
     */
    @Override
    public void remove(CamelContext camelContext, String key, Exchange exchange) {
        Object[] idValues = getPKValues(key);
        LOGGER.debug("Deleting key {}", (Object) idValues);
        getSession().execute(deleteStatement.bind(idValues));
    }

    // -------------------------------------------------------------------------
    private void initSelectKeyIdStatement() {
        String cql = generateSelect(table,
                new String[]{getKeyColumn(), exchangeIdColumn}, // Key + Exchange Id columns
                pkColumns, pkColumns.length - 1).toString(); // Where fixed PK columns
        LOGGER.debug("Generated Select keys {}", cql);
        selectKeyIdStatement = applyConsistencyLevel(getSession().prepare(cql), readConsistencyLevel);
    }

    private List<Row> selectKeyIds() {
        Object[] pkValues = getPKValues();
        LOGGER.debug("Selecting keys {}", pkValues);
        return getSession().execute(selectKeyIdStatement.bind(pkValues)).all();
    }

    /**
     * Get aggregation keys from aggregation table.
     */
    @Override
    public Set<String> getKeys() {
        List<Row> rows = selectKeyIds();
        Set<String> keys = new HashSet<String>(rows.size());
        String keyColumnName = getPKColumns()[1];
        for (Row row : rows) {
            keys.add(row.getString(keyColumnName));
        }
        return keys;
    }


    // -------------------------------------------------------------------------
    // Getters and Setters

    public Session getSession() {
        return sessionHolder.getSession();
    }

    public void setSession(Session session) {
        this.sessionHolder = new CassandraSessionHolder(session);
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public String[] getPKColumns() {
        return pkColumns;
    }

    public void setPKColumns(String... pkColumns) {
        this.pkColumns = pkColumns;
    }

    public String getExchangeIdColumn() {
        return exchangeIdColumn;
    }

    public void setExchangeIdColumn(String exchangeIdColumn) {
        this.exchangeIdColumn = exchangeIdColumn;
    }

    public ConsistencyLevel getWriteConsistencyLevel() {
        return writeConsistencyLevel;
    }

    public void setWriteConsistencyLevel(ConsistencyLevel writeConsistencyLevel) {
        this.writeConsistencyLevel = writeConsistencyLevel;
    }

    public ConsistencyLevel getReadConsistencyLevel() {
        return readConsistencyLevel;
    }

    public void setReadConsistencyLevel(ConsistencyLevel readConsistencyLevel) {
        this.readConsistencyLevel = readConsistencyLevel;
    }

    public String getExchangeColumn() {
        return exchangeColumn;
    }

    public void setExchangeColumn(String exchangeColumnName) {
        this.exchangeColumn = exchangeColumnName;
    }

    public Integer getTtl() {
        return ttl;
    }

    public void setTtl(Integer ttl) {
        this.ttl = ttl;
    }

}
