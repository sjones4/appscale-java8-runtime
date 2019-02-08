/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import com.google.appengine.repackaged.com.google.common.base.MoreObjects;

/**
 *
 */
public class LoginCookies {
  public static final String  COOKIE_NAME             = "dev_appserver_login";
  private final static String SHA                     = "SHA";
  private static final String APPLICATION_ID_PROPERTY = "APPLICATION_ID";
  private static final String COOKIE_SECRET_PROPERTY  = "COOKIE_SECRET";
  private static final String CLOUD_ADMIN_MARKER      = "CLOUD_ADMIN";

  private static final Logger logger                  = Logger.getLogger( LoginCookies.class.getName());
  private static final String hextab                  = "0123456789abcdef";

  public static Optional<LoginCookie> fromRequest(final HttpServletRequest request) {
    final Cookie cookie = findCookie(request);
    if (cookie == null) {
      return Optional.empty();
    }
    final LoginCookie cookieData = parseCookie(cookie);
    if( !cookieData.isValid() ||
        cookieData.getUserId() == null ||
        cookieData.getEmail() == null ||
        cookieData.getUserId().equals("") ||
        cookieData.getEmail().equals("")) {
      return Optional.empty();
    } else {
      return Optional.of(cookieData);
    }
  }

  private static LoginCookie parseCookie( final Cookie cookie) {
    String value = cookie.getValue();

    // replace chars
    value = value.replace("%3A", ":");
    value = value.replace("%40", "@");
    value = value.replace("%2C", ",");
    final String[] parts = value.split(":");
    if (parts.length < 4) {
      logger.log( Level.SEVERE, "Invalid cookie");
      return new LoginCookie("", false, "", false);
    }
    String email = parts[0];
    String nickname = parts[1];
    boolean admin = false;
    String[] adminList = parts[ 2 ].split( "," );
    String curApp = System.getProperty(APPLICATION_ID_PROPERTY);
    if (curApp == null) {
      logger.log(Level.FINE, "Current app is not set when placing cookie!");
    } else {
      for (final String s : adminList) {
        if (s.equals( curApp) || s.equals(CLOUD_ADMIN_MARKER)) {
          admin = true;
        }
      }
    }
    String hsh = parts[3];
    boolean valid_cookie = true;
    String cookie_secret = System.getProperty(COOKIE_SECRET_PROPERTY);
    if (cookie_secret == null || cookie_secret.isEmpty()) {
      return new LoginCookie("", false, "", false);
    }
    if (email.equals("")) {
      nickname = "";
      admin = false;
    } else {
      try {
        MessageDigest sha = MessageDigest.getInstance(SHA);
        sha.update((email + nickname + parts[2] + cookie_secret).getBytes());
        StringBuilder builder = new StringBuilder();
        // padding 0
        for (byte b : sha.digest()) {
          byte tmphigh = (byte)(b >> 4);
          tmphigh = (byte)(tmphigh & 0xf);
          builder.append(hextab.charAt(tmphigh));
          byte tmplow = (byte)(b & 0xf);
          builder.append(hextab.charAt(tmplow));
        }
        String vhsh = builder.toString();
        if (!vhsh.equals(hsh))
        {
          valid_cookie = false;
          email = "";
          admin = false;
          nickname = "";
        }
      }
      catch (NoSuchAlgorithmException e) {
        logger.log(Level.SEVERE, "Decoding cookie failed");
        return new LoginCookie("", false, "", false);
      }
    }
    final LoginCookie cookieData = new LoginCookie(email, admin, nickname, valid_cookie);
    logger.fine( "Login cookie: " + cookieData );
    return cookieData;
  }

  private static Cookie findCookie( final HttpServletRequest req ) {
    final Cookie[] cookies = req.getCookies();
    if (cookies != null) {
      for (final Cookie cookie : cookies) {
        if (cookie.getName().equals(COOKIE_NAME)) {
          return cookie;
        }
      }
    }
    return null;
  }

  public static final class LoginCookie {
    private final String  email;
    private final boolean isAdmin;
    private final String  nickname;
    private final boolean valid;

    public LoginCookie( String email, boolean isAdmin, String nickname, boolean isValid ) {
      this.email = email;
      this.isAdmin = isAdmin;
      this.nickname = nickname;
      this.valid = isValid;
    }

    public String getEmail()
    {
      return this.email;
    }

    public boolean isAdmin()
    {
      return this.isAdmin;
    }

    public String getUserId()
    {
      return this.nickname;
    }

    public boolean isValid()
    {
      return this.valid;
    }

    @Override
    public String toString( ) {
      return MoreObjects.toStringHelper( this )
          .add( "email", email )
          .add( "isAdmin", isAdmin )
          .add( "nickname", nickname )
          .add( "valid", valid )
          .toString( );
    }
  }
}
