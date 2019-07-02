/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8.server;

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
import java.util.logging.Logger;
import com.google.appengine.api.capabilities.CapabilityStatus;
import com.google.appengine.repackaged.com.google.common.collect.Sets;
import com.google.appengine.tools.development.ApiProxyLocal;
import com.google.appengine.tools.development.ApiUtils;
import com.google.appengine.tools.development.Clock;
import com.google.appengine.tools.development.DevLogService;
import com.google.appengine.tools.development.DevServices;
import com.google.appengine.tools.development.LocalCapabilitiesEnvironment;
import com.google.appengine.tools.development.LocalRpcService;
import com.google.appengine.tools.development.LocalRpcService.Status;
import com.google.appengine.tools.development.LocalServerEnvironment;
import com.google.appengine.tools.development.LocalServiceContext;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.ApiProxy.ApiConfig;
import com.google.apphosting.api.ApiProxy.CallNotFoundException;
import com.google.apphosting.api.ApiProxy.CancelledException;
import com.google.apphosting.api.ApiProxy.CapabilityDisabledException;
import com.google.apphosting.api.ApiProxy.Environment;
import com.google.apphosting.api.ApiProxy.FeatureNotEnabledException;
import com.google.apphosting.api.ApiProxy.LogRecord;
import com.google.apphosting.api.ApiProxy.RequestTooLargeException;
import com.google.apphosting.api.ApiProxy.UnknownException;

/**
 *
 */
public class AppScaleApiProxyLocal implements ApiProxyLocal, DevServices {
  private static final Logger logger = Logger.getLogger(AppScaleApiProxyLocal.class.getName());

  private static final int MAX_API_REQUEST_SIZE = 1048576;
  private static final String API_DEADLINE_KEY = "com.google.apphosting.api.ApiProxy.api_deadline_key";

  private final Map<String, LocalRpcService> serviceCache = new ConcurrentHashMap<>();
  private final Map<String, Method> methodCache = new ConcurrentHashMap<>();
  private final Map<String, String> properties = new HashMap<>();
  private final ExecutorService apiExecutor = Executors.newCachedThreadPool(
      new AppScaleApiProxyLocal.DaemonThreadFactory(Executors.defaultThreadFactory()));
  private final LocalServiceContext context;
  private final Set<String> apisUsingPythonStubs;
  private final AppScaleApiClient apiClient;
  private final Clock clock;

  public AppScaleApiProxyLocal(
      final LocalServerEnvironment environment,
      final Set<String> apisUsingPythonStubs,
      final AppScaleApiClient apiClient
  ) {
    this.clock = Clock.DEFAULT;
    this.context = new AppScaleApiProxyLocal.LocalServiceContextImpl(environment);
    this.apisUsingPythonStubs = Collections.unmodifiableSet(Sets.newTreeSet(apisUsingPythonStubs));
    this.apiClient = apiClient;
  }

  public void log(Environment environment, LogRecord record) {
    logger.log(toJavaLevel(record.getLevel()), record.getMessage());
  }

  public void flushLogs(Environment environment) {
    System.err.flush();
  }

  public byte[] makeSyncCall(
      final Environment environment,
      final String packageName,
      final String methodName,
      final byte[] requestBytes
  ) {
    ApiConfig apiConfig = null;
    Double deadline = (Double)environment.getAttributes().get(API_DEADLINE_KEY);
    if (deadline != null) {
      apiConfig = new ApiConfig();
      apiConfig.setDeadlineInSeconds(deadline);
    }

    Future future = this.makeAsyncCall(environment, packageName, methodName, requestBytes, apiConfig);

    try {
      return (byte[])future.get();
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

  public Future<byte[]> makeAsyncCall(
      final Environment environment,
      final String packageName,
      final String methodName,
      final byte[] requestBytes,
      final ApiConfig apiConfig
  ) {
    final Semaphore semaphore = (Semaphore)environment.getAttributes().get("com.google.appengine.tools.development.api_call_semaphore");
    if (semaphore != null) {
      try {
        semaphore.acquire();
      } catch (InterruptedException e) {
        throw new RuntimeException("Interrupted while waiting on semaphore:", e);
      }
    }

    final boolean apiCallShouldUsePythonStub = this.apisUsingPythonStubs.contains(packageName);
    final AppScaleApiProxyLocal.AsyncApiCall asyncApiCall =
        new AppScaleApiProxyLocal.AsyncApiCall(environment, packageName, methodName,
                                               requestBytes, semaphore, apiCallShouldUsePythonStub);

    final Future<byte[]>  callFuture;
    boolean success = false;
    try {
      final Callable<byte[]> callable = Executors.privilegedCallable(asyncApiCall);
      callFuture = AccessController.doPrivileged(new PrivilegedApiAction(callable, asyncApiCall));
      success = true;
    } finally {
      if (!success) {
        asyncApiCall.tryReleaseSemaphore();
      }
    }

    return callFuture;
  }

  public List<Thread> getRequestThreads(final Environment environment) {
    return Collections.singletonList(Thread.currentThread());
  }

  public void setProperty(String serviceProperty, String value) {
    if (serviceProperty == null) {
      throw new NullPointerException("Property key must not be null.");
    } else {
      final String[] propertyComponents = serviceProperty.split("\\.");
      if (propertyComponents.length < 2) {
        throw new IllegalArgumentException("Property string must be of the form"
            + " {service}.{property}, received: " + serviceProperty);
      } else {
        this.properties.put(serviceProperty, value);
      }
    }
  }

  public void setProperties(Map<String, String> properties) {
    this.properties.clear();
    if (properties != null) {
      this.appendProperties(properties);
    }

  }

  public void appendProperties(Map<String, String> properties) {
    this.properties.putAll(properties);
  }

  public void stop() {

    for (final LocalRpcService service : this.serviceCache.values()) {
      service.stop();
    }

    this.serviceCache.clear();
    this.methodCache.clear();
  }

  int getMaxApiRequestSize(LocalRpcService rpcService) {
    Integer size = rpcService.getMaxApiRequestSize();
    return size == null ? MAX_API_REQUEST_SIZE : size;
  }

  private Method getDispatchMethod(LocalRpcService service, String packageName, String methodName) {
    final char methodStartChar = Character.toLowerCase(methodName.charAt(0));
    final String methodNameSuffix = methodName.substring(1);
    final String dispatchName = methodStartChar + methodNameSuffix;
    final String methodId = packageName + "." + dispatchName;
    final Method method = this.methodCache.get(methodId);
    if (method != null) {
      return method;
    } else {
      final Method[] serviceMethods = service.getClass().getMethods();

      for (final Method candidate : serviceMethods) {
        if (dispatchName.equals(candidate.getName())) {
          this.methodCache.put(methodId, candidate);
          return candidate;
        }
      }

      throw new CallNotFoundException(packageName, methodName);
    }
  }

  public final synchronized LocalRpcService getService(final String pkg) {
    final LocalRpcService cachedService = this.serviceCache.get(pkg);
    return cachedService != null ?
        cachedService :
        AccessController.doPrivileged(
            (PrivilegedAction<LocalRpcService>) () -> AppScaleApiProxyLocal.this.startServices(pkg));
  }

  public DevLogService getLogService() {
    return (DevLogService)this.getService("logservice");
  }

  private LocalRpcService startServices(String pkg) {
    final Iterator serviceIterator =
        ServiceLoader.load(LocalRpcService.class, AppScaleApiProxyLocal.class.getClassLoader()).iterator();

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

  private static Level toJavaLevel(com.google.apphosting.api.ApiProxy.LogRecord.Level apiProxyLevel) {
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

  public Clock getClock() {
    return this.clock;
  }

  public void setClock(Clock clock) {
  }

  private static class DaemonThreadFactory implements ThreadFactory {
    private final ThreadFactory parent;

    public DaemonThreadFactory(ThreadFactory parent) {
      this.parent = parent;
    }

    public Thread newThread(Runnable r) {
      Thread thread = this.parent.newThread(r);
      thread.setDaemon(true);
      return thread;
    }
  }

  private class AsyncApiCall implements Callable<byte[]> {
    private final Environment environment;
    private final String packageName;
    private final String methodName;
    private final byte[] requestBytes;
    private final Semaphore semaphore;
    private boolean released;
    private final boolean apiCallShouldUsePythonStub;

    public AsyncApiCall(Environment environment, String packageName, String methodName, byte[] requestBytes, Semaphore semaphore, boolean apiCallShouldUsePythonStub) {
      this.environment = environment;
      this.packageName = packageName;
      this.methodName = methodName;
      this.requestBytes = requestBytes;
      this.semaphore = semaphore;
      this.apiCallShouldUsePythonStub = apiCallShouldUsePythonStub;
    }

    public byte[] call() {
      byte[] callResult;
      try {
        callResult = this.callInternal();
      } finally {
        this.tryReleaseSemaphore();
      }

      return callResult;
    }

    private byte[] callInternal() {
      ApiProxy.setEnvironmentForCurrentThread(this.environment);

      byte[] callResult;
      try {
        if ("file".equals(this.packageName)) {
          if (!Boolean.getBoolean("appengine.enableFilesApi")) {
            throw new FeatureNotEnabledException("The Files API is disabled.", this.packageName, this.methodName);
          }
          this.environment.getAttributes().put("com.google.appengine.api.files.filesapi_was_used", true);
        }

        LocalCapabilitiesEnvironment capEnv = AppScaleApiProxyLocal.this.context.getLocalCapabilitiesEnvironment();
        CapabilityStatus capabilityStatus = capEnv.getStatusFromMethodName(this.packageName, this.methodName);
        if (!CapabilityStatus.ENABLED.equals(capabilityStatus)) {
          throw new CapabilityDisabledException("Setup in local configuration.", this.packageName, this.methodName);
        }

        if (this.apiCallShouldUsePythonStub) {
          callResult = this.invokeApiMethodPython(this.packageName, this.methodName, this.requestBytes);
          return callResult;
        }

        callResult = this.invokeApiMethodJava(this.packageName, this.methodName, this.requestBytes);
      } catch (InvocationTargetException e) {
        if (e.getCause() instanceof RuntimeException) {
          throw (RuntimeException)e.getCause();
        }
        throw new UnknownException(this.packageName, this.methodName, e.getCause());
      } catch (IOException | ReflectiveOperationException e) {
        throw new UnknownException(this.packageName, this.methodName, e);
      } finally {
        ApiProxy.clearEnvironmentForCurrentThread();
      }

      return callResult;
    }

    public byte[] invokeApiMethodJava(
        final String packageName,
        final String methodName,
        final byte[] requestBytes
    ) throws ReflectiveOperationException {
      logger.log(Level.FINE, "Making an API call to a Java implementation: " + packageName + "." + methodName);
      LocalRpcService service = AppScaleApiProxyLocal.this.getService(packageName);
      if (service == null) {
        throw new CallNotFoundException(packageName, methodName);
      } else if (requestBytes.length > AppScaleApiProxyLocal.this.getMaxApiRequestSize(service)) {
        throw new RequestTooLargeException(packageName, methodName);
      } else {
        Method method = AppScaleApiProxyLocal.this.getDispatchMethod(service, packageName, methodName);
        Status status = new Status();
        Class<?> requestClass = method.getParameterTypes()[1];
        Object request = ApiUtils.convertBytesToPb(requestBytes, requestClass);
        return ApiUtils.convertPbToBytes(method.invoke(service, status, request));
      }
    }

    public byte[] invokeApiMethodPython(String packageName, String methodName, byte[] requestBytes) throws IOException {
      logger.log(Level.FINE, "Making an API call to a Python implementation: " + packageName + "." + methodName);
      return AppScaleApiProxyLocal.this.apiClient.makeSyncCall(packageName, methodName, requestBytes);
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
    private final AppScaleApiProxyLocal.AsyncApiCall asyncApiCall;

    PrivilegedApiAction(Callable<byte[]> callable, AppScaleApiProxyLocal.AsyncApiCall asyncApiCall) {
      this.callable = callable;
      this.asyncApiCall = asyncApiCall;
    }

    public Future<byte[]> run() {
      final Future<byte[]> result = AppScaleApiProxyLocal.this.apiExecutor.submit(this.callable);
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

        public byte[] get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
          return result.get(timeout, unit);
        }
      };
    }
  }

  private class LocalServiceContextImpl implements LocalServiceContext {
    private final LocalServerEnvironment localServerEnvironment;
    private final LocalCapabilitiesEnvironment localCapabilitiesEnvironment =
        new LocalCapabilitiesEnvironment(System.getProperties());

    public LocalServiceContextImpl(LocalServerEnvironment localServerEnvironment) {
      this.localServerEnvironment = localServerEnvironment;
    }

    public LocalServerEnvironment getLocalServerEnvironment() {
      return this.localServerEnvironment;
    }

    public LocalCapabilitiesEnvironment getLocalCapabilitiesEnvironment() {
      return this.localCapabilitiesEnvironment;
    }

    public Clock getClock() {
      return AppScaleApiProxyLocal.this.clock;
    }

    public LocalRpcService getLocalService(String packageName) {
      return AppScaleApiProxyLocal.this.getService(packageName);
    }
  }
}
