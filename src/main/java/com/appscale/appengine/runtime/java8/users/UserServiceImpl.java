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
import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserService;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.ApiProxy.Environment;

/**
 *
 */
public class UserServiceImpl implements UserService {

  static final String PATH_LOGIN = System.getProperty("appscale.loginPath", "/login");
  static final String PATH_LOGOUT = System.getProperty("appscale.logoutPath", "/logout");
  static final String LOGIN_PORT = System.getProperty("appscale.loginPort", "1443");

  private static final String LOGIN_SERVER = System.getProperty("LOGIN_SERVER");

  @Override
  public String createLoginURL(final String destinationURL) {
    return buildLoginUrl(getRequest(), PATH_LOGIN, destinationURL);
  }

  @Override
  public String createLoginURL(final String destinationURL, final String authDomain) {
    return createLoginURL(destinationURL);
  }

  @Override
  public String createLoginURL(
      final String destinationURL,
      final String authDomain,
      final String federatedIdentity,
      final Set<String> attributesRequest
  ) {
    return createLoginURL(destinationURL);
  }

  @Override
  public String createLogoutURL(final String destinationURL) {
    return buildLoginUrl(getRequest(), PATH_LOGOUT, destinationURL);
  }

  @Override
  public String createLogoutURL(final String destinationURL, final String authDomain) {
    return createLogoutURL(destinationURL);
  }

  @Override
  public boolean isUserLoggedIn( ) {
    return environment().isLoggedIn();
  }

  @Override
  public boolean isUserAdmin( ) {
    final Environment environment = environment();
    if (!environment.isLoggedIn()) {
      throw new IllegalStateException( "Not logged in" );
    }
    return environment.isAdmin();
  }

  @Override
  public User getCurrentUser( ) {
    Environment environment = environment();
    if (!environment.isLoggedIn()) {
      return null;
    } else {
      String userId = (String)environment.getAttributes().get("com.google.appengine.api.users.UserService.user_id_key");
      return new User(environment.getEmail(), environment.getAuthDomain(), userId);
    }
  }

  static String buildLoginUrl(final HttpServletRequest request, final String path, final String destinationURL) {
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

  static Environment environment() {
    Environment environment = ApiProxy.getCurrentEnvironment();
    if (environment == null) {
      throw new IllegalStateException("Operation not allowed in a thread that is neither the original request thread nor a thread created by ThreadManager");
    } else {
      return environment;
    }
  }

  private static HttpServletRequest getRequest() {
    return (HttpServletRequest)environment().getAttributes().get("com.google.appengine.http_servlet_request");
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
