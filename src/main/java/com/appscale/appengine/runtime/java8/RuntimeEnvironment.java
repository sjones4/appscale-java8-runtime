/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletRequest;
import com.appscale.appengine.runtime.java8.users.LoginCookies;
import com.appscale.appengine.runtime.java8.users.LoginCookies.LoginCookie;
import com.google.appengine.repackaged.com.google.common.base.MoreObjects;
import com.google.appengine.tools.development.BackgroundThreadFactory;
import com.google.appengine.tools.development.ModulesFilterHelper;
import com.google.appengine.tools.development.RequestEndListener;
import com.google.appengine.tools.development.RequestThreadFactory;
import com.google.apphosting.api.ApiProxy;


public class RuntimeEnvironment implements ApiProxy.Environment {

  private static final Logger logger = Logger.getLogger(RuntimeEnvironment.class.getName());
  private static AtomicInteger requestID = new AtomicInteger();

  private final String appId;
  private final String moduleId;
  private final String versionId;
  private final Collection<RequestEndListener> requestEndListeners;
  private final ConcurrentMap<String, Object> attributes;
  private final Long endTime;

  private final String email;
  private final boolean loggedIn;
  private final boolean admin;

  public RuntimeEnvironment(
      final String appId,
      final String moduleName,
      final String majorVersionId,
      final int instance,
      final Integer port,
      final HttpServletRequest request,
      final Long deadlineMillis,
      final ModulesFilterHelper modulesFilterHelper
  ) {
    this.attributes = new ConcurrentHashMap<>();
    this.appId = appId;
    this.moduleId = moduleName;
    this.versionId = MoreObjects.firstNonNull(majorVersionId, "no_version");
    if ( deadlineMillis == null ) {
      this.endTime = null;
    } else {
      if ( deadlineMillis < 0L ) {
        throw new IllegalArgumentException( "deadlineMillis must be a non-negative integer." );
      }
      this.endTime = System.currentTimeMillis( ) + deadlineMillis;
    }

    setInstance( this.attributes, instance );
    setPort( this.attributes, port );
    this.requestEndListeners = Collections.newSetFromMap( new ConcurrentHashMap<>( 10 ) );
    this.attributes.put( "com.google.appengine.runtime.request_log_id", this.generateRequestId( ) );
    this.attributes.put( "com.google.appengine.tools.development.request_end_listeners", this.requestEndListeners );
    this.attributes.put( "com.google.appengine.tools.development.start_time", new Date( ) );
    //TODO thread factories for AppScale using Environments based on RuntimeEnvironment and without thread/api call latencies
    this.attributes.put( "com.google.appengine.api.ThreadManager.REQUEST_THREAD_FACTORY",
        new RequestThreadFactory( ) );
    this.attributes.put( "com.google.appengine.api.ThreadManager.BACKGROUND_THREAD_FACTORY",
        new BackgroundThreadFactory( appId, moduleName, majorVersionId ) );

    final Optional<LoginCookie> loginCookie = LoginCookies.fromRequest(request);
    if (loginCookie.isPresent()) {
      this.loggedIn = true;
      this.email = loginCookie.get().getEmail();
      this.admin = loginCookie.get().isAdmin();
      this.attributes.put("com.google.appengine.api.users.UserService.user_id_key", loginCookie.get().getUserId());
      this.attributes.put("com.google.appengine.api.users.UserService.user_organization", "");
    } else {
      this.loggedIn = false;
      this.email = null;
      this.admin = false;
    }

    this.attributes.put("com.google.appengine.http_servlet_request", request);
    if (modulesFilterHelper != null) {
      this.attributes.put("com.google.appengine.tools.development.modules_filter_helper", modulesFilterHelper);
    }

    logger.log( Level.FINE, () -> "Request environment: " + this );
  }

  static void setInstance(Map<String, Object> attributes, int instance) {
    attributes.remove("com.google.appengine.instance.id");
    if (instance != -1) {
      attributes.put("com.google.appengine.instance.id", Integer.toString(instance));
    }
  }

  static void setPort(Map<String, Object> attributes, Integer port) {
    if (port == null) {
      attributes.remove("com.google.appengine.instance.port");
    } else {
      attributes.put("com.google.appengine.instance.port", port);
    }
  }

  @Override
  public String getAppId( ) {
    return appId;
  }

  @Override
  public String getModuleId( ) {
    return moduleId;
  }

  @Override
  public String getVersionId( ) {
    return versionId;
  }

  @Override
  public String getEmail( ) {
    return email;
  }

  @Override
  public boolean isLoggedIn( ) {
    return loggedIn;
  }

  @Override
  public boolean isAdmin( ) {
    return admin;
  }

  @Override
  public String getAuthDomain( ) {
    return "gmail.com";
  }

  @Override
  public String getRequestNamespace( ) {
    return "";
  }

  @Override
  public Map<String, Object> getAttributes( ) {
    return attributes;
  }

  @Override
  public long getRemainingMillis( ) {
    return this.endTime != null ? this.endTime - System.currentTimeMillis() : Long.MAX_VALUE;
  }

  public void callRequestEndListeners() {
    for ( final RequestEndListener listener : this.requestEndListeners ) {
      try {
        listener.onRequestEnd( this );
      } catch ( Exception var5 ) {
        String var4 = String.valueOf( listener.getClass( ) );
        logger.logp( Level.WARNING,
            RuntimeEnvironment.class.getName( ),
            "callRequestEndListeners",
            "Exception while attempting to invoke RequestEndListener " + var4 + ": ",
            var5 );
      }
    }

    this.requestEndListeners.clear();
  }

  private String generateRequestId( ) {
    try {
      ByteBuffer buf = ByteBuffer.allocate(12);
      long now = System.currentTimeMillis();
      buf.putInt((int)(now / 1000L));
      buf.putInt((int)(now * 1000L % 1000000L));
      String nextID = Integer.toString(requestID.getAndIncrement());
      byte[] hashBytes = MessageDigest.getInstance("SHA-1").digest(nextID.getBytes(StandardCharsets.US_ASCII));
      buf.put(hashBytes, 0, 4);
      return String.format("%x", new BigInteger(buf.array()));
    } catch (Exception var6) {
      return "";
    }
  }

  @Override
  public String toString( ) {
    return MoreObjects.toStringHelper( this )
        .add( "appId", appId )
        .add( "moduleId", moduleId )
        .add( "versionId", versionId )
        .add( "email", email )
        .add( "loggedIn", loggedIn )
        .add( "admin", admin )
        .toString( );
  }
}
