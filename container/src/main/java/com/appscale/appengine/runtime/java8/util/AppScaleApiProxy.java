/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8.util;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import javax.xml.ws.http.HTTPException;
import com.google.appengine.repackaged.com.google.common.flogger.GoogleLogger;
import com.google.appengine.repackaged.com.google.net.util.proto2api.Status.StatusProto;
import com.google.appengine.repackaged.org.apache.commons.httpclient.HttpClient;
import com.google.appengine.repackaged.org.apache.commons.httpclient.methods.ByteArrayRequestEntity;
import com.google.appengine.repackaged.org.apache.commons.httpclient.methods.PostMethod;
import com.google.appengine.tools.development.ApiProxyLocal;
import com.google.appengine.tools.development.ApiUtils;
import com.google.appengine.tools.development.Clock;
import com.google.appengine.tools.development.DevSocketImplFactory;
import com.google.appengine.tools.development.LocalCapabilitiesEnvironment;
import com.google.appengine.tools.development.LocalEnvironment;
import com.google.appengine.tools.development.LocalRpcService;
import com.google.appengine.tools.development.LocalRpcService.Status;
import com.google.appengine.tools.development.LocalServerEnvironment;
import com.google.appengine.tools.development.LocalServiceContext;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.ApiProxy.ApiConfig;
import com.google.apphosting.api.ApiProxy.ApiProxyException;
import com.google.apphosting.api.ApiProxy.CallNotFoundException;
import com.google.apphosting.api.ApiProxy.CancelledException;
import com.google.apphosting.api.ApiProxy.Environment;
import com.google.apphosting.api.ApiProxy.LogRecord;
import com.google.apphosting.api.ApiProxy.RequestTooLargeException;
import com.google.apphosting.api.ApiProxy.UnknownException;
import com.google.apphosting.utils.remoteapi.RemoteApiPb.Request;
import com.google.apphosting.utils.remoteapi.RemoteApiPb.Response;
import com.google.apphosting.utils.runtime.ApiProxyUtils;

/**
 *
 */
public class AppScaleApiProxy implements ApiProxyLocal {
  private static final String NAME = AppScaleApiProxy.class.getName();
  @SuppressWarnings("deprecation")
  private static final GoogleLogger googleLogger = GoogleLogger.forInjectedClassName(NAME);
  private static final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(NAME);

  private final LocalServiceContext context;
  private final int externalApiPort;
  private final Set<String> externalApis;
  private final Map<String, String> properties = new HashMap<>();
  private final Map<String, LocalRpcService> serviceCache = new ConcurrentHashMap<>();
  private final Map<String, Method> methodCache = new ConcurrentHashMap<>();
  private final ExecutorService apiExecutor =
      Executors.newCachedThreadPool(new DaemonThreadFactory(Executors.defaultThreadFactory()));

  public static ApiProxyLocal create(
      final LocalServerEnvironment localServerEnvironment,
      final Set<String> externalApis,
      final int externalApiPort
  ) {
    return new AppScaleApiProxy(localServerEnvironment, externalApis, externalApiPort);
  }

  private AppScaleApiProxy(
      final LocalServerEnvironment environment,
      final Set<String> externalApis,
      final int externalApiPort
  ) {
    this.context = new AppScaleLocalServiceContext(environment);
    this.externalApis = externalApis;
    this.externalApiPort = externalApiPort;
  }

  @Override
  public void setProperty(final String serviceProperty, final String value) {
    if (serviceProperty == null) {
      throw new NullPointerException("Property key must not be null.");
    } else {
      String[] propertyComponents = serviceProperty.split("\\.");
      if (propertyComponents.length < 2) {
        throw new IllegalArgumentException("Property string must be of the form {service}.{property}, received: " +
            serviceProperty);
      } else {
        this.properties.put(serviceProperty, value);
      }
    }
  }

  @Override
  public void setProperties(final Map<String, String> properties) {
    this.properties.clear();
    if (properties != null) {
      this.appendProperties(properties);
    }
  }

  @Override
  public void appendProperties(final Map<String, String> properties) {
    this.properties.putAll(properties);
  }

  @Override
  public void stop() {
    for (final LocalRpcService service : this.serviceCache.values()) {
      service.stop();
    }

    this.serviceCache.clear();
    this.methodCache.clear();
  }

  @Override
  public LocalRpcService getService(final String pkg) {
    LocalRpcService cachedService = this.serviceCache.get(pkg);
    return cachedService != null ?
        cachedService :
        AccessController.doPrivileged((PrivilegedAction<LocalRpcService>) () -> startServices(pkg));
  }

  @Override
  public Clock getClock() {
    return Clock.DEFAULT;
  }

  @Override
  public void setClock(final Clock clock) {
  }

  @Override
  public void log(final Environment environment, final LogRecord logRecord) {
    logger.logp(toJavaLevel(logRecord.getLevel()), NAME, "log", logRecord.getMessage());
  }

  @Override
  public void flushLogs(final Environment environment) {
    System.err.flush();
  }

  @Override
  public List<Thread> getRequestThreads(final Environment environment) {
    return Collections.singletonList(Thread.currentThread());
  }


  @Override
  public byte[] makeSyncCall(
      final Environment environment,
      final String packageName,
      final String methodName,
      final byte[] requestBytes
  ) throws ApiProxyException {
    try {
      return makeAsyncCall(environment, packageName, methodName, requestBytes, null).get();
    } catch (InterruptedException | CancellationException e) {
      throw new CancelledException(packageName, methodName);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException) {
        throw (RuntimeException)e.getCause();
      } else if (e.getCause() instanceof Error) {
        throw (Error)e.getCause();
      } else {
        throw new UnknownException(packageName, methodName, e.getCause());
      }
    }
  }

  @Override
  public Future<byte[]> makeAsyncCall(
      final Environment environment,
      final String packageName,
      final String methodName,
      final byte[] requestBytes,
      final ApiConfig apiConfig
  ) {
    final Semaphore semaphore = (Semaphore)environment.getAttributes().get(LocalEnvironment.API_CALL_SEMAPHORE);
    if (semaphore != null) {
      try {
        semaphore.acquire();
      } catch (InterruptedException e) {
        throw new RuntimeException("Interrupted while waiting on semaphore:", e);
      }
    }

    final boolean useExternal = this.externalApis.contains(packageName);
    final AsyncApiCall asyncApiCall =
        new AsyncApiCall(environment, packageName, methodName, requestBytes, semaphore, useExternal);

    boolean success = false;
    Future<byte[]> future;
    try {
      final Callable<byte[]> callable = Executors.privilegedCallable(asyncApiCall);
      future = AccessController.doPrivileged(new PrivilegedApiAction(callable, asyncApiCall));
      success = true;
    } finally {
      if (!success) {
        asyncApiCall.tryReleaseSemaphore();
      }
    }
    return future;
  }

  private static Level toJavaLevel(final LogRecord.Level apiProxyLevel) {
    switch(apiProxyLevel) {
      case debug:
        return Level.FINE;
      case info:
        return Level.INFO;
      case warn:
        return Level.WARNING;
      case error:
        return Level.SEVERE;
      case fatal:
        return Level.SEVERE;
      default:
        return Level.WARNING;
    }
  }

  private static int getMaxApiRequestSize(final LocalRpcService rpcService) {
    final Integer size = rpcService.getMaxApiRequestSize();
    return size == null ? 1048576 : size;
  }

  private Method getDispatchMethod(
      final LocalRpcService service,
      final String packageName,
      final String methodName
  ) {
    final char methodStartChar = Character.toLowerCase(methodName.charAt(0));
    final String remainingMethodName = methodName.substring(1);
    final String dispatchName = methodStartChar + remainingMethodName;
    final String methodId = packageName + "." + dispatchName;
    final Method method = this.methodCache.get(methodId);
    if (method != null) {
      return method;
    } else {
      final Method[] methods = service.getClass().getMethods();
      for (Method candidate : methods) {
        if (dispatchName.equals(candidate.getName())) {
          this.methodCache.put(methodId, candidate);
          return candidate;
        }
      }
      throw new CallNotFoundException(packageName, methodName);
    }
  }

  private LocalRpcService startServices(final String pkg) {
    final Iterator serviceIterator =
        ServiceLoader.load(LocalRpcService.class, AppScaleApiProxy.class.getClassLoader()).iterator();

    LocalRpcService service;
    do {
      if (!serviceIterator.hasNext()) {
        return null;
      }
      service = (LocalRpcService)serviceIterator.next();
    } while(!service.getPackage().equals(pkg));

    service.init(this.context, this.properties);
    service.start();
    this.serviceCache.put(pkg, service);
    return service;
  }

  private class AsyncApiCall implements Callable<byte[]> {
    private final Environment environment;
    private final String packageName;
    private final String methodName;
    private final byte[] requestBytes;
    private final Semaphore semaphore;
    private final boolean useExternal;

    private boolean released;

    public AsyncApiCall(
        final Environment environment,
        final String packageName,
        final String methodName,
        final byte[] requestBytes,
        final Semaphore semaphore,
        final boolean useExternal
    ) {
      this.environment = environment;
      this.packageName = packageName;
      this.methodName = methodName;
      this.requestBytes = requestBytes;
      this.semaphore = semaphore;
      this.useExternal = useExternal;
    }

    public byte[] call() {
      try {
        ApiProxy.setEnvironmentForCurrentThread(this.environment);
        try {
          return this.doCall();
        } finally {
          ApiProxy.clearEnvironmentForCurrentThread();
        }
      } finally {
        this.tryReleaseSemaphore();
      }
    }

    private byte[] doCall() {
      try {
        if (this.useExternal) {
          return this.invokeExternalApi(this.packageName, this.methodName, this.requestBytes);
        } else {
          return this.invokeInternalApi(this.packageName, this.methodName, this.requestBytes);
        }
      } catch (InvocationTargetException e) {
        if (e.getCause() instanceof RuntimeException) {
          throw (RuntimeException)e.getCause();
        }
        throw new UnknownException(this.packageName, this.methodName, e.getCause());
      } catch (IOException | ReflectiveOperationException e) {
        throw new UnknownException(this.packageName, this.methodName, e);
      }
    }

    private byte[] invokeInternalApi(
        final String packageName,
        final String methodName,
        final byte[] requestBytes
    ) throws ReflectiveOperationException {
      logger.logp(Level.FINE, AsyncApiCall.class.getName(), "invokeInternalApi",
          "Making an API call to a Java implementation: " + packageName + "." + methodName);
      final LocalRpcService service = getService(packageName);
      if (service == null) {
        throw new CallNotFoundException(packageName, methodName);
      } else if (requestBytes.length > getMaxApiRequestSize(service)) {
        throw new RequestTooLargeException(packageName, methodName);
      } else {
        final Method method = getDispatchMethod(service, packageName, methodName);
        final Status status = new Status();
        final Class<?> requestClass = method.getParameterTypes()[1];
        final Object request = ApiUtils.convertBytesToPb(requestBytes, requestClass);

        return ApiUtils.convertPbToBytes(method.invoke(service, status, request));
      }
    }

    private byte[] invokeExternalApi(
        final String packageName,
        final String methodName,
        final byte[] requestBytes
    ) throws IOException {
      logger.logp(Level.FINE, AsyncApiCall.class.getName(), "invokeExternalApi",
          "Making an API call to an api service implementation: " + packageName + "." + methodName);

      final Request remoteApiRequest = new Request();
      remoteApiRequest.setServiceName(packageName);
      remoteApiRequest.setMethod(methodName);
      remoteApiRequest.setRequestAsBytes(requestBytes);
      remoteApiRequest.setRequestId(UUID.randomUUID().toString().substring(0, 10));

      final byte[] remoteApiRequestBytes = ApiUtils.convertPbToBytes(remoteApiRequest);
      final PostMethod post = new PostMethod("http://localhost:" + externalApiPort);
      post.setFollowRedirects(false);
      post.addRequestHeader("Host", "localhost");
      post.addRequestHeader("Content-Type", "application/octet-stream");
      post.setRequestEntity(new ByteArrayRequestEntity(remoteApiRequestBytes));
      boolean oldNativeSocketMode = DevSocketImplFactory.isNativeSocketMode();

      DevSocketImplFactory.setSocketNativeMode(true);
      try {
        final HttpClient httpClient = new HttpClient();
        httpClient.executeMethod(post);
        if (post.getStatusCode() != 200) {
          throw new HTTPException(post.getStatusCode());
        }
      } catch (IOException e) {
        throw new IOException("Error executing POST to HTTP API server.");
      } finally {
        DevSocketImplFactory.setSocketNativeMode(oldNativeSocketMode);
      }

      final Response response = new Response();
      final boolean parsed = response.mergeFrom(post.getResponseBodyAsStream());
      if (!parsed) {
        throw new IOException("Error parsing the response from the HTTP API server.");
      } else if (response.hasApplicationError()) {
        throw ApiProxyUtils.getRpcError(packageName, methodName, StatusProto.getDefaultInstance(),
            response.getApplicationError().getCode(), response.getApplicationError().getDetail(), null);
      } else if (response.hasRpcError()) {
        throw ApiProxyUtils.getApiError(packageName, methodName, response, googleLogger);
      } else {
        return response.getResponseAsBytes();
      }
    }

    synchronized void tryReleaseSemaphore() {
      if (!this.released && this.semaphore != null) {
        this.semaphore.release();
        this.released = true;
      }
    }
  }

  private class PrivilegedApiAction implements PrivilegedAction<Future<byte[]>> {
    private final Callable<byte[]> callable;
    private final AsyncApiCall asyncApiCall;

    PrivilegedApiAction(final Callable<byte[]> callable, final AsyncApiCall asyncApiCall) {
      this.callable = callable;
      this.asyncApiCall = asyncApiCall;
    }

    public Future<byte[]> run() {
      final Future<byte[]> result = apiExecutor.submit(this.callable);
      return new Future<byte[]>() {
        public boolean cancel(final boolean mayInterruptIfRunning) {
          return AccessController.doPrivileged((PrivilegedAction<Boolean>) () -> {
            PrivilegedApiAction.this.asyncApiCall.tryReleaseSemaphore();
            return result.cancel(mayInterruptIfRunning);
          });
        }

        public boolean isCancelled() {
          return result.isCancelled();
        }

        public boolean isDone() {
          return result.isDone();
        }

        public byte[] get() throws InterruptedException, ExecutionException {
          return result.get();
        }

        public byte[] get(
            final long timeout,
            final TimeUnit unit
        ) throws InterruptedException, ExecutionException, TimeoutException {
          return result.get(timeout, unit);
        }
      };
    }
  }

  private static class DaemonThreadFactory implements ThreadFactory {
    private final ThreadFactory parent;

    public DaemonThreadFactory(final ThreadFactory parent) {
      this.parent = parent;
    }

    public Thread newThread(final Runnable runnable) {
      Thread thread = this.parent.newThread(runnable);
      thread.setDaemon(true);
      return thread;
    }
  }

  private class AppScaleLocalServiceContext implements LocalServiceContext {
    private final LocalServerEnvironment localServerEnvironment;
    private final LocalCapabilitiesEnvironment localCapabilitiesEnvironment =
        new LocalCapabilitiesEnvironment(System.getProperties());

    public AppScaleLocalServiceContext(final LocalServerEnvironment localServerEnvironment) {
      this.localServerEnvironment = localServerEnvironment;
    }

    public LocalServerEnvironment getLocalServerEnvironment() {
      return this.localServerEnvironment;
    }

    public LocalCapabilitiesEnvironment getLocalCapabilitiesEnvironment() {
      return this.localCapabilitiesEnvironment;
    }

    public Clock getClock() {
      return Clock.DEFAULT;
    }

    public LocalRpcService getLocalService(final String packageName) {
      return AppScaleApiProxy.this.getService(packageName);
    }
  }
}
