// This file is part of OpenTSDB.
// Copyright (C) 2015  The OpenTSDB Authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 2.1 of the License, or (at your
// option) any later version.  This program is distributed in the hope that it
// will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
// General Public License for more details.  You should have received a copy
// of the GNU Lesser General Public License along with this program.  If not,
// see <http://www.gnu.org/licenses/>.
package net.opentsdb.rollup;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import net.opentsdb.core.Aggregators;
import net.opentsdb.core.Const;
import net.opentsdb.core.DataPoint;
import net.opentsdb.core.IllegalDataException;
import net.opentsdb.core.Internal;
import net.opentsdb.core.RowKey;
import net.opentsdb.core.RowSeq;
import net.opentsdb.core.SeekableView;
import net.opentsdb.core.TSDB;
import net.opentsdb.core.Tags;
import net.opentsdb.core.iRowSeq;
import net.opentsdb.meta.Annotation;

import org.hbase.async.Bytes;
import org.hbase.async.Bytes.ByteMap;
import org.hbase.async.KeyValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stumbleupon.async.Deferred;

/**
 * Represents a read-only sequence of continuous HBase rows.
 * <p>
 * This class stores in memory the data of one or more continuous
 * HBase rows for a given time series. To consolidate memory, the data points
 * are stored in two byte arrays: one for the time offsets/flags and another
 * for the values. Access is granted via pointers.
 * @since 2.4
 */
public final class RollupSeq implements iRowSeq {
  private static final Logger LOG = LoggerFactory.getLogger(RollupSeq.class);
  
  /** The {@link TSDB} instance we belong to. */
  private final TSDB tsdb;

  /** The RollupQuery object holds information about a rollup interval and 
   *  rollup aggregator */
  private final RollupQuery rollup_query;

  /** Whether or not we need counts with our data, e.g. to compute the average */
  private final boolean need_count;
  private final int agg_id;
  private final int count_id;
  
  /** First row key. */
  protected byte[] key;
  
  /** The qualifier and values for the request rollup type or SUM if the user
   * asked for AVG or DEV. */ 
  protected byte[] qualifiers;
  protected byte[] values; 
  protected long last_value_ts;

  /** If the user asked for AVG or DEV then we store the COUNT values here */
  protected byte[] count_qualifiers;
  protected byte[] count_values;
  protected long last_count_ts;
  
  /** An array of indices for the arrays above */
  protected int[] indices; // 0 = q, 1 = v, 2 = cq, 3 = cv

  /** Sentinels to make sure we don't sneak in any out-of-order values */
  protected int last_offset = -1;
  protected int last_count_offset = -1;
  
  /**
   * Default constructor.
   * @param tsdb The TSDB to which we belong
   * @param rollup_query holds information about a rollup interval and 
   *  rollup aggregator
   */
  public RollupSeq(final TSDB tsdb, final RollupQuery rollup_query) {
    this.tsdb = tsdb;
    this.rollup_query = rollup_query;
    
    // TODO - others
    need_count = rollup_query.getGroupBy() == Aggregators.AVG ||
                 rollup_query.getGroupBy() == Aggregators.DEV;
    
    // WARNING overallocation
    qualifiers = new byte[rollup_query.getRollupInterval().getIntervals() * 2];
    // NEED to dynamically expand this sucker
    values = new byte[rollup_query.getRollupInterval().getIntervals()];
    
    if (need_count) {
      count_qualifiers = new byte[rollup_query.getRollupInterval().getIntervals() * 2];
      count_values = new byte[rollup_query.getRollupInterval().getIntervals()];
      indices = new int[4];
      agg_id = tsdb.getRollupConfig().getIdForAggregator("sum");
      count_id = tsdb.getRollupConfig().getIdForAggregator("count");
    } else {
      indices = new int[2];
      agg_id = tsdb.getRollupConfig()
          .getIdForAggregator(rollup_query.getRollupAgg().toString());
      count_id = tsdb.getRollupConfig().getIdForAggregator("count");
    }
  }
    
  /**
   * Sets the row this instance holds in RAM using a row from a scanner.
   * @param column The compacted HBase row to set.
   * @throws IllegalStateException if this method was already called.
   */
  public void setRow(final KeyValue column) {
    //This api will be called only with the KeyValues from rollup table, as per
    //the scan logic
    if (key != null) {
      throw new IllegalStateException("setRow was already called on " + this);
    }
    
    key = column.key();
    
    //Check whether the cell is generated by same rollup aggregator
    if (need_count) {
      System.out.println("AGG ID: " + agg_id + "  COUNT ID: " + count_id + "  MASK: " + (column.qualifier()[0] & RollupUtils.AGGREGATOR_MASK));
      if ((column.qualifier()[0] & RollupUtils.AGGREGATOR_MASK) == agg_id) {
        append(column, false, false);
      } else if ((column.qualifier()[0] & RollupUtils.AGGREGATOR_MASK) == count_id) {
        append(column, true, false);
      // OLD style for Yahoo!
      } else if (Bytes.memcmp(RollupQuery.SUM, column.qualifier(), 0, RollupQuery.SUM.length) == 0) {
        append(column, false, true);
      } else if (Bytes.memcmp(RollupQuery.COUNT, column.qualifier(), 0, RollupQuery.COUNT.length) == 0) {
        append(column, true, true);
      } else {
        throw new IllegalDataException("Attempt to add a different aggrregate cell ="
            + column + ", expected aggregator either SUM or COUNT");
      }
    } else {
      if ((column.qualifier()[0] & RollupUtils.AGGREGATOR_MASK) == agg_id) {
        append(column, false, false);
      } else if (Bytes.memcmp(column.qualifier(), rollup_query.getRollupAggPrefix(), 0,
            rollup_query.getRollupAggPrefix().length) == 0) {
        append(column, false, true);
      } else {
        throw new IllegalDataException("Attempt to add a different aggrregate cell ="
            + column + ", expected aggregator " + Bytes.pretty(
            rollup_query.getRollupAggPrefix()));
      }
    }
  }
  
  /**This method of parent/super class is not applicable to Rollup data point.
   * @param column The compacted HBase row to merge into this instance.
   * @throws IllegalStateException if {@link #setRow} wasn't called first.
   * @throws IllegalArgumentException if the data points in the argument
   * do not belong to the same row as this RowSeq
   */
  public void addRow(final KeyValue column) {
    if (key == null) {
      throw new IllegalStateException("setRow was never called on " + this);
    }
    
    if (Bytes.memcmp(column.key(), key, Const.SALT_WIDTH(), 
        key.length - Const.SALT_WIDTH()) != 0) {
      throw new IllegalDataException("Attempt to add a different row="
          + column + ", this=" + this);
    }
    
    //Check whether the cell is generated by same rollup aggregator
    if (need_count) {
      if ((column.qualifier()[0] & RollupUtils.AGGREGATOR_MASK) == agg_id) {
        append(column, false, false);
      } else if ((column.qualifier()[0] & RollupUtils.AGGREGATOR_MASK) == count_id) {
        append(column, true, false);
      // OLD style for Yahoo!
      } else if (Bytes.memcmp(RollupQuery.SUM, column.qualifier(), 0, RollupQuery.SUM.length) == 0) {
        append(column, false, true);
      } else if (Bytes.memcmp(RollupQuery.COUNT, column.qualifier(), 0, RollupQuery.COUNT.length) == 0) {
        append(column, true, true);
      } else {
        throw new IllegalDataException("Attempt to add a different aggrregate cell ="
            + column + ", expected aggregator either SUM or COUNT");
      }
    } else {
      if ((column.qualifier()[0] & RollupUtils.AGGREGATOR_MASK) == agg_id) {
        append(column, false, false);
      } else if (Bytes.memcmp(column.qualifier(), rollup_query.getRollupAggPrefix(), 0,
            rollup_query.getRollupAggPrefix().length) == 0) {
        append(column, false, true);
      } else {
        throw new IllegalDataException("Attempt to add a different aggrregate cell ="
            + column + ", expected aggregator " + Bytes.pretty(
            rollup_query.getRollupAggPrefix()));
      }
    }
  }

  /**
   * Adds the column to the byte arrays.
   * @param column The non-null key value to add.
   * @param is_count Whether or not the column is for counts.
   * @param strip_string Whether or not the column has the old style string 
   * header and needs cleaning.
   */
  private void append(final KeyValue column, 
                      final boolean is_count, 
                      final boolean strip_string) {
    // for now assume we properly allocated our qualifiers
    if (is_count) {
      int offset = strip_string ? 
          Internal.getOffsetFromQualifier(column.qualifier(), 
          RollupQuery.COUNT.length + 1) :
            Internal.getOffsetFromQualifier(column.qualifier(), 
                1);
      if (last_count_offset > -1 && offset <= last_count_offset) {
        // only accept equivalent offsets. If somehow we get an earlier one, HBase is broke 
        if (offset == last_count_offset && tsdb.getConfig().fix_duplicates()) {
          if (column.timestamp() < last_count_ts) {
            if (LOG.isDebugEnabled()) {
              LOG.debug("Skipping older duplicate count value for " + column 
              + " of " + offset + " which is = the last offset " + last_count_offset
              + " for " + this);
            }
            return;
          } else { // if it's equal, just use the one we got first
            // roll back the indices
            indices[2] -= 2;
            indices[3] -= Internal.getValueLengthFromQualifier(
                count_qualifiers, indices[2]);
            if (LOG.isDebugEnabled()) {
              LOG.debug("Replacing older duplicate count with " + column 
              + " of " + offset + " which is = the last offset " + last_count_offset
              + " for " + this);
            }
          }
        } else {
          throw new IllegalArgumentException("The count offset for " + column 
              + " of " + offset + " is <= the last offset " + last_count_offset
              + " for " + this);
        }
      }
      last_count_offset = offset;
      last_count_ts = column.timestamp();
      if (strip_string) {
        System.arraycopy(column.qualifier(), RollupQuery.COUNT.length + 1, 
            count_qualifiers, indices[2], 2);
      } else {
        System.arraycopy(column.qualifier(), 1, count_qualifiers, indices[2], 2);
      }
      indices[2] += 2;
      
      if (indices[3] + column.value().length > count_values.length) {
        byte[] buf = new byte[count_values.length * 2];
        System.arraycopy(count_values, 0, buf, 0, count_values.length);
        count_values = buf;
      }
      System.arraycopy(column.value(), 0, count_values, indices[3], 
          column.value().length);
      indices[3] += column.value().length;
    } else {
      int offset = strip_string ? 
          Internal.getOffsetFromQualifier(column.qualifier(), 
              rollup_query.getRollupAggPrefix().length)
          : Internal.getOffsetFromQualifier(column.qualifier(), 1);
      if (last_offset > -1 && offset <= last_offset) {
        // only accept equivalent offsets. If somehow we get an earlier one, HBase is broke
        if (offset == last_offset && tsdb.getConfig().fix_duplicates()) {
          if (column.timestamp() < last_value_ts) {
            if (LOG.isDebugEnabled()) {
              LOG.debug("Skipping older duplicate value for " + column 
              + " of " + offset + " which is = the last offset " + last_count_offset
              + " for " + this);
            }
            return;
          } else { // if it's equal, just use the one we got first
            // roll back the indices
            indices[0] -= 2;
            indices[1] -= Internal.getValueLengthFromQualifier(qualifiers, indices[0]);
            if (LOG.isDebugEnabled()) {
              LOG.debug("Replacing older duplicate value with " + column 
              + " of " + offset + " is = the last offset " + last_count_offset
              + " for " + this);
            }
          }
        } else {
          throw new IllegalDataException("The offset for " + column 
              + " of " + offset + " is <= the last offset " + last_offset
              + " for " + this);
        }
      }
      last_offset = offset;
      last_value_ts = column.timestamp();
      if (strip_string) {
        System.arraycopy(column.qualifier(), 
            rollup_query.getRollupAggPrefix().length, qualifiers, indices[0], 2);
      } else {
        System.arraycopy(column.qualifier(), 1, qualifiers, indices[0], 2);
      }
      indices[0] += 2;
      
      if (indices[1] + column.value().length > values.length) {
        byte[] buf = new byte[values.length * 2];
        System.arraycopy(values, 0, buf, 0, values.length);
        values = buf;
      }
      System.arraycopy(column.value(), 0, values, indices[1], 
          column.value().length);
      indices[1] += column.value().length;
    }
  }
  
  @Override
  public String toString() {
    final StringBuilder buf = new StringBuilder(80 + 
        + (key == null ? 0 : key.length * 4)
        + indices[0] * 8);
    buf.append("RollupSeq(key=")
       .append(key == null ? "<null>" : Arrays.toString(key))
       .append(" base_time=")
       .append(key == null ? "<null>" : baseTime())
       .append(", basetime=")
       .append(key == null ? "no data" : new Date(baseTime() * 1000))
       .append(", ");
    buf.append("datapoints=").append(indices[0] / 2)
       .append(", counts=").append(indices.length > 2 ? indices[2] / 2 : "0");
    buf.append(",\n (qualifier=[").append(Arrays.toString(qualifiers));
    buf.append("]),\n (values=[").append(Arrays.toString(values));
    buf.append("],\n (count_qualifier=[").append(Arrays.toString(count_qualifiers));
    buf.append("],\n (count_values=[").append(Arrays.toString(count_values));
    buf.append("])");
    return buf.toString();
  }
  
  public String metricName() {
    try {
      return metricNameAsync().join();
    } catch (InterruptedException iex) {
      throw new RuntimeException("Interrupted the metric name call", iex);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Should never be here", e);
    }
  }

  public Deferred<String> metricNameAsync() {
    if (key == null) {
      throw new IllegalStateException("the row key is null!");
    }
    return RowKey.metricNameAsync(tsdb, key);
  }
  
  public byte[] metricUID() {
    return Arrays.copyOfRange(key, Const.SALT_WIDTH(), 
        Const.SALT_WIDTH() + TSDB.metrics_width());
  }
  
  public Map<String, String> getTags() {
    try {
      return getTagsAsync().join();
    } catch (InterruptedException iex) {
      throw new RuntimeException("Interrupted the tags call", iex);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Should never be here", e);
    }
  }
  
  public Deferred<Map<String, String>> getTagsAsync() {
    return Tags.getTagsAsync(tsdb, key);
  }

  @Override
  public ByteMap<byte[]> getTagUids() {
    return Tags.getTagUids(key);
  }
  
  /** @return an empty list since aggregated tags cannot exist on a single row */
  public List<String> getAggregatedTags() {
    return Collections.emptyList();
  }
  
  public Deferred<List<String>> getAggregatedTagsAsync() {
    final List<String> empty = Collections.emptyList();
    return Deferred.fromResult(empty);
  }
  
  @Override
  public List<byte[]> getAggregatedTagUids() {
    return Collections.emptyList();
  }
  
  public List<String> getTSUIDs() {
    return Collections.emptyList();
  }
  
  /** @return null since annotations are stored at the SpanGroup level. They
   * are filtered when a row is compacted */ 
  public List<Annotation> getAnnotations() {
    return Collections.emptyList();
  }
  
  @Override
  public int size() {
    if (need_count) {
      int count = 0;
      final Iterator it = internalIterator();
      while (it.hasNext()) {
        it.next();
        ++count;
      }
      return count;
    } else {
      return indices[0] / 2;
    }
  }

  @Override
  public int aggregatedSize() {
    return 0;
  }

  @Override
  public long timestamp(int i) {
    if (i < 0) {
      throw new IndexOutOfBoundsException("index " + i + 
          " must be positive for this=" + this);
    }
    if (need_count) {
      final Iterator it = internalIterator();
      int count = 0;
      while (it.hasNext()) {
        final DataPoint dp = it.next();
        if (count == i) {
          return dp.timestamp();
        }
        ++count;
      }
      throw new IndexOutOfBoundsException("index " + i + " >= " + size()
            + " for this=" + this);
    } else {
      if (i * 2 >= indices[0]) {
        throw new IndexOutOfBoundsException("index " + i + " >= " + size()
            + " for this=" + this);
      }
      return RollupUtils.getTimestampFromRollupQualifier(qualifiers, baseTime(),
          rollup_query.getRollupInterval(), i * 2);
    }
  }

  @Override
  public boolean isInteger(int i) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long longValue(int i) {
    throw new UnsupportedOperationException();
  }

  @Override
  public double doubleValue(int i) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getQueryIndex() {
    return 0;
  }

  @Override
  public byte[] key() {
    return key;
  }

  @Override
  public long baseTime() {
    return Internal.baseTime(key);
  }

  @Override
  public SeekableView iterator() {
    return internalIterator();
  }
  
  @Override
  public Iterator internalIterator() {
    return new RollupIterator();
  }
  
  @Override
  public boolean isPercentile() {
    return false;
  }

  @Override
  public float getPercentile() {
    throw new UnsupportedOperationException("getPercentile not supported");
  }
  
  /** Iterator for {@link RowSeq}s.  */
  public final class RollupIterator implements iRowSeq.Iterator {

    /** Current qualifier.  */
    private int qualifier;

    /** Next index in {@link #qualifiers}.  */
    private int qual_index;

    /** Next index in {@link #values}.  */
    private int value_index;
    
    /** Current qualifier for the counts */
    private int count_qualifier;
    
    /** Next index in {@link #count_qualifier}. */
    private int count_qual_index;
    
    /** Next index in {@link #count_values}. */
    private int count_value_index;

    /** Pre-extracted base time of this row sequence.  */
    private final long base_time = baseTime();

    RollupIterator() {
      if (need_count) {
        sync();
      }
    }

    // ------------------ //
    // Iterator interface //
    // ------------------ //

    public boolean hasNext() {
      if (need_count) {
        sync();
        return qual_index < indices[0] && 
            count_qual_index < indices[2];
      }
      return qual_index < indices[0];
    }
    
    void sync() {
      if (qual_index >= indices[0] || count_qual_index >= indices[2]) {
        return;
      }
      long q_ts = Internal.getOffsetFromQualifier(qualifiers, qual_index);
      long c_ts = Internal.getOffsetFromQualifier(count_qualifiers, count_qual_index);
      if (q_ts == c_ts) {
        return;
      }
      LOG.warn("Different agg [" + q_ts + "] and count [" + c_ts + 
          "] offsets for " + this);
      if (q_ts > c_ts) {
        count_value_index += Internal.getValueLengthFromQualifier(
            count_qualifiers, count_qual_index);
        count_qual_index += 2;
        if (count_qual_index >= count_qualifiers.length) {
          LOG.warn("Ran out of counts: " + this);
          return;
        }
        sync();
      } else {
        value_index += Internal.getValueLengthFromQualifier(qualifiers, qual_index);
        qual_index += 2;
        if (qual_index >= qualifiers.length) {
          LOG.warn("Ran out of qualifiers: " + this);
          return;
        }
        sync();
      }
    }

    public DataPoint next() {
      if (!hasNext()) {
        throw new NoSuchElementException("no more elements");
      }
      value_index += Internal.getValueLengthFromQualifier(qualifiers, qual_index);
      qualifier = Bytes.getUnsignedShort(qualifiers, qual_index);
      qual_index += 2;
      if (need_count) {
        count_value_index += Internal.getValueLengthFromQualifier(
            count_qualifiers, count_qual_index);
        count_qualifier = Bytes.getUnsignedShort(count_qualifiers, count_qual_index);
        count_qual_index += 2;
      }
      return this;
    }

    public void remove() {
      throw new UnsupportedOperationException();
    }

    // ---------------------- //
    // SeekableView interface //
    // ---------------------- //

    @Override
    public void seek(final long timestamp) {
      final long ts;
      if ((timestamp & Const.SECOND_MASK) == 0) {
        ts = timestamp * 1000;
      } else {
        ts = timestamp;
      }
      // reset all
      qual_index = value_index = count_qual_index = count_value_index = 0;
      if (!hasNext()) {
        return;
      }
      
      qualifier = Bytes.getUnsignedShort(qualifiers, qual_index);
      if (need_count) {
        count_qualifier = Bytes.getUnsignedShort(count_qualifiers, count_qual_index);
      }
      while (qual_index < indices[0] && 
          RollupUtils.getTimestampFromRollupQualifier(qualifiers, base_time,
          rollup_query.getRollupInterval(), qual_index) < ts) {
        value_index += Internal.getValueLengthFromQualifier(qualifiers, qual_index);
        qualifier = Bytes.getUnsignedShort(qualifiers, qual_index);
        qual_index += 2;
        if (need_count) {
          count_value_index += Internal.getValueLengthFromQualifier(
              count_qualifiers, count_qual_index);
          count_qualifier = Bytes.getUnsignedShort(count_qualifiers, count_qual_index);
          count_qual_index += 2;
        }
      }
    }

    // ------------------- //
    // DataPoint interface //
    // ------------------- //

    public long timestamp() {
      return RollupUtils.getTimestampFromRollupQualifier(qualifier, base_time,
          rollup_query.getRollupInterval());
    }

    public boolean isInteger() {
      assert qual_index > 0: "not initialized: " + this;
      return (qualifier & Const.FLAG_FLOAT) == 0x0;
    }

    @Override
    public long valueCount() {
      if (count_values == null) {
        // real values (sum, max, min) so just return 1.
        return 1;
      }
      final byte flags = (byte) count_qualifier;
      final byte vlen = (byte) ((flags & Const.LENGTH_MASK) + 1);
      if ((count_qualifier & Const.FLAG_FLOAT) == 0x0) {
        return Internal.extractIntegerValue(count_values, count_value_index - vlen, flags);
      } else {
        return (long)Internal.extractFloatingPointValue(count_values, count_value_index - vlen, flags);
      }
    }
    
    public long longValue() {
      if (!isInteger()) {
        throw new ClassCastException("value @"
          + qual_index + " is not a long in " + this);
      }
      
      final byte flags = (byte) qualifier;
      final byte vlen = (byte) ((flags & Const.LENGTH_MASK) + 1);
      return Internal.extractIntegerValue(values, value_index - vlen, flags);
      //return extractIntegerValue(values, value_index - vlen, flags);
    }

    public double doubleValue() {
      if (isInteger()) {
        throw new ClassCastException("value @"
          + qual_index + " is not a float in " + this);
      }
      final byte flags = (byte) qualifier;
      final byte vlen = (byte) ((flags & Const.LENGTH_MASK) + 1);
      return Internal.extractFloatingPointValue(values, value_index - vlen, flags);
      //return extractFloatingPointValue(values, value_index - vlen, flags);
    }

    public double toDouble() {
      return isInteger() ? longValue() : doubleValue();
    }

    // ---------------- //
    // Helpers for Span //
    // ---------------- //

    /** Helper to take a snapshot of the state of this iterator.  */
    long saveState() {
      return ((long)qual_index << 32) | ((long)value_index & 0xFFFFFFFF);
    }

    /** Helper to restore a snapshot of the state of this iterator.  */
    void restoreState(long state) {
      value_index = (int) state & 0xFFFFFFFF;
      state >>>= 32;
      qual_index = (int) state;
      qualifier = 0;
    }

    /**
     * Look a head to see the next timestamp.
     * @throws IndexOutOfBoundsException if we reached the end already.
     */
    long peekNextTimestamp() {
      return RollupUtils.getTimestampFromRollupQualifier(qualifiers, base_time,
          rollup_query.getRollupInterval(), qual_index);
    }

    /** Only returns internal state for the iterator itself.  */
    String toStringSummary() {
      return "RowSeq.Iterator(qual_index=" + qual_index
        + ", value_index=" + value_index + ", cq_idx=" + count_qual_index +
        ", cv_idx=" + count_value_index;
    }

    public String toString() {
      return toStringSummary() + ", seq=" + RollupSeq.this + ')';
    }

  }
}
