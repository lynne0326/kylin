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

package org.apache.kylin.measure;

import java.util.List;
import java.util.Map;

import org.apache.kylin.measure.basic.BasicMeasureType;
import org.apache.kylin.measure.bitmap.BitmapMeasureType;
import org.apache.kylin.measure.dim.DimCountDistinctMeasureType;
import org.apache.kylin.measure.extendedcolumn.ExtendedColumnMeasureType;
import org.apache.kylin.measure.hllc.HLLCMeasureType;
import org.apache.kylin.measure.raw.RawMeasureType;
import org.apache.kylin.measure.topn.TopNMeasureType;
import org.apache.kylin.metadata.datatype.DataType;
import org.apache.kylin.metadata.datatype.DataTypeSerializer;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * Factory for MeasureType.
 * 
 * The factory registers itself by claiming the aggregation function and data type it supports,
 * to match a measure descriptor in cube definition.
 * 
 * E.g. HyperLogLog measure type claims "COUNT_DISCINT" as function and "hllc" as data type to
 * match measure descriptor:
 * <pre>
  {
    "name" : "SELLER_CNT_HLL",
    "function" : {
      "expression" : "COUNT_DISTINCT",        <----  function name
      "parameter" : {
        "type" : "column",
        "value" : "SELLER_ID",
        "next_parameter" : null
      },
      "returntype" : "hllc(10)"               <----  data type
    }
  }
</pre>
 * 
 * @param <T> the Java type of aggregation data object, e.g. HyperLogLogPlusCounter
 */
abstract public class MeasureTypeFactory<T> {

    /**
     * Create a measure type with specified aggregation function and data type.
     * 
     * @param funcName should always match this factory's claim <code>getAggrFunctionName()</code>
     * @param dataType should always match this factory's claim <code>getAggrDataTypeName()</code>
     */
    abstract public MeasureType<T> createMeasureType(String funcName, DataType dataType);

    /** Return the aggregation function this factory supports, like "COUNT_DISTINCT" */
    abstract public String getAggrFunctionName();

    /** Return the aggregation data type name this factory supports, like "hllc" */
    abstract public String getAggrDataTypeName();

    /** Return the Serializer for aggregation data object. Note a Serializer implementation must be thread-safe! */
    abstract public Class<? extends DataTypeSerializer<T>> getAggrDataTypeSerializer();

    // ============================================================================

    private static Map<String, List<MeasureTypeFactory<?>>> factories = Maps.newHashMap();
    private static List<MeasureTypeFactory<?>> defaultFactory = Lists.newArrayListWithCapacity(2);

    static {
        init();
    }

    public static synchronized void init() {
        if (factories.isEmpty() == false)
            return;

        List<MeasureTypeFactory<?>> factoryInsts = Lists.newArrayList();

        // two built-in advanced measure types
        factoryInsts.add(new HLLCMeasureType.Factory());
        factoryInsts.add(new BitmapMeasureType.Factory());
        factoryInsts.add(new TopNMeasureType.Factory());
        factoryInsts.add(new RawMeasureType.Factory());
        factoryInsts.add(new ExtendedColumnMeasureType.Factory());

        /*
         * Maybe do classpath search for more custom measure types?
         * More MeasureType cannot be configured via kylin.properties alone,
         * because used in coprocessor, the new classes must be on classpath
         * and be packaged into coprocessor jar. This inevitably involves
         * rebuild Kylin from code.
         */

        // register factories & data type serializers
        for (MeasureTypeFactory<?> factory : factoryInsts) {
            String funcName = factory.getAggrFunctionName();
            if (funcName.equals(funcName.toUpperCase()) == false)
                throw new IllegalArgumentException("Aggregation function name '" + funcName + "' must be in upper case");
            String dataTypeName = factory.getAggrDataTypeName();
            if (dataTypeName.equals(dataTypeName.toLowerCase()) == false)
                throw new IllegalArgumentException("Aggregation data type name '" + dataTypeName + "' must be in lower case");
            Class<? extends DataTypeSerializer<?>> serializer = factory.getAggrDataTypeSerializer();

            DataType.register(dataTypeName);
            DataTypeSerializer.register(dataTypeName, serializer);
            List<MeasureTypeFactory<?>> list = factories.get(funcName);
            if (list == null)
                factories.put(funcName, list = Lists.newArrayListWithCapacity(2));
            list.add(factory);
        }

        defaultFactory.add(new BasicMeasureType.Factory());
    }

    public static MeasureType<?> create(String funcName, String dataType) {
        return create(funcName, DataType.getType(dataType));
    }

    public static MeasureType<?> createNoRewriteFieldsMeasureType(String funcName, DataType dataType) {
        // currently only has DimCountDistinctAgg
        if (funcName.equalsIgnoreCase("COUNT_DISTINCT")) {
            return new DimCountDistinctMeasureType.DimCountDistinctMeasureTypeFactory().createMeasureType(funcName, dataType);
        }

        throw new UnsupportedOperationException("No measure type found.");
    }

    public static MeasureType<?> create(String funcName, DataType dataType) {
        funcName = funcName.toUpperCase();

        List<MeasureTypeFactory<?>> factory = factories.get(funcName);
        if (factory == null)
            factory = defaultFactory;

        // a special case where in early stage of sql parsing, the data type is unknown; only needRewrite() is required at that stage
        if (dataType == null) {
            return new NeedRewriteOnlyMeasureType(funcName, factory);
        }

        // the normal case, only one factory for a function
        if (factory.size() == 1) {
            return factory.get(0).createMeasureType(funcName, dataType);
        }

        // sometimes multiple factories are registered for the same function, then data types must tell them apart
        for (MeasureTypeFactory<?> f : factory) {
            if (f.getAggrDataTypeName().equals(dataType.getName()))
                return f.createMeasureType(funcName, dataType);
        }
        throw new IllegalStateException();
    }

    @SuppressWarnings("rawtypes")
    private static class NeedRewriteOnlyMeasureType extends MeasureType {

        private Boolean needRewrite;

        public NeedRewriteOnlyMeasureType(String funcName, List<MeasureTypeFactory<?>> factory) {
            for (MeasureTypeFactory<?> f : factory) {
                boolean b = f.createMeasureType(funcName, null).needRewrite();
                if (needRewrite == null)
                    needRewrite = Boolean.valueOf(b);
                else if (needRewrite.booleanValue() != b)
                    throw new IllegalStateException("needRewrite() of factorys " + factory + " does not have consensus");
            }
        }

        @Override
        public MeasureIngester newIngester() {
            throw new UnsupportedOperationException();
        }

        @Override
        public MeasureAggregator newAggregator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean needRewrite() {
            return needRewrite;
        }

        @Override
        public Class getRewriteCalciteAggrFunctionClass() {
            throw new UnsupportedOperationException();
        }
        
    }
}
