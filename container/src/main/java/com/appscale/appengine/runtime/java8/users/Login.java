/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8.users;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import javax.servlet.http.HttpServletRequest;

/**
 *
 */
public class Login {
  private static final String PATH_LOGIN = System.getProperty("appscale.loginPath", "/login");
  private static final String PATH_LOGOUT = System.getProperty("appscale.logoutPath", "/logout");
  private static final String LOGIN_PORT = System.getProperty("appscale.loginPort", "1443");

  private static final String LOGIN_SERVER = System.getProperty("LOGIN_SERVER");

  /**
   * Construct a login url for the given request.
   */
  public static String buildLoginUrl(final HttpServletRequest request, final String destinationURL) {
    return buildUrl(request, PATH_LOGIN, destinationURL);
  }

  /**
   * Construct a logout url for the given request.
   */
  public static String buildLogoutUrl(final HttpServletRequest request, final String destinationURL) {
    return buildUrl(request, PATH_LOGOUT, destinationURL);
  }

  private static String buildUrl(final HttpServletRequest request, final String path, final String destinationURL) {
    final String login_host = LOGIN_SERVER != null ? LOGIN_SERVER : request.getServerName();
    final String login_service_endpoint = "https://" + login_host + ":" + LOGIN_PORT + path;
    final String continue_url = resolveUrl(request, destinationURL != null ? destinationURL : "");
    try {
      return login_service_endpoint + "?continue=" +
          URLEncoder.encode( continue_url, StandardCharsets.UTF_8.name() );
    } catch ( UnsupportedEncodingException e ) {
      throw new RuntimeException( e );
    }
  }

  private static String resolveUrl(final HttpServletRequest request, final String url) {
    if ( request == null ) {
      return url;
    }
    try {
      return URI.create(request.getRequestURL().toString()).resolve(url).toString();
    } catch (final Exception e) {
      return url;
    }
  }
}
