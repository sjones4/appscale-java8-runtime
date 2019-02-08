/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8.server;

import java.io.IOException;
import java.util.Map;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import com.appscale.appengine.runtime.java8.jetty.JettyContainerService;
import com.google.appengine.tools.development.AppContext;
import com.google.appengine.tools.development.ApplicationConfigurationManager;
import com.google.appengine.tools.development.ApplicationConfigurationManager.ModuleConfigurationHandle;
import com.google.appengine.tools.development.ContainerUtils;
import com.google.appengine.tools.development.DevAppServer;
import com.google.appengine.tools.development.LocalServerEnvironment;
import com.google.appengine.tools.development.ModulesFilterHelper;
import com.google.apphosting.api.ApiProxy.Delegate;
import com.google.apphosting.api.ApiProxy.Environment;
import com.google.apphosting.utils.config.AppEngineWebXml;


/**
 *
 */
public class AppScaleModules implements ModulesFilterHelper {
  private final String address;
  private final int port;
  private final DevAppServer devAppServer;
  private LocalServerEnvironment localServerEnvironment;
  private final ModuleConfigurationHandle moduleConfigurationHandle;
  private final JettyContainerService jettyContainerService;

  public static AppScaleModules createModules(
      final ApplicationConfigurationManager applicationConfigurationManager,
      final String address,
      final int port,
      final DevAppServer devAppServer
  ) {
    return new AppScaleModules(applicationConfigurationManager, address, port, devAppServer);
  }

  public AppScaleModules(
      final ApplicationConfigurationManager applicationConfigurationManager,
      final String address,
      final int port,
      final DevAppServer devAppServer
  ) {
    this.address = address;
    this.port = port;
    this.devAppServer = devAppServer;
    this.moduleConfigurationHandle = applicationConfigurationManager.getModuleConfigurationHandles().get(0);
    jettyContainerService = new JettyContainerService();
  }

  public String getMainModuleName() {
    return moduleConfigurationHandle.getModule().getModuleName();
  }

  public int getMainModulePort() {
    return port;
  }

  public void configure(final Map<String, Object> containerConfigProperties) throws Exception {
    this.localServerEnvironment = jettyContainerService.configure(
        ContainerUtils.getServerInfo(),
        this.address,
        this.port,
        this.moduleConfigurationHandle,
        null,
        containerConfigProperties,
        -1,
        this.devAppServer);
  }

  public void createConnections() throws Exception {
    jettyContainerService.createConnection();
  }

  public void startup() throws Exception {
    jettyContainerService.startup();
  }

  public void shutdown() throws Exception {
    jettyContainerService.shutdown();
  }

  public LocalServerEnvironment getLocalServerEnvironment() {
    return localServerEnvironment;
  }

  public AppEngineWebXml getAppEngineWebXmlConfig() {
    return jettyContainerService.getAppEngineWebXmlConfig();
  }

  public AppContext getAppContext() {
    return jettyContainerService.getAppContext();
  }

  public void setApiProxyDelegate(final Delegate<Environment> apiProxyDelegate) {
    jettyContainerService.setApiProxyDelegate(apiProxyDelegate);
  }

  @Override
  public boolean acquireServingPermit(
      final String moduleOrBackendName,
      final int instanceNumber,
      final boolean allowQueueOnBackends
  ) {
    return true;
  }

  @Override
  public int getAndReserveFreeInstance(final String requestedModuleOrBackendname) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void returnServingPermit(final String moduleOrBackendName, final int instance) {
  }

  @Override
  public boolean checkInstanceExists(final String moduleOrBackendName, final int instance) {
    return false;
  }

  @Override
  public boolean checkModuleExists(final String moduleOrBackendName) {
    return false;
  }

  @Override
  public boolean checkModuleStopped(final String moduleOrBackendName) {
    return this.checkInstanceStopped(moduleOrBackendName, -1);
  }

  @Override
  public boolean checkInstanceStopped(final String moduleOrBackendName, final int instance) {
    return false;
  }

  @Override
  public void forwardToInstance(final String requestedModuleOrBackendName, final int instance, final HttpServletRequest hrequest, final HttpServletResponse hresponse) throws IOException, ServletException {
    jettyContainerService.forwardToServer(hrequest, hresponse);
  }

  @Override
  public boolean isLoadBalancingInstance(final String moduleOrBackendName, final int instance) {
    return false;
  }

  @Override
  public boolean expectsGeneratedStartRequests(final String moduleOrBackendName, final int instance) {
    return false;
  }

  @Override
  public int getPort(final String moduleOrBackendName, final int instance) {
    return port;
  }
}
