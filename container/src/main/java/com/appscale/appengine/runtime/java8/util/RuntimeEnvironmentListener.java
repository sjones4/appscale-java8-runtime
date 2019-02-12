/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8.util;

import static com.appscale.appengine.runtime.java8.util.RuntimeEnvironmentListener.RuntimeEnvironmentListenerDispatch.dispatch;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 */
public interface RuntimeEnvironmentListener {

  void onRequestEnd(RuntimeEnvironment environment);

  static void onRequestEnd(Consumer<RuntimeEnvironment> callback) {
    final RuntimeEnvironment environment = RuntimeEnvironment.current();
    final Optional<Collection<RuntimeEnvironmentListener>> listeners =
        environment.getAttribute(RuntimeEnvironment.ATTR_LISTENERS);
    if (listeners.isPresent()) {
      listeners.get().add(new RuntimeEnvironmentListenerSupport(){
        @Override
        public void onRequestEnd(final RuntimeEnvironment environment) {
          callback.accept(environment);
        }
      });
    } else {
      throw new IllegalStateException("No listeners found");
    }
  }

  static void requestEnd(final RuntimeEnvironment environment) {
    dispatch(environment, "request end", l -> l.onRequestEnd(environment));
  }

  class RuntimeEnvironmentListenerSupport implements RuntimeEnvironmentListener {
    @Override
    public void onRequestEnd(final RuntimeEnvironment environment) {
    }
  }

  class RuntimeEnvironmentListenerDispatch {
    private static final Logger logger = Logger.getLogger(RuntimeEnvironmentListener.class.getName());

    static void dispatch(
        final RuntimeEnvironment environment,
        final String desc,
        final Consumer<RuntimeEnvironmentListener> consumer
    ) {
      final Optional<Collection<RuntimeEnvironmentListener>> listeners =
          environment.getAttribute(RuntimeEnvironment.ATTR_LISTENERS);
      if (listeners.isPresent()) {
        for (final RuntimeEnvironmentListener listener : listeners.get()) {
          try {
            consumer.accept(listener);
          } catch (final Exception e) {
            String listenerType = String.valueOf(listener.getClass());
            logger.log(
                Level.WARNING,
                "Error handling "+desc+" notification for listener of type "+listenerType,
                e );
          }
        }
      }
    }
  }
}
