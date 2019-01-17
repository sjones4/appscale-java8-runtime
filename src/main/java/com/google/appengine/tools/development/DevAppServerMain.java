package com.google.appengine.tools.development;

import static com.google.appengine.repackaged.com.google.common.io.Files.asCharSource;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import com.google.appengine.repackaged.com.google.common.annotations.VisibleForTesting;
import com.google.appengine.repackaged.com.google.common.collect.ImmutableList;
import com.google.appengine.repackaged.com.google.common.collect.ImmutableMap;
import com.google.appengine.tools.info.AppengineSdk;
import com.google.appengine.tools.info.UpdateCheck;
import com.google.appengine.tools.util.Action;
import com.google.appengine.tools.util.Option;
import com.google.appengine.tools.util.Parser;
import com.google.appengine.tools.util.Parser.ParseResult;

public class DevAppServerMain extends SharedMain {

  public static final String GENERATE_WAR_ARG = "generate_war";

  public static final String GENERATED_WAR_DIR_ARG = "generated_war_dir";

  private static final String DEFAULT_RDBMS_PROPERTIES_FILE = ".local.rdbms.properties";

  private static final String RDBMS_PROPERTIES_FILE_SYSTEM_PROPERTY = "rdbms.properties.file";

  private static final String SYSTEM_PROPERTY_STATIC_MODULE_PORT_NUM_PREFIX = "com.google.appengine.devappserver_module.";

  private static final String SECRET_LOCATION = "/etc/appscale/secret.key";

  private static final String COOKIE_SECRET_PROPERTY = "COOKIE_SECRET";

  private final Action startAction = new DevAppServerMain.StartAction( );

  private String versionCheckServer = AppengineSdk.getSdk( ).getDefaultServer( );

  private String address = "localhost";

  private int port = 8080;

  private boolean disableUpdateCheck;

  private String generatedDirectory = null;

  private String defaultGcsBucketName = null;

  @VisibleForTesting
  List<Option> getBuiltInOptions( ) {
    List<Option> options = new ArrayList<>( );
    options.addAll( this.getSharedOptions( ) );
    options.addAll( Arrays.asList( new Option( "s", "server", false ) {
      public void apply( ) {
        DevAppServerMain.this.versionCheckServer = this.getValue( );
      }

      public List<String> getHelpLines( ) {
        return ImmutableList.of(
            " --server=SERVER            The server to use to determine the latest",
            "  -s SERVER                   SDK version." );
      }
    }, new Option( "a", "address", false ) {
      public void apply( ) {
        DevAppServerMain.this.address = this.getValue( );
      }

      public List<String> getHelpLines( ) {
        return ImmutableList.of(
            " --address=ADDRESS          The address of the interface on the local machine",
            "  -a ADDRESS                  to bind to (or 0.0.0.0 for all interfaces)." );
      }
    }, new Option( "p", "port", false ) {
      public void apply( ) {
        DevAppServerMain.this.port = Integer.valueOf( this.getValue( ) );
      }

      public List<String> getHelpLines( ) {
        return ImmutableList.of(
            " --port=PORT                The port number to bind to on the local machine.",
            "  -p PORT" );
      }
    }, new Option( null, "disable_update_check", true ) {
      public void apply( ) {
        DevAppServerMain.this.disableUpdateCheck = true;
      }

      public List<String> getHelpLines( ) {
        return ImmutableList.of( " --disable_update_check     Disable the check for newer SDK versions." );
      }
    }, new Option( null, "generated_dir", false ) {
      public void apply( ) {
        DevAppServerMain.this.generatedDirectory = this.getValue( );
      }

      public List<String> getHelpLines( ) {
        return ImmutableList.of( " --generated_dir=DIR        Set the directory where generated files are created." );
      }
    }, new Option( null, "default_gcs_bucket", false ) {
      public void apply( ) {
        DevAppServerMain.this.defaultGcsBucketName = this.getValue( );
      }

      public List<String> getHelpLines( ) {
        return ImmutableList.of( " --default_gcs_bucket=NAME  Set the default Google Cloud Storage bucket name." );
      }
    }, new Option( null, "instance_port", false ) {
      public void apply( ) {
        DevAppServerMain.processInstancePorts( this.getValues( ) );
      }
    }, new Option( null, "disable_filesapi_warning", true ) {
      public void apply( ) {
        System.setProperty( "appengine.disableFilesApiWarning", "true" );
      }
    }, new Option( null, "enable_filesapi", true ) {
      public void apply( ) {
        System.setProperty( "appengine.enableFilesApi", "true" );
      }
    }, new Option( null, "pidfile", false ) {
      public void apply( ) {
        System.setProperty( "appscale.pidfile", this.getValue( ) );
      }
    } ) );
    return options;
  }

  private static void processInstancePorts( List<String> optionValues ) {
    for ( final String optionValue : optionValues ) {
      String[] keyAndValue = optionValue.split( "=", 2 );
      if ( keyAndValue.length != 2 ) {
        reportBadInstancePortValue( optionValue );
      }

      try {
        Integer.parseInt( keyAndValue[ 1 ] );
      } catch ( NumberFormatException var5 ) {
        reportBadInstancePortValue( optionValue );
      }

      String var4 = keyAndValue[ 0 ].trim( );
      System.setProperty( "com.google.appengine.devappserver_module." + var4 + ".port", keyAndValue[ 1 ].trim( ) );
    }
  }

  private static void reportBadInstancePortValue( String optionValue ) {
    String var10003 = String.valueOf( optionValue );
    String var10002;
    if ( var10003.length( ) != 0 ) {
      var10002 = "Invalid instance_port value ".concat( var10003 );
    } else {
      var10002 = "Invalid instance_port value ";
    }

    throw new IllegalArgumentException( var10002 );
  }

  private List<Option> buildOptions( ) {
    return this.getBuiltInOptions( );
  }

  public static void main( String[] args ) {
    SharedMain.sharedInit( );
    ( new DevAppServerMain( ) ).run( args );
  }

  public DevAppServerMain( ) {
  }

  public void run( String[] args ) {
    Parser parser = new Parser( );
    ParseResult result = parser.parseArgs( this.startAction, this.buildOptions( ), args );
    result.applyArgs( );
  }

  public void printHelp( PrintStream out ) {
    out.println( "Usage: <dev-appserver> [options] <app directory> [<appn directory> ...]" );
    out.println( );
    out.println( "Options:" );

    for ( final Option option : this.buildOptions( ) ) {
      for ( final String helpString : option.getHelpLines( ) ) {
        out.println( helpString );
      }
    }

    out.println( " --jvm_flag=FLAG            Pass FLAG as a JVM argument. May be repeated to" );
    out.println( "                              supply multiple flags." );
  }

  public static void recursiveDelete( File dead ) {
    File[] files = dead.listFiles( );
    if ( files != null ) {
      for ( File name : files ) {
        recursiveDelete( name );
      }
    }

    dead.delete( );
  }

  static File constructTemporaryEARDirectory( List<String> services ) throws IOException {
    final File path = Files.createTempDirectory( "tmpEarArea" ).toFile( );
    Runtime.getRuntime( ).addShutdownHook( new Thread( ( ) -> DevAppServerMain.recursiveDelete( path ) ) );
    File metaInf = new File( path, "META-INF" );
    metaInf.mkdir( );
    Writer fw = Files.newBufferedWriter( ( new File( metaInf, "application.xml" ) ).toPath( ), StandardCharsets.UTF_8 );

    try {
      fw.write( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" );
      fw.write( "<application " );
      fw.write( "xmlns=\"http://java.sun.com/xml/ns/javaee\" " );
      fw.write( "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " );
      fw.write( "xsi:schemaLocation=\"http://java.sun.com/xml/ns/javaee " );
      fw.write( "http://java.sun.com/xml/ns/javaee/application_5.xsd\" " );
      fw.write( "version=\"5\">" );
      fw.write( "<display-name>appengine-modules-ear</display-name>" );
      TreeSet<String> contextRootNames = new TreeSet<>( );
      Iterator var6 = services.iterator( );

      while ( true ) {
        if ( !var6.hasNext( ) ) {
          fw.write( "</application>" );
          break;
        }

        String service = (String) var6.next( );
        File serviceFile = new File( service );
        fw.write( "<module>" );
        fw.write( "<web>" );
        String contextRoot = String.valueOf( serviceFile.toURI( ) );
        fw.write( "<web-uri>" + contextRoot + "</web-uri>" );
        contextRoot = serviceFile.getName( );

        String var11;
        int var12;
        for ( int var10 = 0; contextRootNames.contains( contextRoot ); contextRoot = var11 + var12 ) {
          var11 = contextRoot;
          var12 = var10++;
        }

        contextRootNames.add( contextRoot );
        fw.write( "<context-root>/" + contextRoot + "</context-root>" );
        fw.write( "</web>" );
        fw.write( "</module>" );
      }
    } finally {
      fw.close( );

    }

    fw = Files.newBufferedWriter( ( new File( metaInf, "appengine-application.xml" ) ).toPath( ), StandardCharsets.UTF_8 );

    try {
      fw.write( "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"no\"?>\n" );
      fw.write( "<appengine-application xmlns=\"http://appengine.google.com/ns/1.0\">" );
      fw.write( "<application>localdevapp</application></appengine-application>" );
    } finally {
      fw.close( );
    }

    return path;
  }

  class StartAction extends Action {

    StartAction( ) {
      super( "start" );
    }

    public void apply( ) {
      List<String> args = this.getArgs( );

      try {
        if ( args.isEmpty( ) ) {
          DevAppServerMain.this.printHelp( System.err );
          System.exit( 1 );
        }

        for ( final Object arg : args ) {
          String path = (String) arg;
          if ( !( new File( path ) ).exists( ) ) {
            System.out.println( path.concat( " does not exist." ) );
            DevAppServerMain.this.printHelp( System.err );
            System.exit( 1 );
          }
        }

        File appDir;
        if ( args.size( ) == 1 ) {
          appDir = ( new File( args.get( 0 ) ) ).getCanonicalFile( );
        } else {
          appDir = DevAppServerMain.constructTemporaryEARDirectory( args );
        }

        DevAppServerMain.this.validateWarPath( appDir );
        DevAppServerMain.this.configureRuntime( appDir );
        UpdateCheck updateCheck = new UpdateCheck( DevAppServerMain.this.versionCheckServer, appDir, true );
        if ( updateCheck.allowedToCheckForUpdates( ) && !DevAppServerMain.this.disableUpdateCheck ) {
          updateCheck.maybePrintNagScreen( System.err );
        }

        updateCheck.checkJavaVersion( System.err );
        boolean installSecurityManager = !Boolean.getBoolean( "use_jetty9_runtime" );

        // AppScale: Write a pidfile for Monit.
        String pidfile = System.getProperty( "appscale.pidfile" );
        if ( pidfile != null ) {
          String pidString = ManagementFactory.getRuntimeMXBean( ).getName( ).split( "@" )[ 0 ];
          Path file = Paths.get( pidfile );
          Files.write( file, pidString.getBytes( ) );
        }
        // AppScale: Set secret for login cookie processing.
        setSecret( );

        DevAppServer server = ( new DevAppServerFactory( ) ).createDevAppServer( appDir, null, null, DevAppServerMain.this.address, DevAppServerMain.this.port, true, installSecurityManager, ImmutableMap.of( ), DevAppServerMain.this.getNoJavaAgent( ) );
        Map<String, String> stringProperties = DevAppServerMain.this.getSystemProperties( );
        this.setGeneratedDirectory( stringProperties );
        this.setRdbmsPropertiesFile( stringProperties, appDir );
        DevAppServerMain.this.postServerActions( stringProperties );
        this.setDefaultGcsBucketName( stringProperties );
        DevAppServerMain.this.addPropertyOptionToProperties( stringProperties );
        server.setServiceProperties( stringProperties );

        try {
          server.start( ).await( );
        } catch ( InterruptedException ignored ) {
        }

        System.out.println( "Shutting down." );
        System.exit( 0 );
      } catch ( Exception var9 ) {
        var9.printStackTrace( );
        System.exit( 1 );
      }
    }

    private void setSecret( ) {
      try {
        String value = asCharSource( new File( SECRET_LOCATION ), StandardCharsets.UTF_8 ).read( );
        System.setProperty( COOKIE_SECRET_PROPERTY, value );
      } catch ( IOException e ) {
        System.out.println( "Error reading secret file " + e );
      }
    }

    private void setGeneratedDirectory( Map<String, String> stringProperties ) {
      if ( DevAppServerMain.this.generatedDirectory != null ) {
        File dir = new File( DevAppServerMain.this.generatedDirectory );
        String error = null;
        if ( dir.exists( ) ) {
          if ( !dir.isDirectory( ) ) {
            error = String.valueOf( DevAppServerMain.this.generatedDirectory ).concat( " is not a directory." );
          } else if ( !dir.canWrite( ) ) {
            error = String.valueOf( DevAppServerMain.this.generatedDirectory ).concat( " is not writable." );
          }
        } else if ( !dir.mkdirs( ) ) {
          String var10001 = String.valueOf( DevAppServerMain.this.generatedDirectory );
          String var10000;
          if ( var10001.length( ) != 0 ) {
            var10000 = "Could not make ".concat( var10001 );
          } else {
            var10000 = "Could not make ";
          }

          error = var10000;
        }

        if ( error != null ) {
          System.err.println( error );
          System.exit( 1 );
        }

        stringProperties.put( "appengine.generated.dir", DevAppServerMain.this.generatedDirectory );
      }
    }

    private void setDefaultGcsBucketName( Map<String, String> stringProperties ) {
      if ( DevAppServerMain.this.defaultGcsBucketName != null ) {
        stringProperties.put( "appengine.default.gcs.bucket.name", DevAppServerMain.this.defaultGcsBucketName );
      }
    }

    private void setRdbmsPropertiesFile( Map<String, String> stringProperties, File appDir ) {
      if ( stringProperties.get( "rdbms.properties.file" ) == null ) {
        File file = this.findRdbmsPropertiesFile( appDir );
        if ( file != null ) {
          String path = file.getPath( );
          PrintStream var10000 = System.out;
          String var10001;
          if ( path.length( ) != 0 ) {
            var10001 = "Reading local rdbms properties from ".concat( path );
          } else {
            var10001 = "Reading local rdbms properties from ";
          }

          var10000.println( var10001 );
          stringProperties.put( "rdbms.properties.file", path );
        }
      }
    }

    private File findRdbmsPropertiesFile( File dir ) {
      File candidate = new File( dir, ".local.rdbms.properties" );
      return candidate.isFile( ) && candidate.canRead( ) ? candidate : null;
    }
  }
}

