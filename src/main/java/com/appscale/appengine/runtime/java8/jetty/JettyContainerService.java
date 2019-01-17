/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8.jetty;

import com.appscale.appengine.runtime.java8.RuntimeEnvironment;
import com.google.appengine.api.log.dev.DevLogHandler;
import com.google.appengine.api.log.dev.LocalLogService;
import com.google.appengine.repackaged.com.google.common.base.Predicates;
import com.google.appengine.repackaged.com.google.common.collect.FluentIterable;
import com.google.appengine.repackaged.com.google.common.io.Files;
import com.google.appengine.tools.development.AbstractContainerService;
import com.google.appengine.tools.development.ApiProxyLocal;
import com.google.appengine.tools.development.AppContext;
import com.google.appengine.tools.development.DevAppServer;
import com.google.appengine.tools.development.DevAppServerModulesFilter;
import com.google.appengine.tools.development.IsolatedAppClassLoader;
import com.google.appengine.tools.development.jetty9.AppEngineAnnotationConfiguration;
import com.google.appengine.tools.development.jetty9.DevAppEngineWebAppContext;
import com.google.appengine.tools.info.AppengineSdk;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.runtime.jetty9.StubSessionManager;
import com.google.apphosting.utils.config.AppEngineConfigException;
import com.google.apphosting.utils.config.AppEngineWebXml;
import com.google.apphosting.utils.config.WebModule;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.security.Permissions;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.nio.NetworkTrafficSelectChannelConnector;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.FragmentConfiguration;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlConfiguration;

public class JettyContainerService extends AbstractContainerService {
  private static final Logger log = Logger.getLogger(JettyContainerService.class.getName());
  private static final String WEB_DEFAULTS_XML = "com/appscale/appengine/runtime/java8/jetty/webdefault.xml";
  private static final String JETTY_TAG_LIB_JAR_PREFIX = "org.apache.taglibs.taglibs-";
  private static final Pattern JSP_REGEX = Pattern.compile(".*\\.jspx?");
  private static final int MAX_SIMULTANEOUS_API_CALLS = 100;
  private static final Long SOFT_DEADLINE_DELAY_MS = 60000L;
  private static final String[] CONFIG_CLASSES = new String[]{
      WebInfConfiguration.class.getCanonicalName(),
      WebXmlConfiguration.class.getCanonicalName(),
      MetaInfConfiguration.class.getCanonicalName(),
      FragmentConfiguration.class.getCanonicalName(),
      AppEngineAnnotationConfiguration.class.getCanonicalName()
  };
  private static final String WEB_XML_ATTR = "com.google.appengine.tools.development.webXml";
  private static final String APPENGINE_WEB_XML_ATTR = "com.google.appengine.tools.development.appEngineWebXml";

  static {
    System.setProperty("org.eclipse.jetty.util.log.class", "com.google.appengine.development.jetty9.JettyLogger");
  }

  private WebAppContext context;
  private AppContext appContext;
  private Server server;

  protected File initContext() throws IOException {
    this.context = new DevAppEngineWebAppContext(
        this.appDir,
        this.externalResourceDir,
        this.devAppServerVersion,
        this.apiProxyDelegate,
        this.devAppServer
    );
    this.appContext = new JettyContainerService.JettyAppContext();
    this.context.setDescriptor(this.webXmlLocation == null ? null : this.webXmlLocation.getAbsolutePath());
    String webDefaultXml = this.devAppServer.getServiceProperties().get("appengine.webdefault.xml");
    if (webDefaultXml == null) {
      webDefaultXml = WEB_DEFAULTS_XML;
    }

    this.context.setDefaultsDescriptor(webDefaultXml);
    this.context.setConfigurationClasses(CONFIG_CLASSES);
    File appRoot = this.determineAppRoot();
    this.installLocalInitializationEnvironment();
    if (this.applicationContainsJSP(this.appDir )) {

      for ( final File file : AppengineSdk.getSdk( ).getUserJspLibFiles( ) ) {
        if ( file.getName( ).startsWith( JETTY_TAG_LIB_JAR_PREFIX ) ) {
          String var6 = String.valueOf( this.appDir );
          String var7 = file.getName( );
          File jettyProvidedDestination = new File( var6 + "/WEB-INF/lib/" + var7 );
          if ( !jettyProvidedDestination.exists( ) ) {
            var7 = String.valueOf( this.appDir );
            String var8 = file.getName( ).substring( JETTY_TAG_LIB_JAR_PREFIX.length( ) );
            File mavenProvidedDestination = new File( var7 + "/WEB-INF/lib/" + var8 );
            if ( !mavenProvidedDestination.exists( ) ) {
              Level var10001 = Level.WARNING;
              var7 = file.getName( );
              log.logp( var10001, "com.google.appengine.tools.development.jetty9.JettyContainerService", "initContext", "Adding jar " + var7 + " to WEB-INF/lib. You might want to add a dependency in your project build system to avoid this warning." );

              try {
                Files.copy( file, jettyProvidedDestination );
              } catch ( IOException var9 ) {
                log.logp( Level.WARNING, "com.google.appengine.tools.development.jetty9.JettyContainerService", "initContext", "Cannot copy org.apache.taglibs.taglibs jar file to WEB-INF/lib.", var9 );
              }
            }
          }
        }
      }
    }

    URL[] classPath = this.getClassPathForApp(appRoot);
    this.context.setClassLoader(new IsolatedAppClassLoader(appRoot, this.externalResourceDir, classPath, JettyContainerService.class.getClassLoader()));
    if (Boolean.parseBoolean(System.getProperty("appengine.allowRemoteShutdown"))) {
      this.context.addServlet(new ServletHolder(new JettyContainerService.ServerShutdownServlet()), "/_ah/admin/quit");
    }

    return appRoot;
  }

  private boolean applicationContainsJSP( File dir ) {
    return FluentIterable.from(Files.fileTraverser().depthFirstPreOrder(dir))
        .filter(Predicates.not(Files.isDirectory()))
        .firstMatch(file -> file != null && JettyContainerService.JSP_REGEX.matcher(file.getName()).matches())
        .isPresent();
  }

  @SuppressWarnings( "deprecation" )
  protected void connectContainer() throws Exception {
    this.moduleConfigurationHandle.checkEnvironmentVariables();
    Thread currentThread = Thread.currentThread();
    ClassLoader previousCcl = currentThread.getContextClassLoader();
    this.server = new Server();

    try {
      final ForwardedRequestCustomizer forwardedRequestCustomizer = new ForwardedRequestCustomizer();
      forwardedRequestCustomizer.setForwardedOnly(true);
      forwardedRequestCustomizer.setForwardedHeader(null);
      forwardedRequestCustomizer.setForwardedForHeader(HttpHeader.X_FORWARDED_FOR.toString());
      forwardedRequestCustomizer.setForwardedProtoHeader(HttpHeader.X_FORWARDED_PROTO.toString());
      final HttpConfiguration httpConfiguration = new HttpConfiguration();
      httpConfiguration.addCustomizer(forwardedRequestCustomizer);
      final HttpConnectionFactory connectionFactory = new HttpConnectionFactory(httpConfiguration);
      NetworkTrafficSelectChannelConnector connector = new NetworkTrafficSelectChannelConnector(this.server, null, null, null, 0, Runtime.getRuntime().availableProcessors(), connectionFactory );
      connector.setHost(this.address);
      connector.setPort(this.port);
      connector.setSoLingerTime(0);
      connector.open();
      this.server.addConnector(connector);
      this.port = connector.getLocalPort();
    } finally {
      currentThread.setContextClassLoader(previousCcl);
    }
  }

  protected void startContainer() throws Exception {
    this.context.setAttribute(WEB_XML_ATTR, this.webXml);
    this.context.setAttribute(APPENGINE_WEB_XML_ATTR, this.appEngineWebXml);
    Thread currentThread = Thread.currentThread();
    ClassLoader previousCcl = currentThread.getContextClassLoader();
    currentThread.setContextClassLoader( null );

    try {
      JettyContainerService.ApiProxyHandler apiHandler = new JettyContainerService.ApiProxyHandler(this.appEngineWebXml);
      apiHandler.setHandler(this.context);
      this.server.setHandler(apiHandler);
      SessionHandler handler = this.context.getSessionHandler();
      handler.setSessionManager(new StubSessionManager());
      if (this.isSessionsEnabled()) {
        log.severe( "Sessions are enabled for application but not supported." );
      }

      this.server.start();
    } finally {
      currentThread.setContextClassLoader(previousCcl);
    }
  }

  protected void stopContainer() throws Exception {
    this.server.stop();
  }

  protected void startHotDeployScanner() {
  }

  protected void stopHotDeployScanner() {
  }

  protected void reloadWebApp() {
  }

  public AppContext getAppContext() {
    return this.appContext;
  }

  public void forwardToServer(HttpServletRequest hrequest, HttpServletResponse hresponse) throws IOException, ServletException {
    Level var10001 = Level.FINEST;
    String var3 = this.appEngineWebXml.getModule();
    int var4 = this.instance;
    log.logp(var10001, "com.google.appengine.tools.development.jetty9.JettyContainerService", "forwardToServer", "forwarding request to module: " + var3 + "." + var4 );
    RequestDispatcher requestDispatcher = this.context.getServletContext().getRequestDispatcher(hrequest.getRequestURI());
    requestDispatcher.forward(hrequest, hresponse);
  }

  private File determineAppRoot() throws IOException {
    Resource webInf = this.context.getWebInf();
    if (webInf == null) {
      if (this.userCodeClasspathManager.requiresWebInf()) {
        throw new AppEngineConfigException("Supplied application has to contain WEB-INF directory.");
      } else {
        return this.appDir;
      }
    } else {
      return webInf.getFile().getParentFile();
    }
  }

  private static class RecordingResponseWrapper extends HttpServletResponseWrapper {
    private int status = 200;

    RecordingResponseWrapper(HttpServletResponse response) {
      super(response);
    }

    public void setStatus(int sc) {
      this.status = sc;
      super.setStatus(sc);
    }

    public int getStatus() {
      return this.status;
    }

    public void sendError(int sc) throws IOException {
      this.status = sc;
      super.sendError(sc);
    }

    public void sendError(int sc, String msg) throws IOException {
      this.status = sc;
      super.sendError(sc, msg);
    }

    public void sendRedirect(String location) throws IOException {
      this.status = 302;
      super.sendRedirect(location);
    }

    /**
     * @deprecated
     */
    @SuppressWarnings( "deprecation" )
    @Deprecated
    @Override
    public void setStatus( int status, String string) {
      super.setStatus(status, string);
      this.status = status;
    }

    public void reset() {
      super.reset();
      this.status = 200;
    }
  }

  private class ApiProxyHandler extends HandlerWrapper {
    private final AppEngineWebXml appEngineWebXml;

    public ApiProxyHandler(AppEngineWebXml appEngineWebXml) {
      this.appEngineWebXml = appEngineWebXml;
    }

    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
      if (baseRequest.getDispatcherType() == DispatcherType.REQUEST) {
        long startTimeUsec = System.currentTimeMillis() * 1000L;
        Semaphore semaphore = new Semaphore(MAX_SIMULTANEOUS_API_CALLS);
        RuntimeEnvironment env = new RuntimeEnvironment(this.appEngineWebXml.getAppId(), WebModule.getModuleName(this.appEngineWebXml), this.appEngineWebXml.getMajorVersionId(), JettyContainerService.this.instance, JettyContainerService.this.getPort(), request, JettyContainerService.SOFT_DEADLINE_DELAY_MS, JettyContainerService.this.modulesFilterHelper);
        env.getAttributes().put("com.google.appengine.tools.development.api_call_semaphore", semaphore);
        Map<String, Object> envAttributes = env.getAttributes();
        int var9 = JettyContainerService.this.devAppServer.getPort();
        envAttributes.put("com.google.appengine.runtime.default_version_hostname", "localhost:" + var9 );
        env.getAttributes().put("com.google.appengine.api.files.filesapi_was_used", false);
        ApiProxy.setEnvironmentForCurrentThread(env);
        DevAppServerModulesFilter.injectBackendServiceCurrentApiInfo(JettyContainerService.this.backendName, JettyContainerService.this.backendInstance, JettyContainerService.this.portMappingProvider.getPortMapping());
        JettyContainerService.RecordingResponseWrapper wrappedResponse = new JettyContainerService.RecordingResponseWrapper(response);
        boolean var45 = false;

        try {
          var45 = true;
          super.handle(target, baseRequest, request, wrappedResponse);
          var45 = false;
        } finally {
          if (var45) {
            try {
              semaphore.acquire(MAX_SIMULTANEOUS_API_CALLS);
            } catch (InterruptedException var47) {
              Thread.currentThread().interrupt();
              JettyContainerService.log.logp(Level.WARNING, "com.google.appengine.tools.development.jetty9.JettyContainerService$ApiProxyHandler", "handle", "Interrupted while waiting for API calls to complete:", var47);
            }

            env.callRequestEndListeners();
            if (JettyContainerService.this.apiProxyDelegate instanceof ApiProxyLocal) {
              ApiProxyLocal apiProxyLocalx = (ApiProxyLocal)JettyContainerService.this.apiProxyDelegate;

              try {
                String appIdx = env.getAppId();
                String versionIdx = env.getVersionId();
                String requestId = DevLogHandler.getRequestId();
                long endTimeUsec = (new Date()).getTime() * 1000L;
                LocalLogService logService = (LocalLogService)apiProxyLocalx.getService("logservice");

                logService.addRequestInfo(appIdx, versionIdx, requestId, request.getRemoteAddr(), request.getRemoteUser(), startTimeUsec, endTimeUsec, request.getMethod(), request.getRequestURI(), request.getProtocol(), request.getHeader("User-Agent"), true, wrappedResponse.getStatus(), request.getHeader("Referrer"));
                logService.clearResponseSize();
              } finally {
                ApiProxy.clearEnvironmentForCurrentThread();
              }
            }

          }
        }

        try {
          semaphore.acquire(MAX_SIMULTANEOUS_API_CALLS);
        } catch (InterruptedException var49) {
          Thread.currentThread().interrupt();
          JettyContainerService.log.logp(Level.WARNING, "com.google.appengine.tools.development.jetty9.JettyContainerService$ApiProxyHandler", "handle", "Interrupted while waiting for API calls to complete:", var49);
        }

        env.callRequestEndListeners();
        if (JettyContainerService.this.apiProxyDelegate instanceof ApiProxyLocal) {
          ApiProxyLocal apiProxyLocal = (ApiProxyLocal)JettyContainerService.this.apiProxyDelegate;

          try {
            String appId = env.getAppId();
            String versionId = env.getVersionId();
            String requestIdx = DevLogHandler.getRequestId();
            long endTimeUsecx = (new Date()).getTime() * 1000L;
            LocalLogService logServicex = (LocalLogService)apiProxyLocal.getService("logservice");

            logServicex.addRequestInfo(appId, versionId, requestIdx, request.getRemoteAddr(), request.getRemoteUser(), startTimeUsec, endTimeUsecx, request.getMethod(), request.getRequestURI(), request.getProtocol(), request.getHeader("User-Agent"), true, wrappedResponse.getStatus(), request.getHeader("Referrer"));
            logServicex.clearResponseSize();
          } finally {
            ApiProxy.clearEnvironmentForCurrentThread();
          }
        }
      } else {
        super.handle(target, baseRequest, request, response);
      }

    }
  }

  static class ServerShutdownServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
      resp.getWriter().println("Shutting down local server.");
      resp.flushBuffer();
      DevAppServer server = (DevAppServer)this.getServletContext().getAttribute("com.google.appengine.devappserver.Server");
      server.gracefulShutdown();
    }
  }

  private class JettyAppContext implements AppContext {
    public IsolatedAppClassLoader getClassLoader() {
      return (IsolatedAppClassLoader)JettyContainerService.this.context.getClassLoader();
    }

    public Permissions getUserPermissions() {
      return JettyContainerService.this.getUserPermissions();
    }

    public Permissions getApplicationPermissions() {
      return this.getClassLoader().getAppPermissions();
    }

    public Object getContainerContext() {
      return JettyContainerService.this.context;
    }
  }
}
