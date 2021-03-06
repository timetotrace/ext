/*
 * Copyright (c) 2011-2013 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.sockjs.impl;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.DecodeException;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.impl.LoggerFactory;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.shareddata.LocalMap;
import io.vertx.core.shareddata.Shareable;
import io.vertx.ext.sockjs.SockJSSocket;

import java.util.LinkedList;
import java.util.Queue;

import static io.vertx.core.buffer.Buffer.*;

/**
 * The SockJS session implementation.
 *
 * If multiple instances of the SockJS server are used then instances of this
 * class can be accessed by different threads (not concurrently), so we store
 * it in a shared data map
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
class Session extends SockJSSocketBase implements Shareable {

  private static final Logger log = LoggerFactory.getLogger(Session.class);
  private final LocalMap<String, Session> sessions;
  private final Queue<String> pendingWrites = new LinkedList<>();
  private final Queue<String> pendingReads = new LinkedList<>();
  private TransportListener listener;
  private Handler<Buffer> dataHandler;
  private boolean closed;
  private boolean openWritten;
  private final String id;
  private final long timeout;
  private final Handler<SockJSSocket> sockHandler;
  private long heartbeatID = -1;
  private long timeoutTimerID = -1;
  private boolean paused;
  private int maxQueueSize = 64 * 1024; // Message queue size is measured in *characters* (not bytes)
  private int messagesSize;
  private Handler<Void> drainHandler;
  private Handler<Void> endHandler;
  private Handler<Throwable> exceptionHandler;
  private boolean handleCalled;
  private SocketAddress localAddress;
  private SocketAddress remoteAddress;
  private String uri;
  private MultiMap headers;

  Session(Vertx vertx, LocalMap<String, Session> sessions, long heartbeatPeriod,
          Handler<SockJSSocket> sockHandler) {
    this(vertx, sessions, null, -1, heartbeatPeriod, sockHandler);
  }

  Session(Vertx vertx, LocalMap<String, Session> sessions, String id, long timeout, long heartbeatPeriod,
          Handler<SockJSSocket> sockHandler) {
    super(vertx);
    this.sessions = sessions;
    this.id = id;
    this.timeout = timeout;
    this.sockHandler = sockHandler;

    // Start a heartbeat

    heartbeatID = vertx.setPeriodic(heartbeatPeriod, new Handler<Long>() {
      public void handle(Long id) {
        if (listener != null) {
          listener.sendFrame("h");
        }
      }
    });
  }

  @Override
  public synchronized SockJSSocket write(Buffer buffer) {
    String msgStr = buffer.toString();
    pendingWrites.add(msgStr);
    this.messagesSize += msgStr.length();
    if (listener != null) {
      writePendingMessages();
    }
    return this;
  }

  @Override
  public synchronized Session handler(Handler<Buffer> handler) {
    this.dataHandler = handler;
    return this;
  }

  @Override
  public synchronized Session pause() {
    paused = true;
    return this;
  }

  @Override
  public synchronized Session resume() {
    paused = false;
    if (dataHandler != null) {
      for (String msg: this.pendingReads) {
        dataHandler.handle(buffer(msg));
      }
    }
    return this;
  }

  @Override
  public synchronized Session setWriteQueueMaxSize(int maxQueueSize) {
    if (maxQueueSize < 1) {
      throw new IllegalArgumentException("maxQueueSize must be >= 1");
    }
    this.maxQueueSize = maxQueueSize;
    return this;
  }

  @Override
  public synchronized boolean writeQueueFull() {
    return messagesSize >= maxQueueSize;
  }

  @Override
  public synchronized Session drainHandler(Handler<Void> handler) {
    this.drainHandler = handler;
    return this;
  }

  @Override
  public synchronized Session exceptionHandler(Handler<Throwable> handler) {
    this.exceptionHandler = handler;
    return this;
  }

  @Override
  public synchronized Session endHandler(Handler<Void> endHandler) {
    this.endHandler = endHandler;
    return this;
  }


  public synchronized void shutdown() {
    doClose();
  }

  // When the user calls close() we don't actually close the session - unless it's a websocket one
  // Yes, SockJS is weird, but it's hard to work out expected server behaviour when there's no spec
  @Override
  public synchronized void close() {
    if (endHandler != null) {
      endHandler.handle(null);
    }
    closed = true;
    if (listener != null && handleCalled) {
      listener.sessionClosed();
    }
  }

  @Override
  public SocketAddress remoteAddress() {
    return remoteAddress;
  }

  @Override
  public SocketAddress localAddress() {
    return localAddress;
  }

  @Override
  public MultiMap headers() {
    return headers;
  }

  @Override
  public String uri() {
    return uri;
  }

  synchronized boolean isClosed() {
    return closed;
  }

  synchronized void resetListener() {
    listener = null;
    // We set a timer that will kick in and close the session if the client doesn't come back
    // We MUST ALWAYS do this or we can get a memory leak on the server
    setTimer();
  }

  private void cancelTimer() {
    if (timeoutTimerID != -1) {
      vertx.cancelTimer(timeoutTimerID);
    }
  }

  private void setTimer() {
    if (timeout != -1) {
      cancelTimer();
      timeoutTimerID = vertx.setTimer(timeout, new Handler<Long>() {
        public void handle(Long id) {
          vertx.cancelTimer(heartbeatID);
          if (listener == null) {
            shutdown();
          }
          if (listener != null) {
            listener.close();
          }
        }
      });
    }
  }

  synchronized void writePendingMessages() {
    String json = JsonCodec.encode(pendingWrites.toArray());
    listener.sendFrame("a" + json);
    pendingWrites.clear();
    messagesSize = 0;
    if (drainHandler != null && messagesSize <= maxQueueSize / 2) {
      Handler<Void> dh = drainHandler;
      drainHandler = null;
      dh.handle(null);
    }
  }

  synchronized void register(final TransportListener lst) {
    if (closed) {
      // Closed by the application
      writeClosed(lst);
      // And close the listener request
      lst.close();
    } else if (this.listener != null) {
      writeClosed(lst, 2010, "Another connection still open");
      // And close the listener request
      lst.close();
    } else {

      cancelTimer();

      this.listener = lst;

      if (!openWritten) {
        writeOpen(lst);
        sockHandler.handle(this);
        handleCalled = true;
      }

      if (listener != null) {
        if (closed) {
          // Could have already been closed by the user
          writeClosed(lst);
          listener = null;
          lst.close();
        } else {
          if (!pendingWrites.isEmpty()) {
            writePendingMessages();
          }
        }
      }
    }
  }

  // Actually close the session - when the user calls close() the session actually continues to exist until timeout
  // Yes, I know it's weird but that's the way SockJS likes it.
  private void doClose() {
    super.close(); // We must call this or handlers don't get unregistered and we get a leak
    if (heartbeatID != -1) {
      vertx.cancelTimer(heartbeatID);
    }
    if (timeoutTimerID != -1) {
      vertx.cancelTimer(timeoutTimerID);
    }
    if (id != null) {
      // Can be null if websocket session
      sessions.remove(id);
    }

    if (!closed) {
      closed = true;
      if (endHandler != null) {
        endHandler.handle(null);
      }
    }
  }

  private String[] parseMessageString(String msgs) {
    try {
      String[] parts;
      if (msgs.startsWith("[")) {
        //JSON array
        parts = (String[])JsonCodec.decodeValue(msgs, String[].class);
      } else {
        //JSON string
        String str = (String)JsonCodec.decodeValue(msgs, String.class);
        parts = new String[] { str };
      }
      return parts;
    } catch (DecodeException e) {
      return null;
    }
  }

  boolean handleMessages(String messages) {

    String[] msgArr = parseMessageString(messages);

    if (msgArr == null) {
      return false;
    } else {
      if (dataHandler != null) {
        for (String msg : msgArr) {
          if (!paused) {
            try {
              dataHandler.handle(buffer(msg));
            } catch (Throwable t) {
              log.error("Unhandle exception", t);
            }
          } else {
            pendingReads.add(msg);
          }
        }
      }
      return true;
    }
  }

  void handleException(Throwable t) {
    if (exceptionHandler != null) {
      exceptionHandler.handle(t);
    } else {
      log.error("Unhandled exception", t);
    }
  }

  public void writeClosed(TransportListener lst) {
    writeClosed(lst, 3000, "Go away!");
  }

  private void writeClosed(TransportListener lst, int code, String msg) {
    StringBuilder sb = new StringBuilder("c[");
    sb.append(String.valueOf(code)).append(",\"");
    sb.append(msg).append("\"]");
    lst.sendFrame(sb.toString());
  }

  private void writeOpen(TransportListener lst) {
    StringBuilder sb = new StringBuilder("o");
    lst.sendFrame(sb.toString());
    openWritten = true;
  }

  void setInfo(SocketAddress localAddress, SocketAddress remoteAddress, String uri,
               MultiMap headers) {
    this.localAddress = localAddress;
    this.remoteAddress = remoteAddress;
    this.uri = uri;
    this.headers = BaseTransport.removeCookieHeaders(headers);
  }
}
