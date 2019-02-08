/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.api.users;

import java.util.Map;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletRequest;
import com.appscale.appengine.runtime.java8.users.Login;
import com.google.appengine.tools.development.AbstractLocalRpcService;
import com.google.appengine.tools.development.LocalServiceContext;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.ApiProxy.Environment;
import com.google.apphosting.api.UserServicePb.CreateLoginURLRequest;
import com.google.apphosting.api.UserServicePb.CreateLoginURLResponse;
import com.google.apphosting.api.UserServicePb.CreateLogoutURLRequest;
import com.google.apphosting.api.UserServicePb.CreateLogoutURLResponse;
import com.google.apphosting.api.UserServicePb.GetOAuthUserRequest;
import com.google.apphosting.api.UserServicePb.GetOAuthUserResponse;

/**
 *
 */
public class UserServiceImpl extends AbstractLocalRpcService {
  private static final Logger logger = Logger.getLogger(UserServiceImpl.class.getName());
  public static final String PACKAGE = "user";

  public CreateLoginURLResponse createLoginURL(final Status status, final CreateLoginURLRequest request) {
    final CreateLoginURLResponse response = new CreateLoginURLResponse();
    response.setLoginUrl(Login.buildLoginUrl(getRequest(), request.getDestinationUrl()));
    return response;
  }

  public CreateLogoutURLResponse createLogoutURL(final Status status, final CreateLogoutURLRequest request) {
    final CreateLogoutURLResponse response = new CreateLogoutURLResponse();
    response.setLogoutUrl(Login.buildLogoutUrl(getRequest(), request.getDestinationUrl()));
    return response;
  }

  public GetOAuthUserResponse getOAuthUser(final Status status, final GetOAuthUserRequest request) {
    final GetOAuthUserResponse response = new GetOAuthUserResponse();
    final Environment environment = environment();
    if (environment.isLoggedIn()) {
      final String userId = (String)environment.getAttributes().get("com.google.appengine.api.users.UserService.user_id_key");
      response.setEmail(environment.getEmail());
      response.setUserId(userId);
      response.setAuthDomain(environment.getAuthDomain());
      response.setIsAdmin(environment.isAdmin());
      if (request.isRequestWriterPermission()) {
        response.setIsProjectWriter(environment.isAdmin());
      }
    }
    return response;
  }

  public String getPackage() {
    return PACKAGE;
  }

  public void init(final LocalServiceContext context, final Map<String, String> properties) {
    logger.info("Initialized user service");
  }

  public void start() {
  }

  public void stop() {
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
}
