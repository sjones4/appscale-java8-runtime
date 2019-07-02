/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import com.appscale.appengine.runtime.java8.util.RuntimeEnvironment;
import com.google.appengine.repackaged.com.google.net.util.proto2api.Status.StatusProto;
import com.google.appengine.repackaged.org.apache.http.HttpResponse;
import com.google.appengine.repackaged.org.apache.http.StatusLine;
import com.google.appengine.repackaged.org.apache.http.client.HttpResponseException;
import com.google.appengine.repackaged.org.apache.http.client.methods.HttpPost;
import com.google.appengine.repackaged.org.apache.http.conn.HttpClientConnectionManager;
import com.google.appengine.repackaged.org.apache.http.entity.ByteArrayEntity;
import com.google.appengine.repackaged.org.apache.http.impl.client.CloseableHttpClient;
import com.google.appengine.repackaged.org.apache.http.impl.client.HttpClientBuilder;
import com.google.appengine.repackaged.org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import com.google.appengine.tools.development.ApiUtils;
import com.google.appengine.tools.development.DevSocketImplFactory;
import com.google.apphosting.utils.remoteapi.RemoteApiPb.Request;
import com.google.apphosting.utils.remoteapi.RemoteApiPb.Response;
import com.google.apphosting.utils.runtime.ApiProxyUtils;

/**
 *
 */
public class AppScaleApiClient {
  private static final int MAX_CONNECTIONS = 50;
  private static final int CONNECTION_VALIDATE_SECS = 0;
  private static final int CONNECTION_TTL_SECS = 30;

  private final int port;
  private final CloseableHttpClient httpClient = HttpClientBuilder.create()
      .disableRedirectHandling()
      .setConnectionManager(connectionManager())
      .build();

  private static HttpClientConnectionManager connectionManager() {
    final PoolingHttpClientConnectionManager cm =
        new PoolingHttpClientConnectionManager(CONNECTION_TTL_SECS, TimeUnit.SECONDS);
    cm.setMaxTotal(MAX_CONNECTIONS);
    cm.setDefaultMaxPerRoute(MAX_CONNECTIONS);
    cm.setValidateAfterInactivity((int)TimeUnit.MILLISECONDS.convert(CONNECTION_VALIDATE_SECS, TimeUnit.SECONDS));
    return cm;
  }

  public AppScaleApiClient(final int port) {
    this.port = port;
  }

  public byte[] makeSyncCall(
      final String packageName,
      final String methodName,
      final byte[] requestBytes
  ) throws IOException {
    final Request remoteApiRequest = new Request();
    remoteApiRequest.setServiceName(packageName);
    remoteApiRequest.setMethod(methodName);
    remoteApiRequest.setRequestAsBytes(requestBytes);
    remoteApiRequest.setRequestId(UUID.randomUUID().toString().substring(0, 10));
    final byte[] remoteApiRequestBytes = ApiUtils.convertPbToBytes(remoteApiRequest);
    final HttpPost post = new HttpPost("http://127.0.0.1:" + this.port);
    post.addHeader("Host", "localhost");
    post.addHeader("Content-Type", "application/octet-stream");
    RuntimeEnvironment.getCurrentAttribute(RuntimeEnvironment.ATTR_REQUEST).ifPresent(request -> {
      final String scheme = request.getScheme().toLowerCase();
      post.setHeader("Host", request.getHeader("Host"));  //TODO: remove once X-Forwarded-Host implemented
      post.addHeader("X-Forwarded-Host", request.getHeader("Host"));
      post.addHeader("X-Forwarded-For", request.getRemoteAddr());
      post.addHeader("X-Forwarded-Proto", scheme);
      post.addHeader("X-Forwarded-Ssl", "https".equals(scheme) ? "on" : "off");
    });
    post.setEntity(new ByteArrayEntity(remoteApiRequestBytes));
    final boolean oldNativeSocketMode = DevSocketImplFactory.isNativeSocketMode();
    DevSocketImplFactory.setSocketNativeMode(true);

    final ByteArrayOutputStream bout = new ByteArrayOutputStream(1024);
    try {
      final HttpResponse response = httpClient.execute(post);
      final StatusLine statusLine = response.getStatusLine();
      if (statusLine.getStatusCode() != 200) {
        throw new HttpResponseException(statusLine.getStatusCode(), statusLine.getReasonPhrase());
      }
      response.getEntity().writeTo(bout);
    } catch (final IOException e) {
      throw new IOException("Error executing POST to HTTP API server: " + e.getMessage(), e);
    } finally {
      DevSocketImplFactory.setSocketNativeMode(oldNativeSocketMode);
    }

    final Response response = new Response();
    final boolean responseInputStream = response.mergeFrom(bout.toByteArray());
    if (!responseInputStream) {
      throw new IOException("Error parsing the response from the HTTP API server.");
    } else if (response.hasApplicationError()) {
      throw ApiProxyUtils.getRpcError(packageName, methodName, StatusProto.getDefaultInstance(),
          response.getApplicationError().getCode(), response.getApplicationError().getDetail(), null);
    } else if (response.hasRpcError()) {
      throw ApiProxyUtils.getRpcError(packageName, methodName, StatusProto.getDefaultInstance(),
          response.getRpcError().getCode(), response.getRpcError().getDetail(), null);
    } else {
      return response.getResponseAsBytes();
    }
  }

  public void shutdown() {
    try {
      httpClient.close();
    } catch (IOException ignore) {
    }
  }
}
