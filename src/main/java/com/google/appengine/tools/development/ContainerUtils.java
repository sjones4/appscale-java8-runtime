package com.google.appengine.tools.development;

import com.appscale.appengine.runtime.java8.jetty.JettyContainerService;
import com.google.appengine.tools.info.AppengineSdk;

/**
 *
 */
public class ContainerUtils {
  public static ContainerService loadContainer() {
    return new JettyContainerService( );
  }

  public static String getServerInfo() {
    final String version = String.valueOf(AppengineSdk.getSdk().getLocalVersion().getRelease());
    return "Google App Engine Development/" + version;
  }
}
