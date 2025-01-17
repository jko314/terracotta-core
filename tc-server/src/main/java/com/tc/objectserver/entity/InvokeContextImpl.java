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
package com.tc.objectserver.entity;

import com.tc.object.tx.TransactionID;
import org.terracotta.entity.ClientSourceId;
import org.terracotta.entity.InvokeContext;

public class InvokeContextImpl implements InvokeContext {

  private static ThreadLocal<InvokeContext> INHERITED = new InheritableThreadLocal<>();

  public static InvokeContext NULL_CONTEXT=new InvokeContextImpl();

  private final long oldestid;
  private final long currentId;
  private final ClientSourceId sourceId;
  private final int concurrencyKey;

  private InvokeContextImpl() {
    this(ClientSourceIdImpl.NULL_ID, -1, TransactionID.NULL_ID.toLong(), TransactionID.NULL_ID.toLong());
  }

  public InvokeContextImpl(int concurrencyKey) {
    this(ClientSourceIdImpl.NULL_ID, concurrencyKey, TransactionID.NULL_ID.toLong(), TransactionID.NULL_ID.toLong());
  }

  public InvokeContextImpl(ClientSourceId sourceId, int concurrencyKey, long oldestid, long currentId) {
    this.sourceId = sourceId;
    this.concurrencyKey = concurrencyKey;
    this.oldestid = oldestid;
    this.currentId = currentId;
    setThreadLocal();
  }

  private void setThreadLocal() {
    if (this.sourceId.isValidClient()) {
      INHERITED.set(this);
    }
  }

  public static InvokeContext getCurrentContext() {
    return INHERITED.get();
  }

  @Override
  public ClientSourceId getClientSource() {
    return sourceId;
  }

  @Override
  public long getCurrentTransactionId() {
    return currentId;
  }

  @Override
  public long getOldestTransactionId() {
    return oldestid;
  }

  @Override
  public boolean isValidClientInformation() {
    return currentId >= 0 && sourceId.toLong() >= 0;
  }

  @Override
  public ClientSourceId makeClientSourceId(long l) {
    return new ClientSourceIdImpl(l);
  }

  @Override
  public int getConcurrencyKey() {
    return concurrencyKey;
  }

  @Override
  public String toString() {
    return "InvokeContextImpl{" + "oldestid=" + oldestid + ", currentId=" + currentId + ", sourceId=" + sourceId + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    InvokeContextImpl context = (InvokeContextImpl) o;

    if (oldestid != context.oldestid) {
      return false;
    }
    if (currentId != context.currentId) {
      return false;
    }
    return sourceId != null ? sourceId.equals(context.sourceId) : context.sourceId == null;
  }

  @Override
  public int hashCode() {
    int result = (int) (oldestid ^ (oldestid >>> 32));
    result = 31 * result + (int) (currentId ^ (currentId >>> 32));
    result = 31 * result + (sourceId != null ? sourceId.hashCode() : 0);
    return result;
  }
}
