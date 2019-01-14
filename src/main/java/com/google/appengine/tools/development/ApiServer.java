package com.google.appengine.tools.development;

import com.google.appengine.repackaged.com.google.common.base.Splitter;
import com.google.appengine.repackaged.org.apache.commons.httpclient.HttpClient;
import com.google.appengine.repackaged.org.apache.commons.httpclient.methods.ByteArrayRequestEntity;
import com.google.appengine.repackaged.org.apache.commons.httpclient.methods.GetMethod;
import com.google.appengine.repackaged.org.apache.commons.httpclient.methods.PostMethod;
import com.google.apphosting.utils.remoteapi.RemoteApiPb.Request;
import com.google.apphosting.utils.remoteapi.RemoteApiPb.Response;
import com.google.apphosting.utils.runtime.ApiProxyUtils;
import com.google.net.util.proto2api.Status.StatusProto;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.ws.http.HTTPException;

public class ApiServer {
    private static final Logger logger = Logger.getLogger(ApiServer.class.getName());
    private final Process process;
    private final int port;
    private final ApiServer.ServerOutput serverOutput;

    ApiServer(String pathToApiServer, String applicationName) {
        try {
            ServerSocket socket = new ServerSocket(0);
            Throwable var4 = null;

            try {
                this.port = socket.getLocalPort();
            } catch (Throwable var14) {
                var4 = var14;
                throw var14;
            } finally {
                if (var4 != null) {
                    try {
                        socket.close();
                    } catch (Throwable var13) {
                        var4.addSuppressed(var13);
                    }
                } else {
                    socket.close();
                }

            }

            String[] var10002 = new String[]{
                    pathToApiServer,
                    "--api_port", String.valueOf(this.port),
                    "--application", applicationName
            };
            List<String> apiServerCommand = new ArrayList(Arrays.asList(var10002));
            if (System.getProperty("appengine.pythonApiServerFlags") != null) {
                Iterator var19 = Splitter.on('|').split(System.getProperty("appengine.pythonApiServerFlags")).iterator();

                while(var19.hasNext()) {
                    String apiServerFlag = (String)var19.next();
                    apiServerCommand.add(apiServerFlag);
                }
            }

            ProcessBuilder pb = (new ProcessBuilder(new String[0])).command(apiServerCommand).redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            String datastoreEmulatorHost = System.getenv("DATASTORE_EMULATOR_HOST");
            if (datastoreEmulatorHost != null) {
                env.put("DATASTORE_EMULATOR_HOST", datastoreEmulatorHost);
            }

            this.process = pb.start();
            this.serverOutput = new ApiServer.ServerOutput(this.process);
            this.serverOutput.start();
            this.serverOutput.await();
        } catch (IOException var16) {
            throw new RuntimeException(var16);
        }
    }

    public Integer getPort() {
        return this.port;
    }

    public void clear() throws IOException {
        HttpClient httpClient = new HttpClient();
        int returnCode = this.port;
        GetMethod request = new GetMethod((new StringBuilder(34)).append("http://localhost:").append(returnCode).append("/clear").toString());
        returnCode = httpClient.executeMethod(request);
        if (returnCode != 200) {
            throw new IOException((new StringBuilder(78)).append("Sending HTTP request to clear the API server failed with response: ").append(returnCode).toString());
        }
    }

    public void close() {
        try {
            int exitValue = this.process.exitValue();
            if (exitValue != 0) {
                logger.logp(Level.WARNING, "com.google.appengine.tools.development.ApiServer", "close", (new StringBuilder(67)).append("The API server process exited with a non-zero value of: ").append(exitValue).toString());
            }
        } catch (IllegalThreadStateException var2) {
            this.serverOutput.stopReadingOutput();
            this.process.destroy();
        }

    }

    public byte[] makeSyncCall(String packageName, String methodName, byte[] requestBytes) throws IOException {
        Request remoteApiRequest = new Request();
        remoteApiRequest.setServiceName(packageName);
        remoteApiRequest.setMethod(methodName);
        remoteApiRequest.setRequestAsBytes(requestBytes);
        remoteApiRequest.setRequestId(UUID.randomUUID().toString().substring(0, 10));
        byte[] remoteApiRequestBytes = ApiUtils.convertPbToBytes(remoteApiRequest);
        int var7 = this.port;
        PostMethod post = new PostMethod((new StringBuilder(28)).append("http://localhost:").append(var7).toString());
        post.setFollowRedirects(false);
        post.addRequestHeader("Host", "localhost");
        post.addRequestHeader("Content-Type", "application/octet-stream");
        post.setRequestEntity(new ByteArrayRequestEntity(remoteApiRequestBytes));
        boolean oldNativeSocketMode = DevSocketImplFactory.isNativeSocketMode();
        DevSocketImplFactory.setSocketNativeMode(true);

        try {
            HttpClient httpClient = new HttpClient();
            httpClient.executeMethod(post);
            if (post.getStatusCode() != 200) {
                throw new HTTPException(post.getStatusCode());
            }
        } catch (IOException var12) {
            throw new IOException("Error executing POST to HTTP API server.");
        } finally {
            DevSocketImplFactory.setSocketNativeMode(oldNativeSocketMode);
        }

        Response response = new Response();
        boolean var9 = response.mergeFrom(post.getResponseBodyAsStream());
        if (!var9) {
            throw new IOException("Error parsing the response from the HTTP API server.");
        } else if (response.hasApplicationError()) {
            throw ApiProxyUtils.getRpcError(packageName, methodName, StatusProto.getDefaultInstance(), response.getApplicationError().getCode(), response.getApplicationError().getDetail());
        } else if (response.hasRpcError()) {
            throw ApiProxyUtils.getApiError(packageName, methodName, response, logger);
        } else {
            return response.getResponseAsBytes();
        }
    }

    private static class ServerOutput extends Thread {
        private final Process process;
        private final CountDownLatch readyLatch;
        private final String readyMessage;
        private final AtomicBoolean stopped;

        private ServerOutput(Process process) {
            this.readyLatch = new CountDownLatch(1);
            this.readyMessage = "Starting API server at:";
            this.stopped = new AtomicBoolean();
            this.process = process;
        }

        public void await() {
            try {
                this.readyLatch.await();
            } catch (InterruptedException var2) {
                throw new RuntimeException(var2);
            }
        }

        private void stopReadingOutput() {
            this.stopped.set(true);
        }

        public void run() {
            try {
                BufferedReader stdInput = new BufferedReader(new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8));

                String stdInputLine;
                while((stdInputLine = stdInput.readLine()) != null) {
                    System.out.println(stdInputLine);
                    if (stdInputLine.contains("Starting API server at:")) {
                        this.readyLatch.countDown();
                    }
                }
            } catch (IOException var3) {
                if (!this.stopped.get()) {
                    throw new RuntimeException(var3);
                }
            }

        }
    }
}

