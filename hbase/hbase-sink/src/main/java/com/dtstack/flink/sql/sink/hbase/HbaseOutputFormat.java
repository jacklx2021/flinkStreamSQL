/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

 

package com.dtstack.flink.sql.sink.hbase;

import com.dtstack.flink.sql.enums.EUpdateMode;
import com.dtstack.flink.sql.outputformat.AbstractDtRichOutputFormat;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.types.Row;
import org.apache.flink.util.Preconditions;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author: jingzhen@dtstack.com
 * date: 2017-6-29
 */
public class HbaseOutputFormat extends AbstractDtRichOutputFormat<Tuple2> {

    private static final Logger LOG = LoggerFactory.getLogger(HbaseOutputFormat.class);

    private String host;
    private String zkParent;
    private String rowkey;
    private String tableName;
    private String[] columnNames;
    private String updateMode;
    private String[] columnTypes;
    private Map<String, String> columnNameFamily;

    private boolean kerberosAuthEnable;
    private String regionserverKeytabFile;
    private String regionserverPrincipal;
    private String securityKrb5Conf;
    private String zookeeperSaslClient;

    private String[] families;
    private String[] qualifiers;

    private transient org.apache.hadoop.conf.Configuration conf;
    private transient Connection conn;
    private transient Table table;

    @Override
    public void configure(Configuration parameters) {
        LOG.warn("---configure---");
        conf = HBaseConfiguration.create();
        conf.set("hbase.zookeeper.quorum", host);
        if (zkParent != null && !"".equals(zkParent)) {
            conf.set("zookeeper.znode.parent", zkParent);
        }
        LOG.warn("---configure end ---");
    }

    @Override
    public void open(int taskNumber, int numTasks) throws IOException {
        LOG.warn("---open---");
        conn = ConnectionFactory.createConnection(conf);
        table = conn.getTable(TableName.valueOf(tableName));
        LOG.warn("---open end(get table from hbase) ---");
        initMetric();
    }

    @Override
    public void writeRecord(Tuple2 tuple2) {
        Tuple2<Boolean, Row> tupleTrans = tuple2;
        Boolean retract = tupleTrans.f0;
        Row row = tupleTrans.f1;
        if (retract) {
            dealInsert(row);
        } else if (!retract && StringUtils.equalsIgnoreCase(updateMode, EUpdateMode.UPSERT.name())) {
            dealDelete(row);
        }
    }

    protected void dealInsert(Row record) {
        Put put = getPutByRow(record);
        if (put == null) {
            return;
        }

        try {
            table.put(put);
        } catch (IOException e) {
            if (outDirtyRecords.getCount() % DIRTY_PRINT_FREQUENCY == 0 || LOG.isDebugEnabled()) {
                LOG.error("record insert failed ..{}", record.toString());
                LOG.error("", e);
            }
            outDirtyRecords.inc();
        }

        if (outRecords.getCount() % ROW_PRINT_FREQUENCY == 0) {
            LOG.info(record.toString());
        }
        outRecords.inc();
    }

    protected void dealDelete(Row record) {
        String rowKey = buildRowKey(record);
        if (!StringUtils.isEmpty(rowKey)) {
            Delete delete = new Delete(Bytes.toBytes(rowKey));
            try {
                table.delete(delete);
            } catch (IOException e) {
                if (outDirtyRecords.getCount() % DIRTY_PRINT_FREQUENCY == 0 || LOG.isDebugEnabled()) {
                    LOG.error("record insert failed ..{}", record.toString());
                    LOG.error("", e);
                }
                outDirtyRecords.inc();
            }
            if (outRecords.getCount() % ROW_PRINT_FREQUENCY == 0) {
                LOG.info(record.toString());
            }
            outRecords.inc();
        }
    }

    private Put getPutByRow(Row record) {
        String rowKey = buildRowKey(record);
        if (StringUtils.isEmpty(rowKey)) {
            return null;
        }
        Put put = new Put(rowKey.getBytes());
        for (int i = 0; i < record.getArity(); ++i) {
            Object fieldVal = record.getField(i);
            if (fieldVal == null) {
                continue;
            }
            byte[] val = fieldVal.toString().getBytes();
            byte[] cf = families[i].getBytes();
            byte[] qualifier = qualifiers[i].getBytes();

            put.addColumn(cf, qualifier, val);
        }
        return put;
    }

    private String buildRowKey(Row record) {
        String rowKeyValues = getRowKeyValues(record);
        // all rowkey not null
        if (StringUtils.isBlank(rowKeyValues)) {
            LOG.error("row key value must not null,record is ..{}", record);
            outDirtyRecords.inc();
            return "";
        }
        return rowKeyValues;
    }

    private String getRowKeyValues(Row record) {
        Map<String, Object> row = rowConvertMap(record);
        RowKeyBuilder rowKeyBuilder = new RowKeyBuilder();
        rowKeyBuilder.init(rowkey);
        return rowKeyBuilder.getRowKey(row);
    }

    private Map<String, Object> rowConvertMap(Row record){
        Map<String, Object> rowValue = Maps.newHashMap();
        for(int i = 0; i < columnNames.length; i++){
            rowValue.put(columnNames[i], record.getField(i));
        }
        return rowValue;
    }

    @Override
    public void close() throws IOException {
        if (conn != null) {
            conn.close();
            conn = null;
        }
    }

    private HbaseOutputFormat() {
    }

    public static HbaseOutputFormatBuilder buildHbaseOutputFormat() {
        return new HbaseOutputFormatBuilder();
    }

    public static class HbaseOutputFormatBuilder {

        private HbaseOutputFormat format;

        private HbaseOutputFormatBuilder() {
            format = new HbaseOutputFormat();
        }

        public HbaseOutputFormatBuilder setHost(String host) {
            format.host = host;
            return this;
        }

        public HbaseOutputFormatBuilder setZkParent(String parent) {
            format.zkParent = parent;
            return this;
        }


        public HbaseOutputFormatBuilder setTable(String tableName) {
            format.tableName = tableName;
            return this;
        }

        public HbaseOutputFormatBuilder setRowkey(String rowkey) {
            format.rowkey = rowkey;
            return this;
        }

        public HbaseOutputFormatBuilder setUpdateMode(String updateMode) {
            format.updateMode = updateMode;
            return this;
        }

        public HbaseOutputFormatBuilder setColumnNames(String[] columnNames) {
            format.columnNames = columnNames;
            return this;
        }

        public HbaseOutputFormatBuilder setColumnTypes(String[] columnTypes) {
            format.columnTypes = columnTypes;
            return this;
        }

        public HbaseOutputFormatBuilder setColumnNameFamily(Map<String, String> columnNameFamily) {
            format.columnNameFamily = columnNameFamily;
            return this;
        }
        public HbaseOutputFormatBuilder setKerberosAuthEnable(boolean kerberosAuthEnable) {
            format.kerberosAuthEnable = kerberosAuthEnable;
            return this;
        }

        public HbaseOutputFormatBuilder setRegionserverKeytabFile(String regionserverKeytabFile) {
            format.regionserverKeytabFile = regionserverKeytabFile;
            return this;
        }

        public HbaseOutputFormatBuilder setRegionserverPrincipal(String regionserverPrincipal) {
            format.regionserverPrincipal = regionserverPrincipal;
            return this;
        }

        public HbaseOutputFormatBuilder setSecurityKrb5Conf(String securityKrb5Conf) {
            format.securityKrb5Conf = securityKrb5Conf;
            return this;
        }

        public HbaseOutputFormatBuilder setZookeeperSaslClient(String zookeeperSaslClient) {
            format.zookeeperSaslClient = zookeeperSaslClient;
            return this;
        }


        public HbaseOutputFormat finish() {
            Preconditions.checkNotNull(format.host, "zookeeperQuorum should be specified");
            Preconditions.checkNotNull(format.tableName, "tableName should be specified");
            Preconditions.checkNotNull(format.columnNames, "columnNames should be specified");
            Preconditions.checkArgument(format.columnNames.length != 0, "columnNames length should not be zero");

            String[] families = new String[format.columnNames.length];
            String[] qualifiers = new String[format.columnNames.length];

            if (format.columnNameFamily != null) {
                Set<String> keySet = format.columnNameFamily.keySet();
                String[] columns = keySet.toArray(new String[keySet.size()]);
                for (int i = 0; i < columns.length; ++i) {
                    String col = columns[i];
                    String[] part = col.split(":");
                    families[i] = part[0];
                    qualifiers[i] = part[1];
                }
            }
            format.families = families;
            format.qualifiers = qualifiers;

            return format;
        }

    }


}
