/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.api.channel;

import java.io.IOException;
import java.io.InputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 *
 */
public class ServeScriptServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private static final String SCRIPT_RESOURCE = "channel_api.js";

  public void doGet(
      final HttpServletRequest req,
      final HttpServletResponse resp
  ) throws IOException {
    resp.setContentType("text/javascript");

    try (final InputStream in = this.getClass().getResourceAsStream(SCRIPT_RESOURCE)) {
      final ServletOutputStream out = resp.getOutputStream();
      final byte[] data = new byte[1024];

      int read;
      while((read = in.read(data)) != -1) {
        out.write(data, 0, read);
      }
      out.flush();
    }
  }
}
