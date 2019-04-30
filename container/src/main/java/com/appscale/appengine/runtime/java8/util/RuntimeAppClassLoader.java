/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AllPermission;
import java.security.CodeSource;
import java.security.PermissionCollection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import com.google.appengine.repackaged.com.google.common.collect.ImmutableSet;
import com.google.appengine.tools.development.ClassLoaderUtil;
import com.google.appengine.tools.info.AppengineSdk;
import com.google.appengine.tools.info.SdkInfo;
import com.google.apphosting.utils.config.XmlUtils;

/**
 *
 */
public class RuntimeAppClassLoader extends URLClassLoader {
  private static final Logger logger = Logger.getLogger(RuntimeAppClassLoader.class.getName());

  private final ClassLoader appServerClassLoader;
  private final Set<URL> sharedCodeLibs;
  private final ImmutableSet<String> classesToBeLoadedByTheRuntimeClassLoader;

  public RuntimeAppClassLoader(final URL[] urls, final String webDefaultXml, final ClassLoader appServerClassLoader) throws IOException {
    super(urls, ClassLoaderUtil.getPlatformClassLoader());
    this.appServerClassLoader = appServerClassLoader;
    this.sharedCodeLibs = new HashSet<>(AppengineSdk.getSdk().getSharedLibs());
    try {
      this.sharedCodeLibs.add(new File(new File(new File(SdkInfo.getSdkRoot().getParentFile(), "lib"), "shared"), "appscale-java8-runtime-container.jar").toURL());
    } catch (final MalformedURLException e) {
      throw new RuntimeException(e);
    }
    try (final InputStream webResInput = RuntimeAppClassLoader.class.getClassLoader().getResourceAsStream(webDefaultXml)) {
      this.classesToBeLoadedByTheRuntimeClassLoader = ImmutableSet.<String>builder()
          .add("com.google.apphosting.runtime.SessionData")
          .addAll(getServletAndFilterClasses(webResInput))
          .build();
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
      if (this.classesToBeLoadedByTheRuntimeClassLoader.contains(name) ||
          this.sharedCodeLibs.contains(location)) {
        if (resolve) {
          this.resolveClass(c);
        }
        return c;
      }
    } catch (ClassNotFoundException ignore) {
    }
    return super.loadClass(name, resolve);
  }

  protected PermissionCollection getPermissions(final CodeSource codesource) {
    PermissionCollection permissions = super.getPermissions(codesource);
    permissions.add(new AllPermission());
    return permissions;
  }

  private static Set<String> getServletAndFilterClasses(final InputStream inputStream) {
    final Function<NodeList, List<String>> classes = nodeList -> {
      final List<String> classNames = new ArrayList<>();
      for(int i = 0; i < nodeList.getLength(); ++i) {
        classNames.add(nodeList.item(i).getTextContent().trim());
      }
      return classNames;
    };

    final Element topElement = XmlUtils.parseXml(inputStream, null).getDocumentElement();
    return ImmutableSet.<String>builder()
        .addAll(classes.apply(topElement.getElementsByTagName("filter-class")))
        .addAll(classes.apply(topElement.getElementsByTagName("servlet-class")))
        .build();
  }
}
