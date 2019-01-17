/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8.users;

import java.io.IOException;
import java.util.Objects;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Login servlet that redirects to the dashboard login server
 */
public class LoginServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;

  public void doGet(
      final HttpServletRequest request,
      final HttpServletResponse response
  ) throws IOException {
    final String continueUrl = Objects.toString(request.getParameter("continue"),"/");
    final String redirectUrl = UserServiceImpl.buildLoginUrl(
        request,
        UserServiceImpl.PATH_LOGIN,
        continueUrl
    );
    response.sendRedirect(redirectUrl);
  }
}
