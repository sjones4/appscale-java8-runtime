/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AllPermission;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;
import com.google.appengine.tools.info.AppengineSdk;
import com.google.appengine.tools.info.SdkInfo;

/**
 *
 */
public class RuntimeAppClassLoader extends URLClassLoader {
  private static final Logger logger = Logger.getLogger(RuntimeAppClassLoader.class.getName());

  private final ClassLoader appServerClassLoader;
  private final Set<URL> sharedCodeLibs;

  public RuntimeAppClassLoader(final URL[] urls, final ClassLoader appServerClassLoader) {
    super(urls);
    this.appServerClassLoader = appServerClassLoader;
    this.sharedCodeLibs = new HashSet<>(AppengineSdk.getSdk().getSharedLibs());
    try {
      this.sharedCodeLibs.add(new File(new File(new File(SdkInfo.getSdkRoot(), "lib"), "shared"), "appscale-java8-runtime-container.jar").toURL());
      this.sharedCodeLibs.add(new File(new File(new File(SdkInfo.getSdkRoot(), "lib"), "shared"), "appscale-java8-runtime-application.jar").toURL());
    } catch (final MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }

  public URL getResource(String name) {
    URL resource = this.appServerClassLoader.getResource(name);
    if (resource != null && resource.getProtocol().equals("jar")) {
      int bang = resource.getPath().indexOf(33);
      if (bang > 0) {
        try {
          URL url = new URL(resource.getPath().substring(0, bang));
          if (this.sharedCodeLibs.contains(url)) {
            return resource;
          }
        } catch (final MalformedURLException e) {
          logger.warning("Error loading resource: " + e);
        }
      }
    }

    return super.getResource(name);
  }

  protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    try {
      final Class<?> c = this.appServerClassLoader.loadClass(name);
      final CodeSource source = c.getProtectionDomain().getCodeSource();
      if (source == null) {
        return c;
      }

      final URL location = source.getLocation();
      if (this.sharedCodeLibs.contains(location)) {
        if (resolve) {
          this.resolveClass(c);
        }
        return c;
      }
    } catch (ClassNotFoundException var6) {
    }
    return super.loadClass(name, resolve);
  }

  protected PermissionCollection getPermissions(final CodeSource codesource) {
    PermissionCollection permissions = super.getPermissions(codesource);
    permissions.add(new AllPermission());
    return permissions;
  }
}
