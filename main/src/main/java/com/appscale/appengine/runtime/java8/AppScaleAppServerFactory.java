/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import com.google.appengine.tools.development.DevAppServer;

/**
 *
 */
class AppScaleAppServerFactory {

  static final String APPSERVER_CLASSNAME = "com.appscale.appengine.runtime.java8.server.AppScaleAppServer";

  static DevAppServer newInstance( File appDir, String address, int port) {
    final File webXmlLocation = new File(appDir, "WEB-INF/web.xml");
    final File appEngineWebXmlLocation = new File(appDir, "WEB-INF/appengine-web.xml");
    final AppScaleAppServerClassLoader loader =
        AppScaleAppServerClassLoader.newClassLoader(AppScaleAppServerFactory.class.getClassLoader());
    try {
      @SuppressWarnings( "unchecked" )
      final Class<? extends DevAppServer> appServerClass =
          (Class<? extends DevAppServer>)Class.forName(APPSERVER_CLASSNAME, false, loader);
      final Constructor<? extends DevAppServer> cons =
          appServerClass.getConstructor(File.class, File.class, File.class, String.class, Integer.TYPE, Boolean.TYPE);
      cons.setAccessible(true);
      return cons.newInstance(appDir, webXmlLocation, appEngineWebXmlLocation, address, port, true);
    } catch (Exception ex) {
      Throwable throwable = ex;
      if (ex instanceof InvocationTargetException ) {
        throwable = ex.getCause();
      }
      throw new RuntimeException("Unable to create a DevAppServer", throwable);
    }
  }
}
