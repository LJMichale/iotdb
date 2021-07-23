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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.engine.selectinto;

import org.apache.iotdb.db.exception.metadata.IllegalPathException;
import org.apache.iotdb.db.metadata.PartialPath;
import org.apache.iotdb.db.qp.physical.crud.InsertTabletPlan;
import org.apache.iotdb.tsfile.exception.write.UnSupportedDataTypeException;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.read.common.Field;
import org.apache.iotdb.tsfile.read.common.RowRecord;
import org.apache.iotdb.tsfile.utils.Binary;
import org.apache.iotdb.tsfile.utils.BitMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** internallyConstructNewPlan -> collectRowRecord * N -> generateInsertTabletPlan */
public class InsertTabletPlanGenerator {

  private final String targetDevice;
  // the index of target path in into clause -> the index of output column of query data set
  private final List<Integer> targetPathIndexToQueryDataSetIndex;
  // the index of target path in into clause -> the measurement id of the target path
  private final List<String> targetMeasurementIds;

  private final int fetchSize;

  // the following fields are used to construct plan
  private int rowCount;
  private long[] times;
  private Object[] columns;
  private BitMap[] bitMaps;
  private TSDataType[] dataTypes;

  private int numberOfInitializedColumns;

  public InsertTabletPlanGenerator(String targetDevice, int fetchSize) {
    this.targetDevice = targetDevice;
    targetPathIndexToQueryDataSetIndex = new ArrayList<>();
    targetMeasurementIds = new ArrayList<>();

    this.fetchSize = fetchSize;
  }

  public void collectTargetPathInformation(String targetMeasurementId, int queryDataSetIndex) {
    targetMeasurementIds.add(targetMeasurementId);
    targetPathIndexToQueryDataSetIndex.add(queryDataSetIndex);
  }

  public void internallyConstructNewPlan() {
    rowCount = 0;
    times = new long[fetchSize];
    columns = new Object[targetMeasurementIds.size()];
    bitMaps = new BitMap[targetMeasurementIds.size()];
    for (int i = 0; i < bitMaps.length; ++i) {
      bitMaps[i] = new BitMap(fetchSize);
      bitMaps[i].markAll();
    }
    dataTypes = new TSDataType[targetMeasurementIds.size()];
  }

  public void collectRowRecord(RowRecord rowRecord) {
    if (numberOfInitializedColumns != columns.length) {
      List<Integer> initializedDataTypeIndexes = trySetDataTypes(rowRecord);
      tryInitColumns(initializedDataTypeIndexes);
      numberOfInitializedColumns += initializedDataTypeIndexes.size();
    }

    times[rowCount] = rowRecord.getTimestamp();

    for (int i = 0; i < columns.length; ++i) {
      Field field = rowRecord.getFields().get(targetPathIndexToQueryDataSetIndex.get(i));

      // if the field is NULL
      if (field == null || field.getDataType() == null) {
        // bit in bitMaps are marked as 1 (NULL) by default
        continue;
      }

      bitMaps[i].unmark(rowCount);
      switch (field.getDataType()) {
        case INT32:
          ((int[]) columns[i])[rowCount] = field.getIntV();
          break;
        case INT64:
          ((long[]) columns[i])[rowCount] = field.getLongV();
          break;
        case FLOAT:
          ((float[]) columns[i])[rowCount] = field.getFloatV();
          break;
        case DOUBLE:
          ((double[]) columns[i])[rowCount] = field.getDoubleV();
          break;
        case BOOLEAN:
          ((boolean[]) columns[i])[rowCount] = field.getBoolV();
          break;
        case TEXT:
          ((Binary[]) columns[i])[rowCount] = field.getBinaryV();
          break;
        default:
          throw new UnSupportedDataTypeException(
              String.format(
                  "data type %s is not supported when convert data at client",
                  field.getDataType()));
      }
    }

    ++rowCount;
  }

  private List<Integer> trySetDataTypes(RowRecord rowRecord) {
    List<Integer> initializedDataTypeIndexes = new ArrayList<>();
    List<Field> fields = rowRecord.getFields();

    for (int i = 0; i < dataTypes.length; ++i) {
      // if the data type is already set
      if (dataTypes[i] != null) {
        continue;
      }

      // get the field index of the row record
      int queryDataSetIndex = targetPathIndexToQueryDataSetIndex.get(i);
      // if the field is not null
      if (fields.get(queryDataSetIndex) != null
          && fields.get(queryDataSetIndex).getDataType() != null) {
        // set the data type to the field type
        dataTypes[i] = fields.get(queryDataSetIndex).getDataType();
        initializedDataTypeIndexes.add(i);
      }
    }

    for (int i = 0; i < dataTypes.length; ++i) {
      if (dataTypes[i] == null && fields.get(i) != null && fields.get(i).getDataType() != null) {
        dataTypes[i] = fields.get(i).getDataType();
        initializedDataTypeIndexes.add(i);
      }
    }
    return initializedDataTypeIndexes;
  }

  private void tryInitColumns(List<Integer> initializedDataTypeIndexes) {
    for (int i : initializedDataTypeIndexes) {
      switch (dataTypes[i]) {
        case BOOLEAN:
          columns[i] = new boolean[fetchSize];
          break;
        case INT32:
          columns[i] = new int[fetchSize];
          break;
        case INT64:
          columns[i] = new long[fetchSize];
          break;
        case FLOAT:
          columns[i] = new float[fetchSize];
          break;
        case DOUBLE:
          columns[i] = new double[fetchSize];
          break;
        case TEXT:
          columns[i] = new Binary[fetchSize];
          break;
        default:
          throw new UnSupportedDataTypeException(
              String.format(
                  "data type %s is not supported when convert data at client", dataTypes[i]));
      }
    }
  }

  public InsertTabletPlan generateInsertTabletPlan() throws IllegalPathException {
    List<String> nonEmptyColumnNames = new ArrayList<>();

    int countOfNonEmptyColumns = 0;
    for (int i = 0; i < columns.length; ++i) {
      if (columns[i] == null) {
        continue;
      }

      nonEmptyColumnNames.add(targetMeasurementIds.get(i));
      times[countOfNonEmptyColumns] = times[i];
      columns[countOfNonEmptyColumns] = columns[i];
      bitMaps[countOfNonEmptyColumns] = bitMaps[i];
      dataTypes[countOfNonEmptyColumns] = dataTypes[i];

      ++countOfNonEmptyColumns;
    }

    InsertTabletPlan insertTabletPlan =
        new InsertTabletPlan(new PartialPath(targetDevice), nonEmptyColumnNames);

    insertTabletPlan.setAligned(false);
    insertTabletPlan.setRowCount(rowCount);

    if (countOfNonEmptyColumns == columns.length) {
      insertTabletPlan.setTimes(times);
      insertTabletPlan.setColumns(columns);
      insertTabletPlan.setBitMaps(bitMaps);
      insertTabletPlan.setDataTypes(dataTypes);
    } else {
      insertTabletPlan.setTimes(Arrays.copyOf(times, countOfNonEmptyColumns));
      insertTabletPlan.setColumns(Arrays.copyOf(columns, countOfNonEmptyColumns));
      insertTabletPlan.setBitMaps(Arrays.copyOf(bitMaps, countOfNonEmptyColumns));
      insertTabletPlan.setDataTypes(Arrays.copyOf(dataTypes, countOfNonEmptyColumns));
    }

    return insertTabletPlan;
  }
}
