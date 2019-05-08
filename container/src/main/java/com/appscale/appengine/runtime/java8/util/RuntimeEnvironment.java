/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8.util;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletRequest;
import com.appscale.appengine.runtime.java8.util.Logins.LoginCookie;
import com.google.appengine.repackaged.com.google.common.base.MoreObjects;
import com.google.appengine.repackaged.com.google.common.base.Strings;
import com.google.appengine.repackaged.com.google.common.collect.ImmutableMap;
import com.google.appengine.repackaged.com.google.common.collect.ImmutableSet;
import com.google.appengine.repackaged.com.google.common.collect.Maps;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.ApiProxy.Environment;


public class RuntimeEnvironment implements ApiProxy.Environment {

  public static final AttributeKey<Collection<RuntimeEnvironmentListener>> ATTR_LISTENERS =
      AttributeKey.of("com.google.appengine.runtime.environment.listeners", Collection.class);
  public static final AttributeKey<Date> ATTR_STARTTIME =
      AttributeKey.of("com.google.appengine.request.start_time", Date.class);
  public static final AttributeKey<Boolean> ATTR_OFFLINE =
      AttributeKey.of("com.google.appengine.request.offline", Boolean.class);
  public static final AttributeKey<String> ATTR_REQUEST_LOG_ID =
      AttributeKey.of("com.google.appengine.runtime.request_log_id", String.class);

  private static final Logger logger = Logger.getLogger(RuntimeEnvironment.class.getName());
  private static final AtomicInteger requestID = new AtomicInteger();

  private static final Set<String> COPY_ATTRS = ImmutableSet.of(
      "com.google.appengine.instance.id",
      "com.google.appengine.instance.port",
      "com.google.appengine.runtime.request_log_id",
      "com.google.appengine.request.offline"
  );

  private final String appId;
  private final String moduleId;
  private final String versionId;
  private final boolean loggedIn;
  private final String email;
  private final boolean admin;
  private final Collection<RuntimeEnvironmentListener> listeners;
  private final ConcurrentMap<String, Object> attributes;
  private final Long endTime;

  public RuntimeEnvironment(
      final String appId,
      final String moduleName,
      final String majorVersionId,
      final RuntimeEnvironmentRequest reRequest,
      final int instance,
      final Integer port,
      final Long deadlineMillis
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
    this.loggedIn = reRequest.isLoggedIn();
    this.email = reRequest.getEmail();
    this.admin = reRequest.isAdmin();
    setInstance( this.attributes, instance );
    setPort( this.attributes, port );
    this.listeners = Collections.newSetFromMap( new ConcurrentHashMap<>( 10 ) );
    this.attributes.put( "com.google.appengine.runtime.request_log_id", this.generateRequestId( ) );
    this.attributes.put( "com.google.appengine.runtime.environment.listeners", this.listeners );
    this.attributes.put( "com.google.appengine.api.ThreadManager.REQUEST_THREAD_FACTORY",
        new CurrentRequestThreadFactory() );
    this.attributes.put( "com.google.appengine.api.ThreadManager.BACKGROUND_THREAD_FACTORY",
        new BackgroundThreadFactory() );
    if (reRequest.isLoggedIn() && !Strings.isNullOrEmpty(reRequest.getUserId())) {
      this.attributes.put("com.google.appengine.api.users.UserService.user_id_key", reRequest.getUserId());
      this.attributes.put("com.google.appengine.api.users.UserService.user_organization", "");
    }
    this.attributes.put("com.google.appengine.request.start_time", new Date());
    this.attributes.putAll(reRequest.getAttributes());

    logger.log( Level.FINE, () -> "Request environment: " + this );
  }

  public RuntimeEnvironment(
      final RuntimeEnvironment environment
  ) {
    this.attributes = new ConcurrentHashMap<>();
    this.appId = environment.appId;
    this.moduleId = environment.moduleId;
    this.versionId = environment.versionId;
    this.endTime = null;
    this.loggedIn = false;
    this.email = null;
    this.admin = false;

    this.listeners = Collections.newSetFromMap( new ConcurrentHashMap<>( 10 ) );
    this.attributes.put( "com.google.appengine.runtime.environment.listeners", this.listeners );
    this.attributes.put( "com.google.appengine.request.start_time", new Date( ) );
    for (final String attrName : COPY_ATTRS) {
      if (environment.attributes.containsKey(attrName)) {
        this.attributes.put(attrName, environment.attributes.get(attrName));
      }
    }
    logger.log( Level.FINE, () -> "Request environment: " + this );
  }

  public static RuntimeEnvironment unauthChild(final Environment environment) {
    if (!(environment instanceof RuntimeEnvironment)) {
      throw new IllegalStateException("Unexpected environment type " + environment);
    }
    return new RuntimeEnvironment((RuntimeEnvironment)environment);
  }

  public static RuntimeEnvironment current() {
    final Environment environment = ApiProxy.getCurrentEnvironment();
    if (!(environment instanceof RuntimeEnvironment)) {
      throw new IllegalStateException("Unexpected environment" + environment);
    }
    return (RuntimeEnvironment) environment;
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

  @SuppressWarnings("unchecked")
  public <T> Optional<T> getAttribute(final AttributeKey<T> key) {
    final Object value = attributes.get(key.getName());
    return key.getType().isInstance(value) ?
        Optional.of((T)value) :
        Optional.empty();
  }

  @Override
  public long getRemainingMillis( ) {
    return this.endTime != null ? this.endTime - System.currentTimeMillis() : Long.MAX_VALUE;
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

  public static class RuntimeEnvironmentRequest {
    public static final String HEADER_FAKE_IS_ADMIN = "X-AppEngine-Fake-Is-Admin";
    public static final String HEADER_QUEUE_NAME = "X-AppEngine-QueueName";

    private final String email;
    private final String userId;
    private final boolean loggedIn;
    private final boolean admin;
    private final Map<String,Object> attributes;

    public static RuntimeEnvironmentRequest forRequest(final HttpServletRequest request) {
      return new RuntimeEnvironmentRequest(request);
    }

    public RuntimeEnvironmentRequest(final HttpServletRequest request) {
      final String email;
      final String userId;
      final boolean loggedIn;
      final boolean admin;

      final Optional<LoginCookie> loginCookie = Logins.cookie(request);
      if (Logins.isForceAdmin(request, HEADER_FAKE_IS_ADMIN)) {
        loggedIn = true;
        userId = null;
        email = "admin@admin.com";
        admin = true;
      } else if (loginCookie.isPresent()) {
        loggedIn = true;
        email = loginCookie.get().getEmail();
        userId = loginCookie.get().getUserId();
        admin = loginCookie.get().isAdmin();
      } else {
        loggedIn = false;
        email = null;
        userId = null;
        admin = false;
      }

      final Map<String,Object> attributes = Maps.newLinkedHashMap();
      if (request.getHeader(HEADER_QUEUE_NAME) != null) {
        attributes.put("com.google.appengine.request.offline", Boolean.TRUE);
      }
      attributes.put("com.google.appengine.http_servlet_request", request);

      this.email = email;
      this.userId = userId;
      this.loggedIn = loggedIn;
      this.admin = admin;
      this.attributes = ImmutableMap.copyOf(attributes);
    }

    public String getEmail() {
      return email;
    }

    public String getUserId() {
      return userId;
    }

    public boolean isLoggedIn() {
      return loggedIn;
    }

    public boolean isAdmin() {
      return admin;
    }

    public Map<String,Object> getAttributes( ) {
      return this.attributes;
    }
  }

  public static final class AttributeKey<T> {
    private final Class<? super T> typeClass;
    private final String name;

    private AttributeKey(final Class<? super T> typeClass, final String name) {
      this.typeClass = typeClass;
      this.name = name;
    }

    public static <T> AttributeKey<T> of(final String name, final Class<? super T> typeClass) {
      return new AttributeKey<>(typeClass, name);
    }

    public String getName() {
      return name;
    }

    public Class<? super T> getType() {
      return typeClass;
    }
  }
}
