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
package com.terracotta.diagnostic;

import com.tc.util.concurrent.ThreadUtil;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.terracotta.connection.DiagnosticsConfig;
import org.terracotta.entity.EntityClientEndpoint;
import org.terracotta.entity.EntityClientService;
import org.terracotta.entity.EntityMessage;
import org.terracotta.entity.EntityResponse;
import org.terracotta.entity.InvokeFuture;
import org.terracotta.entity.MessageCodec;
import org.terracotta.entity.MessageCodecException;
import org.terracotta.exception.EntityException;


public class DiagnosticEntityClientService implements EntityClientService<Diagnostics, Object, EntityMessage, EntityResponse, Object>{

  @Override
  public boolean handlesEntityType(Class<Diagnostics> type) {
    return type.isAssignableFrom(com.terracotta.diagnostic.Diagnostics.class);
  }

  @Override
  public byte[] serializeConfiguration(Object c) {
    return new byte[] {};
  }

  @Override
  public Object deserializeConfiguration(byte[] bytes) {
    return new Object();
  }

  @Override
  public Diagnostics create(final EntityClientEndpoint<EntityMessage, EntityResponse> ece, Object config) {
    Properties possible = getRequestProperties(config);
    Runnable closeHook = getCloseHook(ece, config);
    final int timeoutInMillis = possible != null ? Integer.parseInt(possible.getProperty("request.timeout", "2000")) : 2000;
    final String timeoutMessage = possible != null ? possible.getProperty("request.timeoutMessage", "Request Timeout") : "Request Timeout";
    return (Diagnostics)Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] {Diagnostics.class},
            new java.lang.reflect.InvocationHandler() {
      @Override
      public Object invoke(Object proxy, Method method, final Object[] args) throws Throwable {
        try {
          final String methodName = method.getName();
          if (methodName.equals("close")) {
            closeHook.run();
          } else {
            InvokeFuture returnValue = ece.beginInvoke().message(new EntityMessage() {
              @Override
              public String toString() {
                if (methodName.equals("get")) {
                  return "getJMX " + args[0] + " " + args[1];
                } else if (methodName.equals("set")) {
                  return "setJMX " + args[0] + " " + args[1] + " " + args[2];
                } else if (methodName.equals("invoke")) {
                  return "invokeJMX " + args[0] + " " + args[1];
                } else if (methodName.equals("invokeWithArg")) {
                  return "invokeWithArgJMX " + args[0] + " " + args[1] + " " + args[2];
                } else {
                  StringBuilder cmd = new StringBuilder();
                  cmd.append(methodName);
                  if (args != null) {
                    for (int x=0;x<args.length;x++) {
                      cmd.append(' ');
                      cmd.append(args[x]);
                    }
                  }
                  return cmd.toString();
                }
              }
            }).invoke();
            // if the server is terminating, never going to get a message back.  just return null
            if (!methodName.equals("terminateServer") && !methodName.equals("forceTerminateServer") && !methodName.equals("restartServer")) {
              try {
                return returnValue.getWithTimeout(timeoutInMillis, TimeUnit.MILLISECONDS).toString();
              } catch (TimeoutException timeout) {
                return timeoutMessage;
              }
            }
          }
        } catch (EntityException ee) {
          RuntimeException t = ThreadUtil.getRootCause(ee, RuntimeException.class);
          if (t != null) {
            throw t;
          }
        } catch (InterruptedException ie) {
          return "ERROR:interrupted";
        } catch (MessageCodecException code) {
          return "ERROR:" + code.getClass() + ":" + code.getMessage();
        }
        return null;
      }}
    );
  }

  private Properties getRequestProperties(Object props) {
    if (props instanceof Properties) {
      return (Properties)props;
    } else if (props instanceof DiagnosticsConfig) {
      return ((DiagnosticsConfig)props).getProperties();
    }
    return new Properties();
  }

  private Runnable getCloseHook(EntityClientEndpoint endpoint, Object props) {
    CompletableFuture<Void> closer = new CompletableFuture<>().thenRun(endpoint::close);
    if (props instanceof DiagnosticsConfig) {
      closer.thenRun(((DiagnosticsConfig)props).getClose());
    }
    return ()->closer.complete(null);
  }

  @Override
  public MessageCodec<EntityMessage, EntityResponse> getMessageCodec() {
    final Charset charset = Charset.forName("UTF-8");

    return new MessageCodec<EntityMessage, EntityResponse>() {
      @Override
      public byte[] encodeMessage(EntityMessage m) throws MessageCodecException {
        return m.toString().getBytes(charset);
      }

      @Override
      public EntityMessage decodeMessage(final byte[] bytes) throws MessageCodecException {
        return new EntityMessage() {
          @Override
          public String toString() {
            return new String(bytes, charset);
          }
        };
      }

      @Override
      public byte[] encodeResponse(EntityResponse r) throws MessageCodecException {
        return r.toString().getBytes(charset);
      }

      @Override
      public EntityResponse decodeResponse(final byte[] bytes) throws MessageCodecException {
        return new EntityResponse() {
          @Override
          public String toString() {
            return new String(bytes, charset);
          }
        };
      }
    };
  }

}
