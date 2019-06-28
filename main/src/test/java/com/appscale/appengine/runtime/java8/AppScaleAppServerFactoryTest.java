/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8;

import org.junit.jupiter.api.Test;

/**
 *
 */
public class AppScaleAppServerFactoryTest {
  @Test
  public void testFactory() throws Exception {
    Class.forName(AppScaleAppServerFactory.APPSERVER_CLASSNAME);
  }
}
