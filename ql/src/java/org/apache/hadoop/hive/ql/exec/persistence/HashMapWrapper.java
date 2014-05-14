/**
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

package org.apache.hadoop.hive.ql.exec.persistence;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.exec.ExprNodeEvaluator;
import org.apache.hadoop.hive.ql.exec.vector.VectorHashKeyWrapper;
import org.apache.hadoop.hive.ql.exec.vector.VectorHashKeyWrapperBatch;
import org.apache.hadoop.hive.ql.exec.vector.expressions.VectorExpressionWriter;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.serde2.SerDe;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.WriteBuffers;
import org.apache.hadoop.hive.serde2.ByteStream.Output;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.Writable;


/**
 * Simple wrapper for persistent Hashmap implementing only the put/get/remove/clear interface. The
 * main memory hash table acts as a cache and all put/get will operate on it first. If the size of
 * the main memory hash table exceeds a certain threshold, new elements will go into the persistent
 * hash table.
 */

public class HashMapWrapper extends AbstractMapJoinTableContainer implements Serializable {
  private static final long serialVersionUID = 1L;
  protected static final Log LOG = LogFactory.getLog(HashMapWrapper.class);

  // default threshold for using main memory based HashMap
  private static final int THRESHOLD = 1000000;
  private static final float LOADFACTOR = 0.75f;
  private final HashMap<MapJoinKey, MapJoinRowContainer> mHash; // main memory HashMap
  private MapJoinKey lastKey = null;
  private final boolean useLazyRows;
  private final boolean useOptimizedKeys;
  private Output output = new Output(0); // Reusable output for serialization

  public HashMapWrapper(Map<String, String> metaData) {
    super(metaData);
    int threshold = Integer.parseInt(metaData.get(THESHOLD_NAME));
    float loadFactor = Float.parseFloat(metaData.get(LOAD_NAME));
    mHash = new HashMap<MapJoinKey, MapJoinRowContainer>(threshold, loadFactor);
    useLazyRows = useOptimizedKeys = false;
  }

  public HashMapWrapper() {
    this(HiveConf.ConfVars.HIVEHASHTABLETHRESHOLD.defaultIntVal,
        HiveConf.ConfVars.HIVEHASHTABLELOADFACTOR.defaultFloatVal, false, false);
  }

  public HashMapWrapper(Configuration hconf) {
    this(HiveConf.getIntVar(hconf, HiveConf.ConfVars.HIVEHASHTABLETHRESHOLD),
        HiveConf.getFloatVar(hconf, HiveConf.ConfVars.HIVEHASHTABLELOADFACTOR),
        HiveConf.getBoolVar(hconf, HiveConf.ConfVars.HIVEMAPJOINLAZYHASHTABLE),
        HiveConf.getBoolVar(hconf, HiveConf.ConfVars.HIVEMAPJOINUSEOPTIMIZEDKEYS));
  }

  private HashMapWrapper(
      int threshold, float loadFactor, boolean useLazyRows, boolean useOptimizedKeys) {
    super(createConstructorMetaData(threshold, loadFactor));
    mHash = new HashMap<MapJoinKey, MapJoinRowContainer>(threshold, loadFactor);
    this.useLazyRows = useLazyRows;
    this.useOptimizedKeys = useOptimizedKeys;
  }

  @Override
  public MapJoinRowContainer get(MapJoinKey key) {
    return mHash.get(key);
  }

  @Override
  public void put(MapJoinKey key, MapJoinRowContainer value) {
    mHash.put(key, value);
  }

  @Override
  public int size() {
    return mHash.size();
  }
  @Override
  public Set<Entry<MapJoinKey, MapJoinRowContainer>> entrySet() {
    return mHash.entrySet();
  }
  @Override
  public void clear() {
    mHash.clear();
  }

  @Override
  public MapJoinKey putRow(MapJoinObjectSerDeContext keyContext, Writable currentKey,
      MapJoinObjectSerDeContext valueContext, Writable currentValue)
          throws SerDeException, HiveException {
    // We pass key in as reference, to find out quickly if optimized keys can be used.
    // However, we do not reuse the object since we are putting them into the hashmap.
    // Later, we don't create optimized keys in MapJoin if hash map doesn't have optimized keys.
    if (lastKey == null && !useOptimizedKeys) {
      lastKey = new MapJoinKeyObject();
    }

    lastKey = MapJoinKey.read(output, lastKey, keyContext, currentKey, false);
    LazyFlatRowContainer values = (LazyFlatRowContainer)get(lastKey);
    if (values == null) {
      values = new LazyFlatRowContainer();
      put(lastKey, values);
    }
    values.add(valueContext, (BytesWritable)currentValue, useLazyRows);
    return lastKey;
  }

  @Override
  public ReusableGetAdaptor createGetter(MapJoinKey keyTypeFromLoader) {
    return new GetAdaptor(keyTypeFromLoader);
  }

  private class GetAdaptor implements ReusableGetAdaptor {
    private MapJoinKey key;
    private MapJoinRowContainer currentValue;
    private Output output;
    private boolean isFirstKey = true;

    public GetAdaptor(MapJoinKey key) {
      this.key = key;
    }

    @Override
    public void setFromVector(VectorHashKeyWrapper kw, VectorExpressionWriter[] keyOutputWriters,
        VectorHashKeyWrapperBatch keyWrapperBatch) throws HiveException {
      if (output == null) {
        output = new Output(0);
      }
      key = MapJoinKey.readFromVector(
          output, key, kw, keyOutputWriters, keyWrapperBatch, !isFirstKey);
      isFirstKey = false;
      this.currentValue = mHash.get(key);
    }

    @Override
    public void setFromRow(Object row, List<ExprNodeEvaluator> fields,
        List<ObjectInspector> ois) throws HiveException {
      if (output == null) {
        output = new Output(0);
      }
      key = MapJoinKey.readFromRow(output, key, row, fields, ois, !isFirstKey);
      isFirstKey = false;
      this.currentValue = mHash.get(key);
    }

    @Override
    public void setFromOther(ReusableGetAdaptor other) {
      assert other instanceof GetAdaptor;
      GetAdaptor other2 = (GetAdaptor)other;
      this.key = other2.key;
      this.isFirstKey = other2.isFirstKey;
      this.currentValue = mHash.get(key);
    }

    @Override
    public boolean hasAnyNulls(int fieldCount, boolean[] nullsafes) {
      return key.hasAnyNulls(fieldCount, nullsafes);
    }

    @Override
    public MapJoinRowContainer getCurrentRows() {
      return currentValue;
    }
  }

  @Override
  public void seal() {
    // Nothing to do.
  }

  @Override
  public MapJoinKey getAnyKey() {
    return mHash.isEmpty() ? null : mHash.keySet().iterator().next();
  }

  @Override
  public void dumpMetrics() {
    // Nothing to do.
  }
}
