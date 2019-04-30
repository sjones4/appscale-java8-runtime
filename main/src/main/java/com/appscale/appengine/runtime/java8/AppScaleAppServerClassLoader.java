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
import java.util.ArrayList;
import java.util.List;
import com.google.appengine.repackaged.com.google.common.collect.ImmutableSet;
import com.google.appengine.tools.development.ClassLoaderUtil;
import com.google.appengine.tools.info.AppengineSdk;
import com.google.appengine.tools.info.SdkInfo;

/**
 *
 */
class AppScaleAppServerClassLoader extends URLClassLoader {
  private static final ImmutableSet<String> DELEGATE_CLASSES = ImmutableSet.of(
    "com.google.appengine.tools.development.DevAppServer",
    "com.google.appengine.tools.development.AppContext",
    "com.google.appengine.tools.development.agent.AppEngineDevAgent",
    "com.google.appengine.tools.development.DevSocketImplFactory"
  );

  private final ClassLoader delegate;

  static AppScaleAppServerClassLoader newClassLoader( ClassLoader delegate) {
    final List<URL> libs = new ArrayList<>();
    try {
      libs.add(new File(new File(new File( SdkInfo.getSdkRoot().getParentFile(), "lib"), "shared"), "appscale-java8-runtime-container.jar").toURL());
    } catch (final MalformedURLException e) {
      throw new RuntimeException(e);
    }
    libs.addAll(AppengineSdk.getSdk().getSharedLibs());
    libs.addAll(AppengineSdk.getSdk().getImplLibs());
    libs.addAll(AppengineSdk.getSdk().getUserJspLibs());
    return new AppScaleAppServerClassLoader(libs.toArray(new URL[0]), delegate);
  }

  AppScaleAppServerClassLoader(URL[] urls, ClassLoader delegate) {
    super(urls, ClassLoaderUtil.getPlatformClassLoader());
    this.delegate = delegate;
  }

  protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    if (!DELEGATE_CLASSES.contains(name) &&
        !name.startsWith("com.google.appengine.tools.info.") &&
        !name.startsWith("com.google.apphosting.utils.config.")) {
      return super.loadClass(name, resolve);
    } else {
      final Class<?> clazz = this.delegate.loadClass(name);
      if (resolve) {
        this.resolveClass(clazz);
      }
      return clazz;
    }
  }

  protected PermissionCollection getPermissions( CodeSource codesource) {
    PermissionCollection permissions = super.getPermissions(codesource);
    permissions.add(new AllPermission());
    return permissions;
  }
}
