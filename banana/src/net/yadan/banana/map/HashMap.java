/*
 * Copyright (C) 2013 Omry Yadan <omry@yadan.net>
 * All rights reserved.
 *
 * See https://github.com/omry/banana/blob/master/BSD-LICENSE for licensing information
 */
package net.yadan.banana.map;

import net.yadan.banana.memory.IBlockAllocator;
import net.yadan.banana.memory.IBuffer;
import net.yadan.banana.memory.IMemAllocator;
import net.yadan.banana.memory.block.BigBlockAllocator;
import net.yadan.banana.memory.block.BlockAllocator;
import net.yadan.banana.memory.malloc.ChainedAllocator;


/**
 * @author omry
 * created May 7, 2013
 */
public class HashMap implements IHashMap {

  private static final double DEFAULT_GROWTH_FACTOR = 2.0;

  private static final int NEXT_OFFSET = 0;
  private static final int KEY_OFFSET = 1;
  private static final int USER_DATA_OFFSET = 3;
  public static final int RESERVED_SIZE = USER_DATA_OFFSET;

  private double m_loadFactor;
  private double m_growthFactor;

  /**
   * Holds an array of pointers into m_memory
   */
  private int m_table[];

  private int m_size;

  /**
   * The table is rehashed when its size exceeds this threshold. (The value of
   * this field is (int)(capacity * loadFactor).)
   */
  private int m_threshold;

  private IMemAllocator m_memory;

  private DebugLevel m_debugLevel = DebugLevel.NONE;

  public HashMap(int maxBlocks, int blockSize, double growthFactor, double loadFactor) {
    IBlockAllocator blocks;
    if ((long)maxBlocks * (HashMap.RESERVED_SIZE + blockSize) > Integer.MAX_VALUE) {
      blocks = new BigBlockAllocator(maxBlocks, HashMap.RESERVED_SIZE + blockSize, growthFactor);
    } else {
      blocks = new BlockAllocator(maxBlocks, HashMap.RESERVED_SIZE + blockSize, growthFactor);
    }
    init(new ChainedAllocator(blocks), maxBlocks, loadFactor);
  }

  public HashMap(IMemAllocator memory, int initialCapacity, double loadFactor) {
    init(memory, initialCapacity, loadFactor);
  }

  protected void init(IMemAllocator memory, int initialCapacity, double loadFactor) {
    m_size = 0;
    m_loadFactor = loadFactor;
    m_growthFactor = DEFAULT_GROWTH_FACTOR;
    m_memory = memory;
    m_table = new int[initialCapacity];
    m_threshold = (int) Math.min(getCapacity() * getLoadFactor(), Integer.MAX_VALUE);
    m_size = 0;
    for (int i = 0; i < m_table.length; i++) {
      m_table[i] = -1;
    }
  }

  @Override
  public int size() {
    return m_size;
  }

  @Override
  public int getCapacity() {
    return m_table.length;
  }

  @Override
  public boolean isEmpty() {
    return size() == 0;
  }

  @Override
  public boolean containsKey(long key) {
    return findRecord(key) != -1;
  }

  @Override
  public int createRecord(long key, int size) {
    if (size() >= m_threshold && m_growthFactor > 0) {
      increaseCapacity();
    }

    int listNum = hashCode(key, m_table.length);
    int pointer = m_table[listNum];

    // find if this key is already in the chain
    int prev = -1;
    while (pointer != -1) {
      // list already contain this key, reuse the space - resizing as needed
      if (m_memory.getLong(pointer, KEY_OFFSET) == key) {
        int pNext = m_memory.getInt(pointer, NEXT_OFFSET);
        pointer = m_memory.realloc(pointer, size + RESERVED_SIZE);
        if (prev == -1) {
          m_table[listNum] = pointer;
        } else {
          m_memory.setInt(prev, NEXT_OFFSET, pointer);
        }
        m_memory.initialize(pointer);
        m_memory.setLong(pointer, KEY_OFFSET, key);
        m_memory.setInt(pointer, NEXT_OFFSET, pNext);
        break;
      }
      prev = pointer;
      pointer = m_memory.getInt(pointer, NEXT_OFFSET);
    }

    if (pointer == -1) {
      pointer = m_memory.malloc(size + RESERVED_SIZE);
      m_memory.setLong(pointer, KEY_OFFSET, key);
      m_memory.setInt(pointer, NEXT_OFFSET, m_table[listNum]);
      m_table[listNum] = pointer;
      m_size++;
    }

    return pointer;
  }

  @Override
  public int createRecord(long key, IBuffer value) {
    int ret = createRecord(key, value.size());
    setInts(ret, 0, value.array(), 0, value.size());
    return ret;
  }

  @Override
  public int reallocRecord(long key, int newSize) {
    int listNum = hashCode(key, m_table.length);
    int n = m_table[listNum];
    int prev = -1;
    while (n != -1) {
      if (m_memory.getLong(n, KEY_OFFSET) == key) {
        int ptr = m_memory.realloc(n, newSize + RESERVED_SIZE);
        if (n == m_table[listNum]) {
          m_table[listNum] = ptr;
        } else {
          m_memory.setInt(prev, NEXT_OFFSET, ptr);
        }
        return ptr;
      }
      prev = n;
      n = m_memory.getInt(n, NEXT_OFFSET);
    }

    return -1;
  }

  @Override
  public int findRecord(long key) {
    int listNum = hashCode(key, m_table.length);

    int n = m_table[listNum];
    while (n != -1) {
      if (m_memory.getLong(n, KEY_OFFSET) == key) {
        break;
      }
      n = m_memory.getInt(n, NEXT_OFFSET);
    }

    return n;
  }

  @Override
  public void setLong(int record_id, int offset_in_data, long data) {
    m_memory.setLong(record_id, offset_in_data + USER_DATA_OFFSET, data);
  }

  @Override
  public void setInt(int record_id, int offset_in_data, int data) {
    m_memory.setInt(record_id, offset_in_data + USER_DATA_OFFSET, data);
  }

  @Override
  public long getLong(int record_id, int offset_in_data) {
    return m_memory.getLong(record_id, offset_in_data + USER_DATA_OFFSET);
  }

  @Override
  public int getInt(int record_id, int offset_in_data) {
    return m_memory.getInt(record_id, offset_in_data + USER_DATA_OFFSET);
  }

  @Override
  public void setInts(int pointer, int dst_offset_in_record,
      int[] src_data, int src_pos, int length) {
    m_memory.setInts(pointer, dst_offset_in_record + USER_DATA_OFFSET, src_data, src_pos, length);
  }

  @Override
  public void getInts(int pointer, int src_offset_in_record,
      int[] dst_data, int dst_pos, int length) {
    m_memory.getInts(pointer, src_offset_in_record + USER_DATA_OFFSET, dst_data, dst_pos, length);
  }

  @Override
  public void getBuffer(int pointer, int src_offset_in_record, IBuffer dst, int length) {
    m_memory.getBuffer(pointer, USER_DATA_OFFSET + src_offset_in_record, dst, length);
  }


  @Override
  public boolean remove(long key) {

    int listNum = hashCode(key, m_table.length);

    int n = m_table[listNum];
    int prev = -1;
    boolean first = true;
    while (n != -1) {
      if (m_memory.getLong(n, KEY_OFFSET) == key) {
        int next = m_memory.getInt(n, NEXT_OFFSET);
        if (first) {
          m_table[listNum] = next;
        } else {
          m_memory.setInt(prev, NEXT_OFFSET, next);
        }
        m_size--;
        m_memory.free(n);
        return true;
      }
      prev = n;
      first = false;
      n = m_memory.getInt(n, NEXT_OFFSET);
    }

    return false;
  }

  @Override
  public void clear() {
    m_size = 0;
    visitRecords(new HashMapVisitorAdapter() {
      @Override
      public void visit(IHashMap map, long key, int record_id, long num, long total) {
        m_memory.free(record_id);
      }
    });
    for (int i = 0; i < m_table.length; i++) {
      m_table[i] = -1;
    }
  }

  @Override
  public double getLoadFactor() {
    return m_loadFactor;
  }

  @Override
  public void setGrowthFactor(double d) {
    if (!(d == 0 || d > 1))
      throw new IllegalArgumentException("Growth factor " + d + " should be > 1 or 0 to disable");
    m_growthFactor = d;
  }

  @Override
  public void visitRecords(HashMapVisitor visitor) {
    visitor.begin(this);

    int num = 0;
    long total = size();
    for (int i = 0; i < m_table.length; i++) {
      int n = m_table[i];
      while (n != -1) {
        long key = m_memory.getLong(n, KEY_OFFSET);
        int next = m_memory.getInt(n, NEXT_OFFSET);
        visitor.visit(this, key, n, num, total);
        n = next;
      }
    }
    visitor.end(this);
  }

  private void increaseCapacity() {
    // long t = Sysftem.currentTimeMillis();
    int capacity = getCapacity();
    long newCapacity = Math.max(capacity + 1, (long) (capacity * m_growthFactor));
    if (newCapacity > Integer.MAX_VALUE) {
      throw new IllegalStateException("Attempted to resize map to " + newCapacity
          + " which is greated than Integer.MAX_VALUE");
    }
    int intCap = (int) newCapacity;
    int newTable[] = new int[intCap];
    for (int i = 0; i < newTable.length; i++) {
      newTable[i] = -1;
    }

    for (int tableNum = 0; tableNum < m_table.length; tableNum++) {
      int n = m_table[tableNum];
      while (n != -1) {
        long key = m_memory.getLong(n, KEY_OFFSET);
        int newTableNum = hashCode(key, intCap);

        int next = m_memory.getInt(n, NEXT_OFFSET);
        m_memory.setInt(n, NEXT_OFFSET, newTable[newTableNum]);
        newTable[newTableNum] = n;
        n = next;
      }
    }

    m_table = newTable;
    m_threshold = (int) Math.min(getCapacity() * getLoadFactor(), Integer.MAX_VALUE);
//    System.out.println(String.format("Increased map capacity from %d to %d took %d ms", capacity,
//        getCapacity(), (System.currentTimeMillis() - t)));
  }

  private int hashCode(long value, int listSize) {
    value = Math.abs(value); // negative values here messes us up.
    return (int) ((value ^ (value >>> 32)) % listSize);
  }

  public static int getIntArraySize(int maxCapacity, int recordSize) {
    return BlockAllocator.getIntArraySize(maxCapacity, recordSize + USER_DATA_OFFSET);
  }

  @Override
  public long computeMemoryUsage() {
    return 4 * m_table.length + m_memory.computeMemoryUsage();
  }

  @Override
  public String toString() {
    final StringBuilder s = new StringBuilder();
    s.append(HashMap.class.getName()).append(" ").append(size()).append(" / ")
        .append(getCapacity());

    if (m_debugLevel == DebugLevel.DEBUG_CONTENT) {
      s.append("\n");
      visitRecords(new HashMapVisitor() {

        @Override
        public void visit(IHashMap map, long key, int valuePtr, long num, long total) {
          s.append("(");
          s.append(key);
          s.append(")=(");
          int maxValueSize = m_memory.maximumCapacityFor(valuePtr);
          for (int i = 0; i < maxValueSize; i++) {
            s.append(m_memory.getInt(valuePtr, i));
            if (i + 1 < maxValueSize) {
              s.append(",");
            }
          }
          s.append(")");
          if (num + 1 < total) {
            s.append(",\n");
          }
        }

        @Override
        public void end(IHashMap map) {
          s.append("]");
        }

        @Override
        public void begin(IHashMap map) {
          s.append("[");
        }
      });
    } else if (m_debugLevel == DebugLevel.DEBUG_STRUCTURE) {
      s.append("\n");
      for (int tableNum = 0; tableNum < m_table.length; tableNum++) {
        int n = m_table[tableNum];
        while (n != -1) {
          long key = m_memory.getLong(n, KEY_OFFSET);
          int next = m_memory.getInt(n, NEXT_OFFSET);

          s.append("(#").append(n).append(",K=");
          s.append(key);
          s.append(",N=").append(m_memory.getInt(n, NEXT_OFFSET));
          s.append(")=(");
          int maxValueSize = m_memory.maximumCapacityFor(n);
          for (int i = USER_DATA_OFFSET; i < maxValueSize; i++) {
            s.append(m_memory.getInt(n, i));
            if (i + 1 < maxValueSize) {
              s.append(",");
            }
          }
          s.append(")");

          n = next;
          s.append(" -> ");
        }

        s.append("END\n");
      }
    }

    return s.toString();
  }

  @Override
  public void setDebug(DebugLevel level) {
    m_debugLevel = level;
  }

  @Override
  public IMemAllocator getAllocator() {
    return m_memory;
  }
}