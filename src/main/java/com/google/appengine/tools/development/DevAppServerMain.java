package com.google.appengine.tools.development;

import com.google.appengine.repackaged.com.google.common.annotations.VisibleForTesting;
import com.google.appengine.repackaged.com.google.common.collect.ImmutableList;
import com.google.appengine.repackaged.com.google.common.collect.ImmutableMap;
import com.google.appengine.tools.info.AppengineSdk;
import com.google.appengine.tools.info.UpdateCheck;
import com.google.appengine.tools.util.Action;
import com.google.appengine.tools.util.Option;
import com.google.appengine.tools.util.Parser;
import com.google.appengine.tools.util.Parser.ParseResult;
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

public class DevAppServerMain extends SharedMain {
    public static final String GENERATE_WAR_ARG = "generate_war";
    public static final String GENERATED_WAR_DIR_ARG = "generated_war_dir";
    private static final String DEFAULT_RDBMS_PROPERTIES_FILE = ".local.rdbms.properties";
    private static final String RDBMS_PROPERTIES_FILE_SYSTEM_PROPERTY = "rdbms.properties.file";
    private static final String SYSTEM_PROPERTY_STATIC_MODULE_PORT_NUM_PREFIX = "com.google.appengine.devappserver_module.";
    private final Action startAction = new DevAppServerMain.StartAction();
    private String versionCheckServer = AppengineSdk.getSdk().getDefaultServer();
    private String address = "localhost";
    private int port = 8080;
    private boolean disableUpdateCheck;
    private String generatedDirectory = null;
    private String defaultGcsBucketName = null;

    @VisibleForTesting
    List<Option> getBuiltInOptions() {
        List<Option> options = new ArrayList();
        options.addAll(this.getSharedOptions());
        options.addAll(Arrays.asList(new Option("s", "server", false) {
            public void apply() {
                DevAppServerMain.this.versionCheckServer = this.getValue();
            }

            public List<String> getHelpLines() {
                return ImmutableList.of(" --server=SERVER            The server to use to determine the latest", "  -s SERVER                   SDK version.");
            }
        }, new Option("a", "address", false) {
            public void apply() {
                DevAppServerMain.this.address = this.getValue();
            }

            public List<String> getHelpLines() {
                return ImmutableList.of(" --address=ADDRESS          The address of the interface on the local machine", "  -a ADDRESS                  to bind to (or 0.0.0.0 for all interfaces).");
            }
        }, new Option("p", "port", false) {
            public void apply() {
                DevAppServerMain.this.port = Integer.valueOf(this.getValue());
            }

            public List<String> getHelpLines() {
                return ImmutableList.of(" --port=PORT                The port number to bind to on the local machine.", "  -p PORT");
            }
        }, new Option((String)null, "disable_update_check", true) {
            public void apply() {
                DevAppServerMain.this.disableUpdateCheck = true;
            }

            public List<String> getHelpLines() {
                return ImmutableList.of(" --disable_update_check     Disable the check for newer SDK versions.");
            }
        }, new Option((String)null, "generated_dir", false) {
            public void apply() {
                DevAppServerMain.this.generatedDirectory = this.getValue();
            }

            public List<String> getHelpLines() {
                return ImmutableList.of(" --generated_dir=DIR        Set the directory where generated files are created.");
            }
        }, new Option((String)null, "default_gcs_bucket", false) {
            public void apply() {
                DevAppServerMain.this.defaultGcsBucketName = this.getValue();
            }

            public List<String> getHelpLines() {
                return ImmutableList.of(" --default_gcs_bucket=NAME  Set the default Google Cloud Storage bucket name.");
            }
        }, new Option((String)null, "instance_port", false) {
            public void apply() {
                DevAppServerMain.processInstancePorts(this.getValues());
            }
        }, new Option((String)null, "disable_filesapi_warning", true) {
            public void apply() {
                System.setProperty("appengine.disableFilesApiWarning", "true");
            }
        }, new Option((String)null, "enable_filesapi", true) {
            public void apply() {
                System.setProperty("appengine.enableFilesApi", "true");
            }
        }, new Option((String)null, "pidfile", false) {
            public void apply() {
                System.setProperty("appscale.pidfile", this.getValue());
            }
        }));
        return options;
    }

    private static void processInstancePorts(List<String> optionValues) {
        Iterator var1 = optionValues.iterator();

        while(var1.hasNext()) {
            String optionValue = (String)var1.next();
            String[] keyAndValue = optionValue.split("=", 2);
            if (keyAndValue.length != 2) {
                reportBadInstancePortValue(optionValue);
            }

            try {
                Integer.parseInt(keyAndValue[1]);
            } catch (NumberFormatException var5) {
                reportBadInstancePortValue(optionValue);
            }

            String var4 = keyAndValue[0].trim();
            System.setProperty((new StringBuilder(46 + String.valueOf(var4).length())).append("com.google.appengine.devappserver_module.").append(var4).append(".port").toString(), keyAndValue[1].trim());
        }

    }

    private static void reportBadInstancePortValue(String optionValue) {
        String var10003 = String.valueOf(optionValue);
        String var10002;
        if (var10003.length() != 0) {
            var10002 = "Invalid instance_port value ".concat(var10003);
        } else {
            var10002 = "Invalid instance_port value ";
        }

        IllegalArgumentException var10000 = new IllegalArgumentException(var10002);
        throw var10000;
    }

    private List<Option> buildOptions() {
        return this.getBuiltInOptions();
    }

    public static void main(String[] args) throws Exception {
        SharedMain.sharedInit();
        (new DevAppServerMain()).run(args);
    }

    public DevAppServerMain() {
    }

    public void run(String[] args) throws Exception {
        Parser parser = new Parser();
        ParseResult result = parser.parseArgs(this.startAction, this.buildOptions(), args);
        result.applyArgs();
    }

    public void printHelp(PrintStream out) {
        out.println("Usage: <dev-appserver> [options] <app directory> [<appn directory> ...]");
        out.println("");
        out.println("Options:");
        Iterator var2 = this.buildOptions().iterator();

        while(var2.hasNext()) {
            Option option = (Option)var2.next();
            Iterator var4 = option.getHelpLines().iterator();

            while(var4.hasNext()) {
                String helpString = (String)var4.next();
                out.println(helpString);
            }
        }

        out.println(" --jvm_flag=FLAG            Pass FLAG as a JVM argument. May be repeated to");
        out.println("                              supply multiple flags.");
    }

    public static void recursiveDelete(File dead) {
        File[] files = dead.listFiles();
        if (files != null) {
            File[] var2 = files;
            int var3 = files.length;

            for(int var4 = 0; var4 < var3; ++var4) {
                File name = var2[var4];
                recursiveDelete(name);
            }
        }

        dead.delete();
    }

    static File constructTemporaryEARDirectory(List<String> services) throws IOException {
        final File path = Files.createTempDirectory("tmpEarArea").toFile();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                DevAppServerMain.recursiveDelete(path);
            }
        });
        File metaInf = new File(path, "META-INF");
        metaInf.mkdir();
        Writer fw = Files.newBufferedWriter((new File(metaInf, "application.xml")).toPath(), StandardCharsets.UTF_8);
        Throwable var4 = null;

        try {
            fw.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            fw.write("<application ");
            fw.write("xmlns=\"http://java.sun.com/xml/ns/javaee\" ");
            fw.write("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ");
            fw.write("xsi:schemaLocation=\"http://java.sun.com/xml/ns/javaee ");
            fw.write("http://java.sun.com/xml/ns/javaee/application_5.xsd\" ");
            fw.write("version=\"5\">");
            fw.write("<display-name>appengine-modules-ear</display-name>");
            TreeSet<String> contextRootNames = new TreeSet();
            Iterator var6 = services.iterator();

            while(true) {
                if (!var6.hasNext()) {
                    fw.write("</application>");
                    break;
                }

                String service = (String)var6.next();
                File serviceFile = new File(service);
                fw.write("<module>");
                fw.write("<web>");
                String contextRoot = String.valueOf(serviceFile.toURI());
                fw.write((new StringBuilder(19 + String.valueOf(contextRoot).length())).append("<web-uri>").append(contextRoot).append("</web-uri>").toString());
                contextRoot = serviceFile.getName();

                String var11;
                int var12;
                for(int var10 = 0; contextRootNames.contains(contextRoot); contextRoot = (new StringBuilder(11 + String.valueOf(var11).length())).append(var11).append(var12).toString()) {
                    var11 = String.valueOf(contextRoot);
                    var12 = var10++;
                }

                contextRootNames.add(contextRoot);
                fw.write((new StringBuilder(30 + String.valueOf(contextRoot).length())).append("<context-root>/").append(contextRoot).append("</context-root>").toString());
                fw.write("</web>");
                fw.write("</module>");
            }
        } catch (Throwable var25) {
            var4 = var25;
            throw var25;
        } finally {
            if (fw != null) {
                fw.close();
            }

        }

        fw = Files.newBufferedWriter((new File(metaInf, "appengine-application.xml")).toPath(), StandardCharsets.UTF_8);
        var4 = null;

        try {
            fw.write("<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"no\"?>\n");
            fw.write("<appengine-application xmlns=\"http://appengine.google.com/ns/1.0\">");
            fw.write("<application>localdevapp</application></appengine-application>");
        } catch (Throwable var23) {
            var4 = var23;
            throw var23;
        } finally {
            if (fw != null) {
                fw.close();
            }

        }

        return path;
    }

    class StartAction extends Action {
        StartAction() {
            super(new String[]{"start"});
        }

        public void apply() {
            List args = this.getArgs();

            try {
                if (args.isEmpty()) {
                    DevAppServerMain.this.printHelp(System.err);
                    System.exit(1);
                }

                Iterator var2 = args.iterator();

                while(var2.hasNext()) {
                    String path = (String)var2.next();
                    if (!(new File(path)).exists()) {
                        System.out.println(String.valueOf(path).concat(" does not exist."));
                        DevAppServerMain.this.printHelp(System.err);
                        System.exit(1);
                    }
                }

                File appDir;
                if (args.size() == 1) {
                    appDir = (new File((String)args.get(0))).getCanonicalFile();
                } else {
                    appDir = DevAppServerMain.constructTemporaryEARDirectory(args);
                }

                DevAppServerMain.this.validateWarPath(appDir);
                DevAppServerMain.this.configureRuntime(appDir);
                UpdateCheck updateCheck = new UpdateCheck(DevAppServerMain.this.versionCheckServer, appDir, true);
                if (updateCheck.allowedToCheckForUpdates() && !DevAppServerMain.this.disableUpdateCheck) {
                    updateCheck.maybePrintNagScreen(System.err);
                }

                updateCheck.checkJavaVersion(System.err);
                boolean installSecurityManager = !Boolean.getBoolean("use_jetty9_runtime");

                // AppScale: Write a pidfile for Monit.
                String pidfile = System.getProperty("appscale.pidfile");
                if (pidfile != null) {
                    String pidString = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
                    Path file = Paths.get(pidfile);
                    Files.write(file, pidString.getBytes());
                }

                DevAppServer server = (new DevAppServerFactory()).createDevAppServer(appDir, (File)null, (File)null, DevAppServerMain.this.address, DevAppServerMain.this.port, true, installSecurityManager, ImmutableMap.of(), DevAppServerMain.this.getNoJavaAgent());
                Map<String, String> stringProperties = DevAppServerMain.this.getSystemProperties();
                this.setGeneratedDirectory(stringProperties);
                this.setRdbmsPropertiesFile(stringProperties, appDir);
                DevAppServerMain.this.postServerActions(stringProperties);
                this.setDefaultGcsBucketName(stringProperties);
                DevAppServerMain.this.addPropertyOptionToProperties(stringProperties);
                server.setServiceProperties(stringProperties);

                try {
                    server.start().await();
                } catch (InterruptedException var8) {
                    ;
                }

                System.out.println("Shutting down.");
                System.exit(0);
            } catch (Exception var9) {
                var9.printStackTrace();
                System.exit(1);
            }

        }

        private void setGeneratedDirectory(Map<String, String> stringProperties) {
            if (DevAppServerMain.this.generatedDirectory != null) {
                File dir = new File(DevAppServerMain.this.generatedDirectory);
                String error = null;
                if (dir.exists()) {
                    if (!dir.isDirectory()) {
                        error = String.valueOf(DevAppServerMain.this.generatedDirectory).concat(" is not a directory.");
                    } else if (!dir.canWrite()) {
                        error = String.valueOf(DevAppServerMain.this.generatedDirectory).concat(" is not writable.");
                    }
                } else if (!dir.mkdirs()) {
                    String var10001 = String.valueOf(DevAppServerMain.this.generatedDirectory);
                    String var10000;
                    if (var10001.length() != 0) {
                        var10000 = "Could not make ".concat(var10001);
                    } else {
                        var10000 = "Could not make ";
                    }

                    error = var10000;
                }

                if (error != null) {
                    System.err.println(error);
                    System.exit(1);
                }

                stringProperties.put("appengine.generated.dir", DevAppServerMain.this.generatedDirectory);
            }

        }

        private void setDefaultGcsBucketName(Map<String, String> stringProperties) {
            if (DevAppServerMain.this.defaultGcsBucketName != null) {
                stringProperties.put("appengine.default.gcs.bucket.name", DevAppServerMain.this.defaultGcsBucketName);
            }

        }

        private void setRdbmsPropertiesFile(Map<String, String> stringProperties, File appDir) {
            if (stringProperties.get("rdbms.properties.file") == null) {
                File file = this.findRdbmsPropertiesFile(appDir);
                if (file != null) {
                    String path = file.getPath();
                    PrintStream var10000 = System.out;
                    String var10002 = String.valueOf(path);
                    String var10001;
                    if (var10002.length() != 0) {
                        var10001 = "Reading local rdbms properties from ".concat(var10002);
                    } else {
                        var10001 = "Reading local rdbms properties from ";
                    }

                    var10000.println(var10001);
                    stringProperties.put("rdbms.properties.file", path);
                }

            }
        }

        private File findRdbmsPropertiesFile(File dir) {
            File candidate = new File(dir, ".local.rdbms.properties");
            return candidate.isFile() && candidate.canRead() ? candidate : null;
        }
    }
}

