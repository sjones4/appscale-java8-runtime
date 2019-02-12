/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8.util;

import java.util.Date;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.google.appengine.tools.development.DevSocketImplFactory;
import com.google.apphosting.api.ApiProxy;
import com.google.apphosting.api.ApiProxy.Environment;

/**
 *
 */
public class CurrentRequestThreadFactory implements ThreadFactory {
  private static final Logger logger = Logger.getLogger(CurrentRequestThreadFactory.class.getName());

  public Thread newThread(final Runnable runnable) {
    final boolean callerNativeMode = DevSocketImplFactory.isNativeSocketMode();
    final Environment environment = RuntimeEnvironment.current();
    DevSocketImplFactory.setSocketNativeMode(callerNativeMode);
    Thread thread = new Thread() {
      public void start() {
        super.start();
        RuntimeEnvironmentListener.onRequestEnd( environment -> {
          if (isAlive()) {
            logger.info("Interrupting request thread: " + getName());
            interrupt();
            logger.info("Waiting up to 100ms for thread to complete: " + getName());

            try {
              join(100L);
            } catch (InterruptedException e) {
              logger.info("Interrupted while waiting.");
            }

            if (isAlive()) {
              logger.info("Interrupting request thread again: " + getName());
              interrupt();
              final long remaining = getRemainingMillis(environment);
              logger.info("Waiting up to " + remaining + " ms for thread to complete: " + getName());

              try {
                join(remaining);
              } catch (InterruptedException e) {
                logger.info("Interrupted while waiting.");
              }

              if (isAlive()) {
                Throwable stack = new Throwable();
                stack.setStackTrace(getStackTrace());
                logger.log(Level.SEVERE, "Thread left running: " + getName(), stack);
              }
            }
          }
        });
      }

      public void run() {
        ApiProxy.setEnvironmentForCurrentThread(environment);
        runnable.run();
      }
    };
    System.setProperty("devappserver-thread-" + thread.getName(), "true");
    return thread;
  }

  private static long getRemainingMillis(final RuntimeEnvironment environment) {
    final boolean offline = environment.getAttribute(RuntimeEnvironment.ATTR_OFFLINE).orElse(false);
    final Date startDate = environment.getAttribute(RuntimeEnvironment.ATTR_STARTTIME).orElseGet(Date::new);
    return TimeUnit.MINUTES.toMillis(offline ? 10 : 1) - Math.max(0, System.currentTimeMillis() - startDate.getTime());
  }
}