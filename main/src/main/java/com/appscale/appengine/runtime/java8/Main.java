/**
 * Copyright 2019 AppScale Systems, Inc
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.appscale.appengine.runtime.java8;

import static com.google.appengine.repackaged.com.google.common.io.Files.asCharSource;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import com.google.appengine.repackaged.com.google.common.collect.ImmutableList;
import com.google.appengine.tools.development.DevAppServer;
import com.google.appengine.tools.development.SharedMain;
import com.google.appengine.tools.util.Action;
import com.google.appengine.tools.util.Option;
import com.google.appengine.tools.util.Parser;
import com.google.appengine.tools.util.Parser.ParseResult;

/**
 * AppScale Java 8 application runtime application.
 */
public class Main extends SharedMain {
  private static final String PATH_SECRET_KEY = "/etc/appscale/secret.key";

  private static final String SYSPROP_COOKIE_SECRET = "COOKIE_SECRET";
  private static final String SYSPROP_PIDFILE = "appscale.pidfile";

  private String address = "localhost";
  private int port = 8080;

  private static void appscaleInit() {
    setSystemProperties();
    setSecret();
    writePidFile();
  }

  public static void main(final String[] args) {
    SharedMain.sharedInit();
    appscaleInit();
    new Main().run(args);
  }

  @Override
  protected void printHelp(final PrintStream out) {
    out.println("Usage: java " + Main.class.getName() + " [options] <app directory>");
    out.println("");
    out.println("Options:");
    for (final Option option : this.buildOptions()) {
      for (final String helpString : option.getHelpLines()) {
        out.println(helpString);
      }
    }
    out.println(" --jvm_flag=FLAG            Pass FLAG as a JVM argument. May be repeated to");
    out.println("                              supply multiple flags.");
  }

  private List<Option> buildOptions() {
    final List<Option> options = new ArrayList<>();
    options.addAll(this.getSharedOptions());
    options.addAll(Arrays.asList(
     new Option("a", "address", false) {
      public void apply() {
        address = this.getValue();
      }

      public List<String> getHelpLines() {
        return ImmutableList.of(" --address=ADDRESS          The address of the interface on the local machine", "  -a ADDRESS                  to bind to (or 0.0.0.0 for all interfaces).");
      }
    }, new Option("p", "port", false) {
      public void apply() {
        port = Integer.valueOf(this.getValue());
      }

      public List<String> getHelpLines() {
        return ImmutableList.of(" --port=PORT                The port number to bind to on the local machine.", "  -p PORT");
      }
    }));
    return options;
  }

  private void run(final String[] args) {
    Parser parser = new Parser();
    ParseResult result = parser.parseArgs(new StartAction(), buildOptions(), args);
    result.applyArgs();
  }

  private static void setSystemProperties() {
    System.setProperty("use_jetty9_runtime", "true");
    System.setProperty("appengine.disableFilesApiWarning", "true");
    System.setProperty("com.google.appengine.disable_api_deadlines", "true");
    //com.google.appengine.runtime.environment	Development
    //com.google.appengine.plugin.path
    //appengine.allowRemoteShutdown
    //appengine.generated.dir
    //appengine.api.urlfetch.defaultDeadline
    //appengine.pythonApiServerFlags
    //appengine.fullscan.seconds
    //appengine.runtime
    //appengine.pathToPythonApiServer
    //appengine.apisUsingPythonStubs
    //appengine.default.gcs.bucket.name
  }

  private static void setSecret() {
    try {
      String value = asCharSource(new File( PATH_SECRET_KEY ), StandardCharsets.UTF_8).read();
      System.setProperty( SYSPROP_COOKIE_SECRET, value);
    } catch (final IOException e) {
      System.out.println("Error reading secret file " + e);
    }
  }

  private static void writePidFile() {
    final String pidfile = System.getProperty( SYSPROP_PIDFILE );
    if ( pidfile != null ) {
      final String pidString = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
      final Path file = Paths.get(pidfile);
      try {
        Files.write(file, pidString.getBytes(StandardCharsets.UTF_8));
      } catch (final IOException e) {
        System.err.println( "Unable to write pid file, exiting: " + e );
        System.exit(1);
      }
    }
  }

  private class StartAction extends Action {
    private StartAction( ) {
      super( "start" );
    }

    public void apply() {
      final List<String> args = this.getArgs();

      try {
        if (args.isEmpty()) {
          printHelp(System.err);
          System.exit(1);
        }

        for( final Object arg : args ) {
          String path = (String) arg;
          if ( !( new File( path ) ).exists( ) ) {
            System.out.println( path + " does not exist." );
            printHelp( System.err );
            System.exit( 1 );
          }
        }

        File appDir;
        if (args.size() == 1) {
          appDir = (new File((String)args.get(0))).getCanonicalFile();
        } else {
          printHelp(System.err);
          System.exit(1);
          return;
        }

        validateWarPath(appDir);
        configureRuntime(appDir);

        final Map<String, String> stringProperties = getSystemProperties();
        postServerActions(stringProperties);
        addPropertyOptionToProperties(stringProperties);
        try {
          final DevAppServer server = AppScaleAppServerFactory.newInstance(appDir, address, port);
          server.setServiceProperties(stringProperties);
          server.start().await();
        } catch (InterruptedException var8) {
        }

        System.out.println("Shutting down.");
        System.exit(0);
      } catch (Exception var9) {
        var9.printStackTrace();
        System.exit(1);
      }
    }
  }
}
