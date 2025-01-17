/*
 *
 *  The contents of this file are subject to the Terracotta Public License Version
 *  2.0 (the "License"); You may not use this file except in compliance with the
 *  License. You may obtain a copy of the License at
 *
 *  http://terracotta.org/legal/terracotta-public-license.
 *
 *  Software distributed under the License is distributed on an "AS IS" basis,
 *  WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 *  the specific language governing rights and limitations under the License.
 *
 *  The Covered Software is Terracotta Core.
 *
 *  The Initial Developer of the Covered Software is
 *  Terracotta, Inc., a Software AG company
 *
 */
package com.tc.io;

import com.tc.bytes.TCByteBuffer;
import com.tc.bytes.TCByteBufferFactory;
import com.tc.util.Assert;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UTFDataFormatException;
import java.util.ArrayList;
import java.util.List;

/**
 * Use me to write data to a set of TCByteBuffer instances. <br>
 * <br>
 * NOTE: This class never throws java.io.IOException (unlike the generic OutputStream) class
 */
public class TCByteBufferOutputStream extends OutputStream implements TCByteBufferOutput {

  private static final int       DEFAULT_MAX_BLOCK_SIZE     = 512 * 1024;
  private static final int       DEFAULT_INITIAL_BLOCK_SIZE = 1024;

  private final int              initialBlockSize;
  private final int              maxBlockSize;
  private final DataOutputStream dos;

  // The "buffers" list is accessed by index in the Mark class, thus it should not be a linked list
  private List<TCByteBuffer>     buffers                    = new ArrayList<TCByteBuffer>(16);

  private TCByteBuffer           current = TCByteBufferFactory.getInstance(0);
  private boolean                closed;
  private int                    written;
  private int                    blockSize;

  // TODO: Provide a method to write buffers to another output stream
  // TODO: Provide a method to turn the buffers into an input stream with minimal cost (ie. no consolidation, no
  // duplicate(), etc)

  public TCByteBufferOutputStream() {
    this(DEFAULT_INITIAL_BLOCK_SIZE, DEFAULT_MAX_BLOCK_SIZE);
  }

  public TCByteBufferOutputStream(int blockSize) {
    this(blockSize, blockSize);
  }

  public TCByteBufferOutputStream(int initialBlockSize, int maxBlockSize) {
    if (maxBlockSize < 1) { throw new IllegalArgumentException("Max block size must be greater than or equal to 1"); }
    if (initialBlockSize < 1) { throw new IllegalArgumentException(
                                                                   "Initial block size must be greater than or equal to 1"); }

    if (maxBlockSize < initialBlockSize) { throw new IllegalArgumentException(
                                                                              "Initial block size less than max block size"); }

    this.maxBlockSize = maxBlockSize;
    this.initialBlockSize = initialBlockSize;
    this.blockSize = initialBlockSize;
    this.closed = false;
    this.dos = new DataOutputStream(this);
  }

  /**
   * Create a "mark" in this stream. A mark can be used to fixup data in an earlier portion of the stream even after you
   * have written past it. One place this is useful is when you need to backtrack and fill in a length field after
   * writing some arbitrary data to the stream. A mark can also be used to read earlier portions of the stream
   */
  public Mark mark() {
    checkClosed();
    return new Mark(buffers.size(), current.position(), getBytesWritten());
  }

  @Override
  public void write(int b) {
    checkClosed();

    written++;

    checkBuffer();

    current.put((byte) b);
  }

  @Override
  public void write(byte b[]) {
    write(b, 0, b.length);
  }

  @Override
  public void write(TCByteBuffer data) {
    if (data == null) { throw new NullPointerException(); }
    write(new TCByteBuffer[] { data });
  }

  private void checkBuffer() {
    while (current == null || !current.hasRemaining()) {
      current = addBuffer();
    }
  }
  /**
   * Add arbitrary buffers into the stream. All of the data (from position 0 to limit()) in each buffer passed will be
   * used in the stream. If that is not what you want, setup your buffers differently before calling this write()
   */
  @Override
  public void write(TCByteBuffer[] data) {
    checkClosed();
    if (data == null) { throw new NullPointerException(); }
    if (data.length == 0) { return; }
    
    for (TCByteBuffer element : data) {
      int len = element.remaining();
      while (element.hasRemaining()) {
        checkBuffer();
        int saveLimit = element.limit();
        if (element.remaining() > current.remaining()) {
          element.limit(element.position() + current.remaining());
        }
        current.put(element);
        Assert.assertFalse(element.hasRemaining());
        element.limit(saveLimit);
      }
      written += len;
    }
  }

  public int getBytesWritten() {
    return written;
  }

  @Override
  public void write(byte b[], int offset, int length) {
    checkClosed();

    if (b == null) { throw new NullPointerException(); }

    if ((offset < 0) || (offset > b.length) || (length < 0) || ((offset + length) > b.length)) { throw new IndexOutOfBoundsException(); }

    if (length == 0) { return; }

    // do this after the checks (ie. don't corrupt the counter if bogus args passed)
    written += length;

    int index = offset;
    int numToWrite = length;
    while (numToWrite > 0) {
      checkBuffer();
      final int numToPut = Math.min(current.remaining(), numToWrite);
      current.put(b, index, numToPut);
      numToWrite -= numToPut;
      index += numToPut;
    }
  }

  @Override
  public void close() {
    if (!closed) {
      finalizeBuffer();
      closed = true;
    }
  }

  public void reset() {
    current = null;
    closed = false;
    buffers.clear();
    written = 0;
    this.blockSize = this.initialBlockSize;
  }

  /**
   * Obtain the contents of this stream as an array of TCByteBuffer
   */
  @Override
  public TCByteBuffer[] toArray() {
    close();
    TCByteBuffer[] rv = new TCByteBuffer[buffers.size()];
    return buffers.toArray(rv);
  }

  @Override
  public String toString() {
    return (buffers == null) ? "null" : buffers.toString();
  }
  
  private TCByteBuffer addBuffer() {
    finalizeBuffer();
    TCByteBuffer nb = newBuffer();
    blockSize = nb.capacity();
    return nb;
  }

  protected TCByteBuffer newBuffer() {
    TCByteBuffer rv = TCByteBufferFactory.getInstance(blockSize);
    blockSize <<= 1;
    if (blockSize > maxBlockSize) {
      blockSize = maxBlockSize;
    }
    return rv;
  }

  private void finalizeBuffer() {
    if (current != null) {
      current.flip();
      if (current.hasRemaining()) {
        buffers.add(current);
      }
    }
  }

  @Override
  public void writeBoolean(boolean value) {
    try {
      dos.writeBoolean(value);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public void writeByte(int value) {
    try {
      dos.writeByte(value);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public void writeChar(int value) {
    try {
      dos.writeChar(value);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public void writeDouble(double value) {
    try {
      dos.writeDouble(value);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public void writeFloat(float value) {
    try {
      dos.writeFloat(value);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public void writeInt(int value) {
    try {
      dos.writeInt(value);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public void writeLong(long value) {
    try {
      dos.writeLong(value);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public void writeShort(int value) {
    try {
      dos.writeShort(value);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public void writeString(String string) {
    writeString(string, false);
  }

  private void writeString(String string, boolean forceRaw) {
    // Is null? (true/false)
    if (string == null) {
      writeBoolean(true);
      return;
    } else {
      writeBoolean(false);
    }

    if (!forceRaw) {
      Mark mark = mark();
      // is UTF encoded? 1(true) or 0(false)
      write(1);

      try {
        dos.writeUTF(string);
        // No exception, just return
        return;
      } catch (IOException e) {
        if (!(e instanceof UTFDataFormatException)) { throw new AssertionError(e); }
        // String too long, encode as raw chars
        mark.write(0);
      }
    } else {
      write(0);
    }

    writeStringAsRawChars(string);
  }

  private void writeStringAsRawChars(String string) {
    if (string == null) { throw new AssertionError(); }
    writeInt(string.length());
    try {
      dos.writeChars(string);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private void checkClosed() {
    if (closed) { throw new IllegalStateException("stream is closed"); }
  }

  // This class could be fancier:
  // - Support the TCDataOutput interface
  // - Allow writing through the mark to grow the buffer list
  // - etc, etc, etc
  public class Mark {
    private final int bufferIndex;
    private final int bufferPosition;
    private final int absolutePosition;

    private Mark(int bufferIndex, int bufferPosition, int absolutePosition) {
      this.bufferIndex = bufferIndex;
      this.bufferPosition = bufferPosition;
      this.absolutePosition = absolutePosition;
    }

    public int getPosition() {
      return this.absolutePosition;
    }

    /**
     * Write the given byte array at the position designated by this mark
     */
    public void write(byte[] data) {
      checkClosed();

      if (data == null) { throw new NullPointerException(); }

      if (data.length == 0) { return; }

      if (getBytesWritten() - absolutePosition < data.length) { throw new IllegalArgumentException(
                                                                                                   "Cannot write past the existing tail of stream via the mark"); }

      TCByteBuffer buf = getBuffer(bufferIndex);

      int bufIndex = bufferIndex;
      int bufPos = bufferPosition;
      int dataIndex = 0;
      int numToWrite = data.length;

      while (numToWrite > 0) {
        int howMany = Math.min(numToWrite, buf.limit() - bufPos);

        if (howMany > 0) {
          buf.put(bufPos, data, dataIndex, howMany);
          dataIndex += howMany;
          numToWrite -= howMany;
          if (numToWrite == 0) { return; }
        }

        buf = getBuffer(++bufIndex);
        bufPos = 0;
      }
    }

    private TCByteBuffer getBuffer(int index) {
      int buffersSize = buffers.size();
      if (index < buffersSize) {
        return buffers.get(index);
      } else if (index == buffersSize) {
        return current;
      } else {
        throw Assert.failure("index=" + index + ", buffers.size()=" + buffers.size());
      }
    }

    /**
     * Write a single byte at the given mark. Calling write(int) multiple times will simply overwrite the same byte over
     * and over
     */
    public void write(int b) {
      write(new byte[] { (byte) b });
    }

    /**
     * Copy (by invoking write() on the destination stream) the given length of bytes starting at this mark
     * 
     * @throws IOException
     */
    public void copyTo(TCByteBufferOutput dest, int length) {
      copyTo(dest, 0, length);
    }

    /**
     * Copy (by invoking write() on the destination stream) the given length of bytes starting from an offset to this
     * mark
     * 
     * @throws IOException
     */
    public void copyTo(TCByteBufferOutput dest, int offset, int length) {
      if (length < 0) { throw new IllegalArgumentException("length: " + length); }

      if (this.absolutePosition + offset + length > getBytesWritten()) {
        //
        throw new IllegalArgumentException("not enough data for copy of " + length + " bytes starting at position "
                                           + (this.absolutePosition + offset) + " of stream of size "
                                           + getBytesWritten());
      }

      int index = this.bufferIndex;
      int pos = this.bufferPosition;

      while (offset > 0) {
        byte[] array = getBuffer(index).array();
        int num = Math.min(array.length - pos, offset);
        offset -= num;
        if (offset == 0) {
          if (index > this.bufferIndex) {
            pos = num;
          } else {
            pos += num;
          }
          break;
        }

        pos = 0;
        index++;
      }

      while (length > 0) {
        byte[] array = getBuffer(index++).array();
        int num = Math.min(array.length - pos, length);
        dest.write(array, pos, num);
        length -= num;
        pos = 0;
      }
    }
  }

  @Override
  public void writeBytes(String s) {
    throw new UnsupportedOperationException("use writeString() instead");
  }

  @Override
  public void writeChars(String s) {
    writeString(s, true);
  }

  @Override
  public void writeUTF(String str) {
    writeString(str);
  }

}
