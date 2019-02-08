/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8.server;

import java.io.File;
import java.net.BindException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.google.appengine.repackaged.com.google.common.base.Joiner;
import com.google.appengine.repackaged.com.google.common.base.Splitter;
import com.google.appengine.repackaged.com.google.common.collect.ImmutableMap;
import com.google.appengine.repackaged.com.google.common.collect.ImmutableSet;
import com.google.appengine.repackaged.com.google.common.collect.ImmutableSet.Builder;
import com.google.appengine.tools.development.AbstractContainerService;
import com.google.appengine.tools.development.ApiProxyLocal;
import com.google.appengine.tools.development.ApiProxyLocalFactory;
import com.google.appengine.tools.development.AppContext;
import com.google.appengine.tools.development.ApplicationConfigurationManager;
import com.google.appengine.tools.development.ApplicationConfigurationManager.ModuleConfigurationHandle;
import com.google.appengine.tools.development.DevAppServer;
import com.google.appengine.tools.development.DevLogService;
import com.google.appengine.tools.development.DevServices;
import com.google.appengine.tools.development.DevSocketImplFactory;
import com.google.appengine.tools.development.StreamHandlerFactory;
import com.google.appengine.tools.info.AppengineSdk;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.ApiProxy.Environment;
import com.google.apphosting.utils.config.AppEngineConfigException;

/**
 *
 */
public class AppScaleAppServer implements DevAppServer {
  private static final Logger logger = Logger.getLogger(AppScaleAppServer.class.getName());

  private final ApplicationConfigurationManager applicationConfigurationManager;
  private final AppScaleModules modules;
  private final Map<String, Object> containerConfigProperties;
  private final AppEngineConfigException configurationException;
  private final ScheduledExecutorService shutdownScheduler;
  private Map<String, String> serviceProperties = new ConcurrentHashMap<>();
  private ServerState serverState;
  private ApiProxyLocal apiProxyLocal;
  private CountDownLatch shutdownLatch;

  public AppScaleAppServer(
      final File appDir,
      final File webXmlLocation,
      final File appEngineWebXmlLocation,
      final String address,
      final int port,
      final boolean useCustomStreamHandler
  ) {
      this.serverState = ServerState.INITIALIZING;
      this.shutdownScheduler = Executors.newScheduledThreadPool(1);
      this.shutdownLatch = null;

      if (useCustomStreamHandler) {
        StreamHandlerFactory.install();
      }
      DevSocketImplFactory.install();

      ApplicationConfigurationManager tempManager;
      try {
        tempManager = ApplicationConfigurationManager.newWarConfigurationManager(appDir, appEngineWebXmlLocation, webXmlLocation, null, AppengineSdk.getSdk().getLocalVersion().getRelease(), "");
      } catch (AppEngineConfigException var13) {
        this.modules = null;
        this.applicationConfigurationManager = null;
        this.containerConfigProperties = null;
        this.configurationException = var13;
        return;
      }

      this.applicationConfigurationManager = tempManager;
      this.modules = AppScaleModules.createModules(this.applicationConfigurationManager, address, port, this);
      this.containerConfigProperties = ImmutableMap.of(
          "com.google.appengine.tools.development.modules_filter_helper", this.modules,
          "devappserver.portMappingProvider", new AppScalePortMappingProvider()
      );
      this.configurationException = null;
    }

    public void setServiceProperties(final Map<String, String> properties) {
      if (this.serverState != ServerState.INITIALIZING) {
        String msg = "Cannot set service properties after the server has been started.";
        throw new IllegalStateException(msg);
      } else {
        if (this.configurationException == null) {
          this.serviceProperties = new ConcurrentHashMap<>(properties);
        }
      }
    }

    public Map<String, String> getServiceProperties() {
      return this.serviceProperties;
    }

    public CountDownLatch start() throws Exception {
      try {
        return AccessController.doPrivileged(new PrivilegedExceptionAction<CountDownLatch>() {
          public CountDownLatch run() throws Exception {
            return doStart();
          }
        });
      } catch ( PrivilegedActionException var2) {
        throw var2.getException();
      }
    }

    private CountDownLatch doStart() throws Exception {
      if (this.serverState != ServerState.INITIALIZING) {
        throw new IllegalStateException("Cannot start a server that has already been started.");
      } else {
        this.reportDeferredConfigurationException();
        this.initializeLogging();
        this.modules.configure(this.containerConfigProperties);

        try {
          this.modules.createConnections();
        } catch (BindException be) {
          System.err.println();
          System.err.println("************************************************");
          System.err.println("Could not open the requested socket: " + be.getMessage( ));
          System.err.println("Try overriding --address and/or --port.");
          System.exit(2);
        }

        ApiProxyLocalFactory factory = new ApiProxyLocalFactory();
        Set<String> apisUsingPythonStubs = new HashSet<>();
        if (System.getProperty("appengine.apisUsingPythonStubs") != null) {
          for ( final String api : Splitter.on( ',' ).split( System.getProperty( "appengine.apisUsingPythonStubs" ) ) ) {
            apisUsingPythonStubs.add( api );
          }
        }

        String applicationName = this.applicationConfigurationManager.getModuleConfigurationHandles().get(0).getModule().getAppEngineWebXml().getAppId();
        this.apiProxyLocal = factory.create(this.modules.getLocalServerEnvironment(), apisUsingPythonStubs, applicationName);
        this.setInboundServicesProperty();
        this.apiProxyLocal.setProperties(this.serviceProperties);
        ApiProxy.setDelegate(this.apiProxyLocal);
        this.installLoggingServiceHandler((DevServices)this.apiProxyLocal);
        TimeZone currentTimeZone = null;

        try {
          currentTimeZone = this.setServerTimeZone();
          this.modules.setApiProxyDelegate(this.apiProxyLocal);
          this.modules.startup();
          Map<String, String> portMapping = Collections.emptyMap();
          AbstractContainerService.installLocalInitializationEnvironment(modules.getAppEngineWebXmlConfig(), -1, this.getPort(), this.getPort(), null, -1, portMapping);
        } finally {
          ApiProxy.clearEnvironmentForCurrentThread();
          this.restoreLocalTimeZone(currentTimeZone);
        }

        this.shutdownLatch = new CountDownLatch(1);
        this.serverState = ServerState.RUNNING;
        logger.log(Level.INFO, "AppScale App Server is now running");
        return this.shutdownLatch;
      }
    }

    private void installLoggingServiceHandler(DevServices proxy) {
      Logger root = Logger.getLogger("");
      DevLogService logService = proxy.getLogService();
      root.addHandler(logService.getLogHandler());
      Handler[] handlers = root.getHandlers();
      if (handlers != null) {
        for (Handler handler : handlers ) {
          handler.setLevel( Level.FINEST );
        }
      }
    }

    public void setInboundServicesProperty() {
      Builder<String> setBuilder = ImmutableSet.builder();
      for ( final ModuleConfigurationHandle moduleConfigurationHandle : this.applicationConfigurationManager.getModuleConfigurationHandles( ) ) {
        setBuilder.addAll( moduleConfigurationHandle.getModule( ).getAppEngineWebXml( ).getInboundServices( ) );
      }

      this.serviceProperties.put("appengine.dev.inbound-services", Joiner.on(",").join(setBuilder.build()));
    }

    private TimeZone setServerTimeZone() {
      String sysTimeZone = this.serviceProperties.get("appengine.user.timezone.impl");
      if (sysTimeZone != null && sysTimeZone.trim().length() > 0) {
        return null;
      } else {
        TimeZone utc = TimeZone.getTimeZone("UTC");

        assert utc.getID().equals("UTC") : "Unable to retrieve the UTC TimeZone";

        TimeZone previousZone = TimeZone.getDefault();
        TimeZone.setDefault(utc);
        return previousZone;
      }
    }

    private void restoreLocalTimeZone(TimeZone timeZone) {
      String sysTimeZone = this.serviceProperties.get("appengine.user.timezone.impl");
      if (sysTimeZone == null || sysTimeZone.trim().length() <= 0) {
        TimeZone.setDefault(timeZone);
      }
    }

    public CountDownLatch restart() throws Exception {
      if (this.serverState != ServerState.RUNNING) {
        throw new IllegalStateException("Cannot restart a server that is not currently running.");
      } else {
        try {
          return AccessController.doPrivileged( new PrivilegedExceptionAction<CountDownLatch>() {
            public CountDownLatch run() throws Exception {
              modules.shutdown();
              shutdownLatch.countDown();
              modules.createConnections();
              modules.setApiProxyDelegate(apiProxyLocal);
              modules.startup();
              shutdownLatch = new CountDownLatch(1);
              return shutdownLatch;
            }
          });
        } catch (PrivilegedActionException var2) {
          throw var2.getException();
        }
      }
    }

    public void shutdown() throws Exception {
      if (this.serverState != ServerState.RUNNING) {
        throw new IllegalStateException("Cannot shutdown a server that is not currently running.");
      } else {
        try {
          AccessController.doPrivileged(new PrivilegedExceptionAction<Void>() {
            public Void run() throws Exception {
              modules.shutdown();
              ApiProxy.setDelegate(null);
              apiProxyLocal = null;
              serverState = ServerState.SHUTDOWN;
              shutdownLatch.countDown();
              return null;
            }
          });
        } catch (PrivilegedActionException var2) {
          throw var2.getException();
        }
      }
    }

    public void gracefulShutdown() throws IllegalStateException {
      AccessController.doPrivileged(new PrivilegedAction<Future<Void>>() {
        public Future<Void> run() {
          return shutdownScheduler.schedule(new Callable<Void>() {
            public Void call() throws Exception {
              shutdown();
              return null;
            }
          }, 1000L, TimeUnit.MILLISECONDS);
        }
      });
    }

    public int getPort() {
      this.reportDeferredConfigurationException();
      return this.modules.getMainModulePort();
    }

    protected void reportDeferredConfigurationException() {
      if (this.configurationException != null) {
        throw new AppEngineConfigException("Invalid configuration", this.configurationException);
      }
    }

    public AppContext getAppContext() {
      this.reportDeferredConfigurationException();
      return this.modules.getAppContext();
    }

    public AppContext getCurrentAppContext() {
      AppContext result = null;
      Environment env = ApiProxy.getCurrentEnvironment();
      if (env != null && env.getVersionId() != null) {
        result = this.modules.getAppContext();
      }

      return result;
    }

    public void setThrowOnEnvironmentVariableMismatch(boolean throwOnMismatch) {
    }

    private void initializeLogging() {
      Handler[] var1 = Logger.getLogger("").getHandlers();
      for ( Handler handler : var1 ) {
        if ( handler instanceof ConsoleHandler ) {
          handler.setLevel( Level.FINEST );
        }
      }
    }

    enum ServerState {
      INITIALIZING,
      RUNNING,
      SHUTDOWN,
      ;
    }
}
