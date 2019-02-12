/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8.jetty;

import com.appscale.appengine.runtime.java8.util.LoginCookies;
import com.appscale.appengine.runtime.java8.util.LoginCookies.LoginCookie;
import com.appscale.appengine.runtime.java8.util.RuntimeAppClassLoader;
import com.appscale.appengine.runtime.java8.util.RuntimeEnvironment;
import com.appscale.appengine.runtime.java8.util.RuntimeEnvironmentListener;
import com.google.appengine.repackaged.com.google.common.base.Strings;
import com.google.appengine.tools.development.AbstractContainerService;
import com.google.appengine.tools.development.AppContext;
import com.google.appengine.tools.development.jetty9.AppEngineAnnotationConfiguration;
import com.google.appengine.tools.info.AppengineSdk;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.runtime.jetty9.StubSessionManager;
import com.google.apphosting.utils.config.AppEngineConfigException;
import com.google.apphosting.utils.config.AppEngineWebXml;
import com.google.apphosting.utils.config.ClassPathBuilder;
import com.google.apphosting.utils.config.WebModule;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.AllPermission;
import java.security.Permissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
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
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.FragmentConfiguration;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlConfiguration;

public class JettyContainerService extends AbstractContainerService {
  private static final Logger logger = Logger.getLogger(JettyContainerService.class.getName());
  private static final String WEB_DEFAULTS_XML = "com/appscale/appengine/runtime/java8/jetty/webdefault.xml";
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
    this.context = new JettyAppEngineWebAppContext(
        this.appDir,
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

    URL[] classPath = getClassPathForApp(appRoot);
    this.context.setClassLoader(new RuntimeAppClassLoader(classPath, JettyContainerService.class.getClassLoader()));

    return appRoot;
  }

  protected URL[] getClassPathForApp(final File root) {
    final ClassPathBuilder classPathBuilder = new ClassPathBuilder(this.appEngineWebXml.getClassLoaderConfig());
    classPathBuilder.addUrls(getUserCodeClasspath(root));
    // classPathBuilder.addUrls(SdkInfo.getUserLibs()); TODO check if gae java8 runtime includes any sdk jars
    classPathBuilder.addUrls(AppengineSdk.getSdk().getUserJspLibs());

    final URL[] urls = classPathBuilder.getUrls();
    String message = classPathBuilder.getLogMessage();
    if (!Strings.isNullOrEmpty(message)) {
      logger.warning(message);
    }
    return urls;
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
        logger.severe( "Sessions are enabled for application but not supported." );
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

  public void forwardToServer(
      final HttpServletRequest hrequest,
      final HttpServletResponse hresponse
  ) throws IOException, ServletException {
    final String module = this.appEngineWebXml.getModule();
    final int instance = this.instance;
    logger.log(Level.FINEST, "Forwarding request to module: " + module + "." + instance);
    final RequestDispatcher requestDispatcher
        = this.context.getServletContext().getRequestDispatcher(hrequest.getRequestURI());
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

  private List<URL> getUserCodeClasspath(final File root) {
    final List<URL> appUrls = new ArrayList<>();
    try {
      final File classesDir = new File(new File(root, "WEB-INF"), "classes");
      if (classesDir.exists()) {
        appUrls.add(classesDir.toURI().toURL());
      }
    } catch (final MalformedURLException me) {
      logger.log(Level.SEVERE, "Error adding WEB-INF/classes to classpath for web application", me);
    }

    final File libDir = new File(new File(root, "WEB-INF"), "lib");
    if (libDir.isDirectory()) {
      final File[] libFiles = libDir.listFiles();
      if (libFiles != null) for(final File file : libFiles) {
        try {
          appUrls.add(file.toURI().toURL());
        } catch (MalformedURLException me) {
          logger.log(Level.SEVERE,
              "Error adding WEB-INF/lib entry to classpath for web application " + file.getAbsolutePath(), me);
        }
      }
    }
    return appUrls;
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
        final Semaphore semaphore = new Semaphore(MAX_SIMULTANEOUS_API_CALLS);

        final String email;
        final String userId;
        final boolean loggedIn;
        final boolean admin;
        final Optional<LoginCookie> loginCookie = LoginCookies.fromRequest(request);
        if (loginCookie.isPresent()) {
          loggedIn = true;
          email = loginCookie.get().getEmail();
          userId = loginCookie.get().getUserId();
          admin = loginCookie.get().isAdmin();
        } else {
          loggedIn = false;
          email = null;
          userId = null;
          admin = false;
        }

        final RuntimeEnvironment env = new RuntimeEnvironment(
            this.appEngineWebXml.getAppId(),
            WebModule.getModuleName(this.appEngineWebXml),
            this.appEngineWebXml.getMajorVersionId(),
            email,
            userId,
            loggedIn,
            admin,
            JettyContainerService.this.instance,
            JettyContainerService.this.getPort(),
            request,
            JettyContainerService.SOFT_DEADLINE_DELAY_MS);
        env.getAttributes().put("com.google.appengine.tools.development.api_call_semaphore", semaphore);
        final Map<String, Object> envAttributes = env.getAttributes();
        final int port = JettyContainerService.this.devAppServer.getPort();
        envAttributes.put("com.google.appengine.runtime.default_version_hostname", "localhost:" + port);
        env.getAttributes().put("com.google.appengine.api.files.filesapi_was_used", false);
        ApiProxy.setEnvironmentForCurrentThread(env);
        final JettyContainerService.RecordingResponseWrapper wrappedResponse =
            new JettyContainerService.RecordingResponseWrapper(response);

        try {
          super.handle(target, baseRequest, request, wrappedResponse);
        } finally {
          try {
            semaphore.acquire(MAX_SIMULTANEOUS_API_CALLS);
          } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "Interrupted while waiting for API calls to complete:", e);
          }
          RuntimeEnvironmentListener.requestEnd(env);
        }
      } else {
        super.handle(target, baseRequest, request, response);
      }
    }
  }

  private class JettyAppContext implements AppContext {
    public ClassLoader getClassLoader() {
      return JettyContainerService.this.context.getClassLoader();
    }

    public Permissions getUserPermissions() {
      return JettyContainerService.this.getUserPermissions();
    }

    public Permissions getApplicationPermissions() {
      final Permissions permissions = new Permissions();
      permissions.add(new AllPermission());
      return permissions;
    }

    public Object getContainerContext() {
      return JettyContainerService.this.context;
    }
  }
}
