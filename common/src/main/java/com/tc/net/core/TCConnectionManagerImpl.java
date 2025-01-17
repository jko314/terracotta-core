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
package com.tc.net.core;

import com.tc.bytes.TCByteBufferFactory;
import com.tc.bytes.TCDirectByteBufferCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tc.net.core.event.TCConnectionErrorEvent;
import com.tc.net.core.event.TCConnectionEvent;
import com.tc.net.core.event.TCConnectionEventListener;
import com.tc.net.core.event.TCListenerEvent;
import com.tc.net.core.event.TCListenerEventListener;
import com.tc.net.protocol.ProtocolAdaptorFactory;
import com.tc.util.concurrent.SetOnceFlag;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.ServerSocketChannel;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.tc.net.protocol.TCProtocolAdaptor;
import com.tc.text.PrettyPrintable;

/**
 * The {@link TCConnectionManager} implementation.
 * 
 * @author teck
 * @author mgovinda
 */
public class TCConnectionManagerImpl implements TCConnectionManager {
  protected static final TCConnection[] EMPTY_CONNECTION_ARRAY = new TCConnection[] {};
  protected static final TCListener[]   EMPTY_LISTENER_ARRAY   = new TCListener[] {};
  protected static final Logger logger                 = LoggerFactory.getLogger(TCConnectionManager.class);

  private final TCCommImpl              comm;
  private final Set<TCConnection>       connections            = new HashSet<>();
  private final Set<TCListener>         listeners              = new HashSet<>();
  private final SetOnceFlag             shutdown               = new SetOnceFlag();
  private final ConnectionEvents        connEvents;
  private final ListenerEvents          listenerEvents;
  private final SocketParams            socketParams;
  private final BufferManagerFactory    bufferManagerFactory;

  private final TCDirectByteBufferCache buffers = new TCDirectByteBufferCache(TCByteBufferFactory.getFixedBufferSize(), 16 * 1024);

  public TCConnectionManagerImpl() {
    this("ConnectionMgr", 0, new CachingClearTextBufferManagerFactory());
  }

  public TCConnectionManagerImpl(String name, int workerCommCount, BufferManagerFactory bufferManagerFactory) {
    this.connEvents = new ConnectionEvents();
    this.listenerEvents = new ListenerEvents();
    this.socketParams = new SocketParams();
    this.bufferManagerFactory = bufferManagerFactory;
    this.comm = new TCCommImpl(name, workerCommCount, socketParams);
    this.comm.start();
  }
  
  @Override
  public Map<String, ?> getStateMap() {
    Map<String, Object> state = new LinkedHashMap<>();
    synchronized(connections) {
      state.put("connections", connections.stream().map(connection->connection.getState()).collect(Collectors.toList()));
    }
    state.put("processors", comm.getState());
    state.put("buffers.cached", buffers.size());
    state.put("buffers.referenced", buffers.referenced());
    if (bufferManagerFactory instanceof PrettyPrintable) {
      state.put("bufferManager", ((PrettyPrintable)bufferManagerFactory).getStateMap());
    } else {
      state.put("bufferManager", bufferManagerFactory.toString());
    }
    return state;
  }

  protected TCConnection createConnectionImpl(TCProtocolAdaptor adaptor, TCConnectionEventListener listener) {
    return new TCConnectionImpl(listener, adaptor, this, comm.nioServiceThreadForNewConnection(), socketParams, bufferManagerFactory);
  }

  @SuppressWarnings("resource")
  protected TCListener createListenerImpl(InetSocketAddress addr, ProtocolAdaptorFactory factory, int backlog,
                                          boolean reuseAddr) throws IOException {
    ServerSocketChannel ssc = ServerSocketChannel.open();
    ssc.configureBlocking(false);
    ServerSocket serverSocket = ssc.socket();
    this.socketParams.applyServerSocketParams(serverSocket, reuseAddr);

    try {
      serverSocket.bind(addr, backlog);
    } catch (IOException ioe) {
      logger.warn("Unable to bind socket on address " + addr.getAddress() + ", port " + addr.getPort() + ", "
                  + ioe.getMessage());
      throw ioe;
    }

    if (logger.isDebugEnabled()) {
      logger.debug("Bind: " + serverSocket.getLocalSocketAddress());
    }

    CoreNIOServices commThread = comm.nioServiceThreadForNewListener();

    TCListenerImpl rv = new TCListenerImpl(ssc, factory, getConnectionListener(), this, commThread, bufferManagerFactory);

    commThread.registerListener(rv, ssc);

    return rv;
  }

  @Override
  public TCConnection[] getAllConnections() {
    synchronized (connections) {
      return connections.toArray(EMPTY_CONNECTION_ARRAY);
    }
  }

  @Override
  public TCListener[] getAllListeners() {
    synchronized (listeners) {
      return listeners.toArray(EMPTY_LISTENER_ARRAY);
    }
  }

  @Override
  public final synchronized TCListener createListener(InetSocketAddress addr, ProtocolAdaptorFactory factory)
      throws IOException {
    return createListener(addr, factory, Constants.DEFAULT_ACCEPT_QUEUE_DEPTH, true);
  }

  @Override
  public final synchronized TCListener createListener(InetSocketAddress addr, ProtocolAdaptorFactory factory,
                                                      int backlog, boolean reuseAddr) throws IOException {
    checkShutdown();

    TCListener rv = createListenerImpl(addr, factory, backlog, reuseAddr);
    rv.addEventListener(listenerEvents);

    synchronized (listeners) {
      listeners.add(rv);
    }

    return rv;
  }

  @Override
  public final synchronized TCConnection createConnection(TCProtocolAdaptor adaptor) throws IOException {
    checkShutdown();

    TCConnection rv = createConnectionImpl(adaptor, connEvents);
    newConnection(rv);

    return rv;
  }

  @Override
  public synchronized void closeAllConnections(long timeout) {
    closeAllConnections(false, timeout);
  }

  @Override
  public synchronized void asynchCloseAllConnections() {
    closeAllConnections(true, 0);
  }

  private void closeAllConnections(boolean async, long timeout) {
    TCConnection[] conns;

    synchronized (connections) {
      conns = connections.toArray(EMPTY_CONNECTION_ARRAY);
    }

    for (TCConnection conn : conns) {
      try {
        if (async) {
          conn.asynchClose();
        } else {
          conn.close(timeout);
        }
      } catch (Exception e) {
        logger.error("Exception trying to close " + conn, e);
      }
    }
  }

  @Override
  public synchronized void closeAllListeners() {
    TCListener[] list;

    synchronized (listeners) {
      list = listeners.toArray(EMPTY_LISTENER_ARRAY);
    }

    for (TCListener lsnr : list) {
      try {
        lsnr.stop();
      } catch (Exception e) {
        logger.error("Exception trying to close " + lsnr, e);
      }
    }
  }

  @Override
  public TCComm getTcComm() {
    return this.comm;
  }

  @Override
  public final synchronized void shutdown() {
    if (shutdown.attemptSet()) {
      closeAllListeners();
      asynchCloseAllConnections();
      this.buffers.close();
      comm.stop();
    }
  }

  void connectionClosed(TCConnection conn) {
    synchronized (connections) {
      connections.remove(conn);
    }
  }

  void newConnection(TCConnection conn) {
    synchronized (connections) {
      connections.add(conn);
    }
  }

  void removeConnection(TCConnection connection) {
    synchronized (connections) {
      connections.remove(connection);
    }
  }

  protected TCConnectionEventListener getConnectionListener() {
    return connEvents;
  }

  private void checkShutdown() throws IOException {
    if (shutdown.isSet()) { throw new IOException("connection manager shutdown"); }
  }

  static class ConnectionEvents implements TCConnectionEventListener {
    @Override
    public final void connectEvent(TCConnectionEvent event) {
      if (logger.isDebugEnabled()) {
        logger.debug("connect event: " + event.toString());
      }
    }

    @Override
    public final void closeEvent(TCConnectionEvent event) {
      if (logger.isDebugEnabled()) {
        logger.debug("close event: " + event.toString());
      }
    }

    @Override
    public final void errorEvent(TCConnectionErrorEvent event) {
      try {
        final Throwable err = event.getException();
        if (err != null) {
          if (err instanceof IOException) {
            if (!event.getSource().isClosed()) {
              logger.info("error event on connection " + event.getSource() + ": " + err.getMessage());
            } else if (logger.isDebugEnabled()) {
              logger.debug("error event on connection " + event.getSource() + ": " + err.getMessage(), err);
            }
          } else {
            logger.error("Exception: ", err);
          }
        }
      } finally {
        event.getSource().asynchClose();
      }
    }

    @Override
    public final void endOfFileEvent(TCConnectionEvent event) {
      if (logger.isDebugEnabled()) {
        logger.debug("EOF event: " + event.toString());
      }

      event.getSource().asynchClose();
    }
  }

  class ListenerEvents implements TCListenerEventListener {
    @Override
    public void closeEvent(TCListenerEvent event) {
      synchronized (listeners) {
        listeners.remove(event.getSource());
      }
    }
  }

  TCDirectByteBufferCache getBufferCache() {
    return buffers;
  }
  
  public int getBufferCount() {
    return buffers.referenced();
  }

}
