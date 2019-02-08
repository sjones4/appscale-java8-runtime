/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8.server;

import java.util.Collections;
import java.util.Map;
import com.google.appengine.tools.development.AbstractContainerService.PortMappingProvider;

/**
 *
 */
public class AppScalePortMappingProvider implements PortMappingProvider {

  @Override
  public Map<String, String> getPortMapping( ) {
    return Collections.emptyMap( );
  }
}
