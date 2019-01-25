package com.google.appengine.tools.development.jetty9;

import com.appscale.appengine.runtime.java8.RuntimeEnvironment;
import com.google.appengine.api.log.dev.DevLogHandler;
import com.google.appengine.api.log.dev.LocalLogService;
import com.google.appengine.repackaged.com.google.common.base.Predicates;
import com.google.appengine.repackaged.com.google.common.collect.FluentIterable;
import com.google.appengine.repackaged.com.google.common.collect.ImmutableList;
import com.google.appengine.repackaged.com.google.common.io.Files;
import com.google.appengine.tools.development.AbstractContainerService;
import com.google.appengine.tools.development.ApiProxyLocal;
import com.google.appengine.tools.development.AppContext;
import com.google.appengine.tools.development.DevAppServer;
import com.google.appengine.tools.development.DevAppServerModulesFilter;
import com.google.appengine.tools.development.IsolatedAppClassLoader;
import com.google.appengine.tools.development.LocalEnvironment;
import com.google.appengine.tools.development.LocalHttpRequestEnvironment;
import com.google.appengine.tools.info.AppengineSdk;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.runtime.jetty9.StubSessionManager;
import com.google.apphosting.utils.config.AppEngineConfigException;
import com.google.apphosting.utils.config.AppEngineWebXml;
import com.google.apphosting.utils.config.WebModule;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.URL;
import java.security.Permissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
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
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.server.nio.NetworkTrafficSelectChannelConnector;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.Scanner.BulkListener;
import org.eclipse.jetty.util.Scanner.DiscreteListener;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.webapp.FragmentConfiguration;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlConfiguration;

public class JettyContainerService extends AbstractContainerService {
  private static final Logger log = Logger.getLogger(JettyContainerService.class.getName());
  private static final String JETTY_TAG_LIB_JAR_PREFIX = "org.apache.taglibs.taglibs-";
  private static final Pattern JSP_REGEX = Pattern.compile(".*\\.jspx?");
  private static final String FILES_API_DEPRECATION_WARNING = "The Files API is deprecated and will soon be removed. Further information is available  here: https://cloud.google.com/appengine/docs/deprecations/files_api";
  public static final String WEB_DEFAULTS_XML = "com/google/appengine/tools/development/jetty9/webdefault.xml";
  private static final int MAX_SIMULTANEOUS_API_CALLS = 100;
  private static final Long SOFT_DEADLINE_DELAY_MS = 60000L;
  private static final String[] CONFIG_CLASSES = new String[]{WebInfConfiguration.class.getCanonicalName(), WebXmlConfiguration.class.getCanonicalName(), MetaInfConfiguration.class.getCanonicalName(), FragmentConfiguration.class.getCanonicalName(), AppEngineAnnotationConfiguration.class.getCanonicalName()};
  private static final String WEB_XML_ATTR = "com.google.appengine.tools.development.webXml";
  private static final String APPENGINE_WEB_XML_ATTR = "com.google.appengine.tools.development.appEngineWebXml";
  private boolean disableFilesApiWarning = false;
  private static final int SCAN_INTERVAL_SECONDS = 5;
  private WebAppContext context;
  private AppContext appContext;
  private Server server;
  private Scanner scanner;

  public JettyContainerService() {
  }

  protected File initContext() throws IOException {
    this.context = new DevAppEngineWebAppContext(this.appDir, this.externalResourceDir, this.devAppServerVersion, this.apiProxyDelegate, this.devAppServer);
    this.appContext = new JettyContainerService.JettyAppContext();
    this.context.setDescriptor(this.webXmlLocation == null ? null : this.webXmlLocation.getAbsolutePath());
    String webDefaultXml = (String)this.devAppServer.getServiceProperties().get("appengine.webdefault.xml");
    if (webDefaultXml == null) {
      webDefaultXml = "com/google/appengine/tools/development/jetty9/webdefault.xml";
    }

    this.context.setDefaultsDescriptor(webDefaultXml);
    this.context.setConfigurationClasses(CONFIG_CLASSES);
    File appRoot = this.determineAppRoot();
    this.installLocalInitializationEnvironment();
    if (this.applicationContainsJSP(this.appDir, JSP_REGEX)) {
      Iterator var3 = AppengineSdk.getSdk().getUserJspLibFiles().iterator();

      while(var3.hasNext()) {
        File file = (File)var3.next();
        if (file.getName().startsWith("org.apache.taglibs.taglibs-")) {
          String var6 = String.valueOf(this.appDir);
          String var7 = file.getName();
          File jettyProvidedDestination = new File((new StringBuilder(13 + String.valueOf(var6).length() + String.valueOf(var7).length())).append(var6).append("/WEB-INF/lib/").append(var7).toString());
          if (!jettyProvidedDestination.exists()) {
            var7 = String.valueOf(this.appDir);
            String var8 = file.getName().substring("org.apache.taglibs.taglibs-".length());
            File mavenProvidedDestination = new File((new StringBuilder(13 + String.valueOf(var7).length() + String.valueOf(var8).length())).append(var7).append("/WEB-INF/lib/").append(var8).toString());
            if (!mavenProvidedDestination.exists()) {
              Logger var10000 = log;
              Level var10001 = Level.WARNING;
              var7 = file.getName();
              var10000.logp(var10001, "com.google.appengine.tools.development.jetty9.JettyContainerService", "initContext", (new StringBuilder(114 + String.valueOf(var7).length())).append("Adding jar ").append(var7).append(" to WEB-INF/lib. You might want to add a dependency in your project build system to avoid this warning.").toString());

              try {
                Files.copy(file, jettyProvidedDestination);
              } catch (IOException var9) {
                log.logp(Level.WARNING, "com.google.appengine.tools.development.jetty9.JettyContainerService", "initContext", "Cannot copy org.apache.taglibs.taglibs jar file to WEB-INF/lib.", var9);
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

    if (Boolean.parseBoolean(System.getProperty("appengine.disableFilesApiWarning"))) {
      this.disableFilesApiWarning = true;
    }

    return appRoot;
  }

  private boolean applicationContainsJSP(File dir, Pattern jspPattern) {
    Iterator var3 = FluentIterable.from(Files.fileTraverser().depthFirstPreOrder(dir)).filter(Predicates.not(Files.isDirectory())).iterator();

    File file;
    do {
      if (!var3.hasNext()) {
        return false;
      }

      file = (File)var3.next();
    } while(!jspPattern.matcher(file.getName()).matches());

    return true;
  }

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
      NetworkTrafficSelectChannelConnector connector = new NetworkTrafficSelectChannelConnector(this.server, (Executor)null, (Scheduler)null, (ByteBufferPool)null, 0, Runtime.getRuntime().availableProcessors(), new ConnectionFactory[]{connectionFactory});
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
    this.context.setAttribute("com.google.appengine.tools.development.webXml", this.webXml);
    this.context.setAttribute("com.google.appengine.tools.development.appEngineWebXml", this.appEngineWebXml);
    Thread currentThread = Thread.currentThread();
    ClassLoader previousCcl = currentThread.getContextClassLoader();
    currentThread.setContextClassLoader((ClassLoader)null);

    try {
      JettyContainerService.ApiProxyHandler apiHandler = new JettyContainerService.ApiProxyHandler(this.appEngineWebXml);
      apiHandler.setHandler(this.context);
      this.server.setHandler(apiHandler);
      SessionHandler handler = this.context.getSessionHandler();
      if (this.isSessionsEnabled()) {
        handler.setSessionManager(new SerializableObjectsOnlyHashSessionManager());
      } else {
        handler.setSessionManager(new StubSessionManager());
      }

      this.server.start();
    } finally {
      currentThread.setContextClassLoader(previousCcl);
    }

  }

  protected void stopContainer() throws Exception {
    this.server.stop();
  }

  protected void startHotDeployScanner() throws Exception {
    String fullScanInterval = System.getProperty("appengine.fullscan.seconds");
    if (fullScanInterval != null) {
      try {
        int interval = Integer.parseInt(fullScanInterval);
        if (interval < 1) {
          log.logp(Level.INFO, "com.google.appengine.tools.development.jetty9.JettyContainerService", "startHotDeployScanner", "Full scan of the web app for changes is disabled.");
          return;
        }

        log.logp(Level.INFO, "com.google.appengine.tools.development.jetty9.JettyContainerService", "startHotDeployScanner", (new StringBuilder(53)).append("Full scan of the web app in place every ").append(interval).append("s.").toString());
        this.fullWebAppScanner(interval);
        return;
      } catch (NumberFormatException var3) {
        log.logp(Level.WARNING, "com.google.appengine.tools.development.jetty9.JettyContainerService", "startHotDeployScanner", "appengine.fullscan.seconds property is not an integer:", var3);
        log.logp(Level.WARNING, "com.google.appengine.tools.development.jetty9.JettyContainerService", "startHotDeployScanner", "Using the default scanning method.");
      }
    }

    this.scanner = new Scanner();
    this.scanner.setReportExistingFilesOnStartup(false);
    this.scanner.setScanInterval(5);
    this.scanner.setScanDirs(ImmutableList.of(this.getScanTarget()));
    this.scanner.setFilenameFilter(new FilenameFilter() {
      public boolean accept(File dir, String name) {
        try {
          return name.equals(JettyContainerService.this.getScanTarget().getName());
        } catch (Exception var4) {
          return false;
        }
      }
    });
    this.scanner.addListener(new JettyContainerService.ScannerListener());
    this.scanner.doStart();
  }

  protected void stopHotDeployScanner() throws Exception {
    if (this.scanner != null) {
      this.scanner.stop();
    }

    this.scanner = null;
  }

  private File getScanTarget() throws Exception {
    if (!this.appDir.isFile() && this.context.getWebInf() != null) {
      String var1 = this.context.getWebInf().getFile().getPath();
      String var2 = File.separator;
      return new File((new StringBuilder(17 + String.valueOf(var1).length() + String.valueOf(var2).length())).append(var1).append(var2).append("appengine-web.xml").toString());
    } else {
      return this.appDir;
    }
  }

  private void fullWebAppScanner(int interval) throws IOException {
    String webInf = this.context.getWebInf().getFile().getPath();
    List<File> scanList = new ArrayList();
    Collections.addAll(scanList, new File[]{new File(webInf, "classes"), new File(webInf, "lib"), new File(webInf, "web.xml"), new File(webInf, "appengine-web.xml")});
    this.scanner = new Scanner();
    this.scanner.setScanInterval(interval);
    this.scanner.setScanDirs(scanList);
    this.scanner.setReportExistingFilesOnStartup(false);
    this.scanner.setRecursive(true);
    this.scanner.addListener(new BulkListener() {
      public void filesChanged(List<String> changedFiles) throws Exception {
        JettyContainerService.log.logp(Level.INFO, "com.google.appengine.tools.development.jetty9.JettyContainerService$2", "filesChanged", "A file has changed, reloading the web application.");
        JettyContainerService.this.reloadWebApp();
      }
    });
    this.scanner.doStart();
  }

  protected void reloadWebApp() throws Exception {
    this.server.getHandler().stop();
    this.server.stop();
    this.moduleConfigurationHandle.restoreSystemProperties();
    this.moduleConfigurationHandle.readConfiguration();
    this.moduleConfigurationHandle.checkEnvironmentVariables();
    this.extractFieldsFromWebModule(this.moduleConfigurationHandle.getModule());
    Thread currentThread = Thread.currentThread();
    ClassLoader previousCcl = currentThread.getContextClassLoader();
    currentThread.setContextClassLoader((ClassLoader)null);

    try {
      this.initContext();
      this.installLocalInitializationEnvironment();
      if (!this.isSessionsEnabled()) {
        this.context.getSessionHandler().setSessionManager(new StubSessionManager());
      }

      this.context.setAttribute("com.google.appengine.tools.development.webXml", this.webXml);
      this.context.setAttribute("com.google.appengine.tools.development.appEngineWebXml", this.appEngineWebXml);
      JettyContainerService.ApiProxyHandler apiHandler = new JettyContainerService.ApiProxyHandler(this.appEngineWebXml);
      apiHandler.setHandler(this.context);
      this.server.setHandler(apiHandler);
      this.server.start();
    } finally {
      currentThread.setContextClassLoader(previousCcl);
    }

  }

  public AppContext getAppContext() {
    return this.appContext;
  }

  public void forwardToServer(HttpServletRequest hrequest, HttpServletResponse hresponse) throws IOException, ServletException {
    Logger var10000 = log;
    Level var10001 = Level.FINEST;
    String var3 = this.appEngineWebXml.getModule();
    int var4 = this.instance;
    var10000.logp(var10001, "com.google.appengine.tools.development.jetty9.JettyContainerService", "forwardToServer", (new StringBuilder(42 + String.valueOf(var3).length())).append("forwarding request to module: ").append(var3).append(".").append(var4).toString());
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

  static {
    System.setProperty("org.eclipse.jetty.util.log.class", " com.google.appengine.development.jetty9.JettyLogger");
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

    public void setStatus(int status, String string) {
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
        Semaphore semaphore = new Semaphore(100);
        RuntimeEnvironment env = new RuntimeEnvironment(this.appEngineWebXml.getAppId(), WebModule.getModuleName(this.appEngineWebXml), this.appEngineWebXml.getMajorVersionId(), JettyContainerService.this.instance, JettyContainerService.this.getPort(), request, JettyContainerService.SOFT_DEADLINE_DELAY_MS, JettyContainerService.this.modulesFilterHelper);
        env.getAttributes().put("com.google.appengine.tools.development.api_call_semaphore", semaphore);
        Map var10000 = env.getAttributes();
        int var9 = JettyContainerService.this.devAppServer.getPort();
        var10000.put("com.google.appengine.runtime.default_version_hostname", (new StringBuilder(21)).append("localhost:").append(var9).toString());
        env.getAttributes().put("com.google.appengine.api.files.filesapi_was_used", false);
        ApiProxy.setEnvironmentForCurrentThread(env);
        DevAppServerModulesFilter.injectBackendServiceCurrentApiInfo(JettyContainerService.this.backendName, JettyContainerService.this.backendInstance, JettyContainerService.this.portMappingProvider.getPortMapping());
        JettyContainerService.RecordingResponseWrapper wrappedResponse = new JettyContainerService.RecordingResponseWrapper(response);
        boolean var45 = false;

        try {
          var45 = true;
          super.handle(target, baseRequest, request, wrappedResponse);
          if (request.getRequestURI().startsWith("/_ah/reloadwebapp")) {
            try {
              JettyContainerService.this.reloadWebApp();
              Logger var53 = JettyContainerService.log;
              Level var10001 = Level.INFO;
              String var10005 = String.valueOf(request.getParameter("info"));
              String var10004;
              if (var10005.length() != 0) {
                var10004 = "Reloaded the webapp context: ".concat(var10005);
              } else {
                var10004 = "Reloaded the webapp context: ";
              }

              var53.logp(var10001, "com.google.appengine.tools.development.jetty9.JettyContainerService$ApiProxyHandler", "handle", var10004);
              var45 = false;
            } catch (Exception var50) {
              JettyContainerService.log.logp(Level.WARNING, "com.google.appengine.tools.development.jetty9.JettyContainerService$ApiProxyHandler", "handle", "Failed to reload the current webapp context.", var50);
              var45 = false;
            }
          } else {
            var45 = false;
          }
        } finally {
          if (var45) {
            try {
              semaphore.acquire(100);
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
                if (!JettyContainerService.this.disableFilesApiWarning && (Boolean)env.getAttributes().get("com.google.appengine.api.files.filesapi_was_used")) {
                  JettyContainerService.log.logp(Level.WARNING, "com.google.appengine.tools.development.jetty9.JettyContainerService$ApiProxyHandler", "handle", "The Files API is deprecated and will soon be removed. Further information is available  here: https://cloud.google.com/appengine/docs/deprecations/files_api");
                }

                logService.addRequestInfo(appIdx, versionIdx, requestId, request.getRemoteAddr(), request.getRemoteUser(), startTimeUsec, endTimeUsec, request.getMethod(), request.getRequestURI(), request.getProtocol(), request.getHeader("User-Agent"), true, wrappedResponse.getStatus(), request.getHeader("Referrer"));
                logService.clearResponseSize();
              } finally {
                ApiProxy.clearEnvironmentForCurrentThread();
              }
            }

          }
        }

        try {
          semaphore.acquire(100);
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
            if (!JettyContainerService.this.disableFilesApiWarning && (Boolean)env.getAttributes().get("com.google.appengine.api.files.filesapi_was_used")) {
              JettyContainerService.log.logp(Level.WARNING, "com.google.appengine.tools.development.jetty9.JettyContainerService$ApiProxyHandler", "handle", "The Files API is deprecated and will soon be removed. Further information is available  here: https://cloud.google.com/appengine/docs/deprecations/files_api");
            }

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

  private class ScannerListener implements DiscreteListener {
    private ScannerListener() {
    }

    public void fileAdded(String filename) throws Exception {
      this.fileChanged(filename);
    }

    public void fileChanged(String filename) throws Exception {
      JettyContainerService.log.logp(Level.INFO, "com.google.appengine.tools.development.jetty9.JettyContainerService$ScannerListener", "fileChanged", String.valueOf(filename).concat(" updated, reloading the webapp!"));
      JettyContainerService.this.reloadWebApp();
    }

    public void fileRemoved(String filename) throws Exception {
    }
  }

  static class ServerShutdownServlet extends HttpServlet {
    ServerShutdownServlet() {
    }

    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
      resp.getWriter().println("Shutting down local server.");
      resp.flushBuffer();
      DevAppServer server = (DevAppServer)this.getServletContext().getAttribute("com.google.appengine.devappserver.Server");
      server.gracefulShutdown();
    }
  }

  private class JettyAppContext implements AppContext {
    private JettyAppContext() {
    }

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
