/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8.users;

import com.google.appengine.api.users.IUserServiceFactory;
import com.google.appengine.api.users.UserService;


public class UserServiceFactoryImpl implements IUserServiceFactory {
  @Override
  public UserService getUserService( ) {
    return new UserServiceImpl();
  }
}
