/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8.util;

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import com.google.appengine.tools.development.ApiUtils;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.ApiProxy.Environment;
import com.google.apphosting.api.logservice.LogStubServicePb.EndRequestLogRequest;
import com.google.apphosting.api.logservice.LogStubServicePb.StartRequestLogRequest;

/**
 *
 */
public class RequestLogFilter implements Filter {

  public void init(FilterConfig filterConfig) {
  }

  public void destroy() {
  }

  public void doFilter(
      final ServletRequest request,
      final ServletResponse response,
      final FilterChain chain
  ) throws IOException, ServletException {
    final String requestId = RuntimeEnvironment.current()
        .getAttribute(RuntimeEnvironment.ATTR_REQUEST_LOG_ID)
        .orElseThrow(()->new ServletException("No id"));
    if (ApiUtils.isUsingPythonStub("logservice")) {
      final RecordingResponseWrapper httpResponse =
          new RecordingResponseWrapper((HttpServletResponse)response);
      this.notifyStartRequest((HttpServletRequest)request, requestId);
      try {
        chain.doFilter(request, httpResponse);
      } finally {
        this.notifyEndRequest(httpResponse, requestId);
      }
    } else {
      chain.doFilter(request, response);
    }
  }

  private void notifyStartRequest(
      final HttpServletRequest request,
      final String requestId
  ) {
    final Environment env = RuntimeEnvironment.current();
    final StartRequestLogRequest startRequestLogRequest = new StartRequestLogRequest()
        .setRequestId(requestId)
        .setUserRequestId(requestId)
        .setIp(request.getRemoteAddr())
        .setAppId(env.getAppId())
        .setVersionId(env.getVersionId())
        .setHost(request.getLocalName())
        .setMethod(request.getMethod())
        .setResource(request.getRequestURI())
        .setHttpVersion(request.getProtocol())
        .setStartTime(System.currentTimeMillis() * 1000L)
        .setModule(env.getModuleId());
    final String nickname = request.getRemoteUser();
    if (nickname != null) {
      startRequestLogRequest.setNickname(nickname);
    }

    final String userAgent = request.getHeader("User-Agent");
    if (userAgent != null) {
      startRequestLogRequest.setUserAgent(userAgent);
    }

    byte[] requestBytes = ApiUtils.convertPbToBytes(startRequestLogRequest);
    ApiProxy.makeSyncCall(
        "logservice",
        "StartRequestLog",
        requestBytes);
  }

  private void notifyEndRequest(
      final RecordingResponseWrapper response,
      final String requestId) {
    final EndRequestLogRequest endRequestLogRequest = new EndRequestLogRequest()
        .setRequestId(requestId)
        .setStatus(response.getStatus())
        .setResponseSize(response.getRecordedHeader("Content-Length"));
    final byte[] requestBytes = ApiUtils.convertPbToBytes(endRequestLogRequest);
    ApiProxy.makeSyncCall("logservice", "EndRequestLog", requestBytes);
  }

  private static class RecordingResponseWrapper extends HttpServletResponseWrapper {
    private int status = 200;
    private int contentLength = 0;

    RecordingResponseWrapper(HttpServletResponse response) {
      super(response);
    }

    public void setStatus(int sc) {
      this.status = sc;
      super.setStatus(sc);
    }

    @SuppressWarnings("deprecation")
    @Deprecated
    @Override
    public void setStatus(int status, String string) {
      super.setStatus(status, string);
      this.status = status;
    }

    @Override
    public int getStatus() {
      return this.status;
    }

    @Override
    public void setIntHeader(String name, int value) {
      if (name.equalsIgnoreCase("content-length")) {
        this.contentLength = value;
      }
      super.setIntHeader(name, value);
    }

    public int getRecordedHeader(String name) {
      if (!name.equalsIgnoreCase("content-length")) {
        throw new IllegalArgumentException("RecordingResponseWrapper only tracks the Content-Length header value.");
      } else {
        return this.contentLength;
      }
    }

    @Override
    public void reset() {
      super.reset();
      this.status = 200;
      this.contentLength = 0;
    }
  }
}
