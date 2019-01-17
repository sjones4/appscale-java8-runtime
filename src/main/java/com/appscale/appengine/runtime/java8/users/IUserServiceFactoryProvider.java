/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8.users;

import com.google.appengine.api.users.IUserServiceFactory;
import com.google.appengine.spi.FactoryProvider;
import com.google.appengine.spi.ServiceProvider;

@ServiceProvider(
    value = FactoryProvider.class
)
public class IUserServiceFactoryProvider extends FactoryProvider<IUserServiceFactory> {
  private UserServiceFactoryImpl instance = new UserServiceFactoryImpl();

  public IUserServiceFactoryProvider() {
    super(IUserServiceFactory.class);
  }

  protected IUserServiceFactory getFactoryInstance() {
    return this.instance;
  }
}
