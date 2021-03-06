/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.segment;

import org.apache.druid.java.util.common.io.Closer;
import org.apache.druid.query.dimension.DimensionSpec;
import org.apache.druid.query.extraction.ExtractionFn;
import org.apache.druid.segment.column.BaseColumn;
import org.apache.druid.segment.column.ColumnCapabilities;
import org.apache.druid.segment.column.ColumnHolder;
import org.apache.druid.segment.column.DictionaryEncodedColumn;
import org.apache.druid.segment.column.ValueType;
import org.apache.druid.segment.data.ReadableOffset;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * The basic implementation of {@link ColumnSelectorFactory} over a historical segment (i. e. {@link QueryableIndex}).
 * It's counterpart for incremental index is {@link
 * org.apache.druid.segment.incremental.IncrementalIndexColumnSelectorFactory}.
 */
class QueryableIndexColumnSelectorFactory implements ColumnSelectorFactory
{
  private final QueryableIndex index;
  private final VirtualColumns virtualColumns;
  private final boolean descending;
  private final Closer closer;
  protected final ReadableOffset offset;

  // Share Column objects, since they cache decompressed buffers internally, and we can avoid recomputation if the
  // same column is used by more than one part of a query.
  private final Map<String, BaseColumn> columnCache;

  // Share selectors too, for the same reason that we cache columns (they may cache things internally).
  private final Map<DimensionSpec, DimensionSelector> dimensionSelectorCache;
  private final Map<String, ColumnValueSelector> valueSelectorCache;

  QueryableIndexColumnSelectorFactory(
      QueryableIndex index,
      VirtualColumns virtualColumns,
      boolean descending,
      Closer closer,
      ReadableOffset offset,
      Map<String, BaseColumn> columnCache
  )
  {
    this.index = index;
    this.virtualColumns = virtualColumns;
    this.descending = descending;
    this.closer = closer;
    this.offset = offset;
    this.columnCache = columnCache;
    this.dimensionSelectorCache = new HashMap<>();
    this.valueSelectorCache = new HashMap<>();
  }

  @Override
  public DimensionSelector makeDimensionSelector(DimensionSpec dimensionSpec)
  {
    return dimensionSelectorCache.computeIfAbsent(
        dimensionSpec,
        spec -> {
          if (virtualColumns.exists(spec.getDimension())) {
            return virtualColumns.makeDimensionSelector(spec, this);
          }

          return spec.decorate(makeDimensionSelectorUndecorated(spec));
        }
    );
  }

  private DimensionSelector makeDimensionSelectorUndecorated(DimensionSpec dimensionSpec)
  {
    final String dimension = dimensionSpec.getDimension();
    final ExtractionFn extractionFn = dimensionSpec.getExtractionFn();

    final ColumnHolder columnHolder = index.getColumnHolder(dimension);
    if (columnHolder == null) {
      return DimensionSelector.constant(null, extractionFn);
    }

    if (dimension.equals(ColumnHolder.TIME_COLUMN_NAME)) {
      return new SingleScanTimeDimensionSelector(makeColumnValueSelector(dimension), extractionFn, descending);
    }

    ValueType type = columnHolder.getCapabilities().getType();
    if (type.isNumeric()) {
      return type.makeNumericWrappingDimensionSelector(makeColumnValueSelector(dimension), extractionFn);
    }

    final DictionaryEncodedColumn column = getCachedColumn(dimension, DictionaryEncodedColumn.class);

    if (column != null) {
      return column.makeDimensionSelector(offset, extractionFn);
    } else {
      return DimensionSelector.constant(null, extractionFn);
    }
  }

  @Override
  public ColumnValueSelector<?> makeColumnValueSelector(String columnName)
  {
    return valueSelectorCache.computeIfAbsent(
        columnName,
        name -> {
          if (virtualColumns.exists(columnName)) {
            return virtualColumns.makeColumnValueSelector(columnName, this);
          }

          BaseColumn column = getCachedColumn(columnName, BaseColumn.class);

          if (column != null) {
            return column.makeColumnValueSelector(offset);
          } else {
            return NilColumnValueSelector.instance();
          }
        }
    );
  }

  @Nullable
  @SuppressWarnings("unchecked")
  private <T extends BaseColumn> T getCachedColumn(final String columnName, final Class<T> clazz)
  {
    return (T) columnCache.computeIfAbsent(
        columnName,
        name -> {
          ColumnHolder holder = index.getColumnHolder(name);
          if (holder != null && clazz.isAssignableFrom(holder.getColumn().getClass())) {
            return closer.register(holder.getColumn());
          } else {
            // Return null from the lambda in computeIfAbsent() results in no recorded value in the columnCache and
            // the column variable is set to null.
            return null;
          }
        }
    );
  }

  @Override
  @Nullable
  public ColumnCapabilities getColumnCapabilities(String columnName)
  {
    if (virtualColumns.exists(columnName)) {
      return virtualColumns.getColumnCapabilities(columnName);
    }

    return QueryableIndexStorageAdapter.getColumnCapabilities(index, columnName);
  }
}
