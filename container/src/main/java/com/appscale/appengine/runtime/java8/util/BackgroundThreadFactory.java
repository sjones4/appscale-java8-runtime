/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8.util;

import java.util.concurrent.ThreadFactory;
import com.google.appengine.tools.development.DevSocketImplFactory;
import com.google.apphosting.api.ApiProxy;

/**
 *
 */
public class BackgroundThreadFactory implements ThreadFactory {

  public Thread newThread(final Runnable runnable) {
    final RuntimeEnvironment environment = RuntimeEnvironment.unauthChild(RuntimeEnvironment.current());
    final boolean callerNativeMode = DevSocketImplFactory.isNativeSocketMode();
    Thread thread = new Thread(runnable) {
      public void run() {
        DevSocketImplFactory.setSocketNativeMode(callerNativeMode);
        ApiProxy.setEnvironmentForCurrentThread(environment);
        try {
          runnable.run();
        } finally {
          RuntimeEnvironmentListener.requestEnd(environment);
        }
      }
    };
    System.setProperty("devappserver-thread-" + thread.getName(), "true");
    return thread;
  }
}