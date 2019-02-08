package com.appscale.appengine.runtime.java8.jetty;

import java.io.File;
import com.google.appengine.tools.development.DevAppServer;
import com.google.appengine.tools.development.jetty9.AppEngineWebAppContext;
import com.google.apphosting.api.ApiProxy.Delegate;

/**
 *
 */
public class JettyAppEngineWebAppContext extends AppEngineWebAppContext {

  public JettyAppEngineWebAppContext(
      final File appDir,
      final String serverInfo,
      final Delegate<?> apiProxyDelegate,
      final DevAppServer devAppServer
  ) {
    super(appDir, serverInfo);
    this._scontext.setAttribute("com.google.appengine.devappserver.ApiProxyLocal", apiProxyDelegate);
    this._scontext.setAttribute("com.google.appengine.devappserver.Server", devAppServer);
  }
}
