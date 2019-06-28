/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import javax.servlet.http.Cookie;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.appscale.appengine.runtime.java8.util.Logins.LoginCookie;

/**
 *
 */
public class LoginsTest {

  @ParameterizedTest
  @ValueSource(strings = {
      "example@appscale.internal:example::6d5e52015d3752026ba46e3670d4c58740914d20",
      "example@appscale.internal:example:other-app:299f19ccc9a94abe204a56049a7945032ba2a7ed",
  })
  public void testLoginCookie(final String cookie) {
    System.setProperty("APPLICATION_ID", "test");
    System.setProperty("COOKIE_SECRET", "5a5db44769184e83a6f9d582fe8b22b4");

    final LoginCookie lc = Logins.parseCookie(new Cookie("dev_appserver_login", cookie));

    assertTrue(lc.isValid(), "Valid login");
    assertFalse(lc.isAdmin(), "Admin login");
    assertEquals(cookie.split(":")[0], lc.getEmail(), "Email");
    assertEquals(cookie.split(":")[1], lc.getUserId(), "User Id");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "example@appscale.internal:example:CLOUD_ADMIN:f54dc3185a409d3c7a33097d452c40e67e7bef9e",
      "example@appscale.internal:example:test:b9f04d0449ad394743d830b2ad7d73159196175e",
  })
  public void testAdminLoginCookie(final String cookie) {
    System.setProperty("APPLICATION_ID", "test");
    System.setProperty("COOKIE_SECRET", "5a5db44769184e83a6f9d582fe8b22b4");

    final LoginCookie lc = Logins.parseCookie(new Cookie("dev_appserver_login", cookie));

    assertTrue(lc.isValid(), "Valid login");
    assertTrue(lc.isAdmin(), "Admin login");
    assertEquals(cookie.split(":")[0], lc.getEmail(), "Email");
    assertEquals(cookie.split(":")[1], lc.getUserId(), "User Id");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "example2@appscale.internal:example:CLOUD_ADMIN:f54dc3185a409d3c7a33097d452c40e67e7bef9e",
      "example2@appscale.internal:example:test:b9f04d0449ad394743d830b2ad7d73159196175e",
      "example@appscale.internal:example:CLOUD_ADMIN:",
      "example@appscale.internal:example:CLOUD_ADMIN",
      "example@appscale.internal:example:",
      "example@appscale.internal:example",
      "",
      ":",
      "::",
      ":::",
      "::::",
  })
  public void testInvalidLoginCookie(final String cookie) {
    System.setProperty("APPLICATION_ID", "test");
    System.setProperty("COOKIE_SECRET", "5a5db44769184e83a6f9d582fe8b22b4");

    final LoginCookie lc = Logins.parseCookie(new Cookie("dev_appserver_login", cookie));

    assertFalse(lc.isValid(), "Valid login");
    assertFalse(lc.isAdmin(), "Admin login");
    assertEquals("", lc.getEmail(), "Email");
    assertEquals("", lc.getUserId(), "User Id");
  }
}
