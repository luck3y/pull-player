package org.jboss.pull.player;

import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import org.jboss.dmr.ModelNode;

/**
 * @author Tomaz Cerar (c) 2013 Red Hat Inc.
 */
public class TeamCityApi {
    private final HttpClient httpClient;
    private final String baseUrl;
    private final Map<String, String> branchMapping = new HashMap<>();
    private final boolean dryRun;
    private final boolean disabled;

    public TeamCityApi(String host, int port, String username, String password, String branchMapping, boolean dryRun,
                       boolean disabled) throws Exception {
        if (port == 443) {
            this.baseUrl = "https://" + host + "/httpAuth";
        } else {
            this.baseUrl = "http://" + host + ":" + port + "/httpAuth";
        }
       /* this.username = username;
        this.password = password;*/
        this.dryRun = dryRun;
        this.disabled = disabled;
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new X509TrustManager[] {new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain,
                                           String authType) throws CertificateException {
            }

            public void checkServerTrusted(X509Certificate[] chain,
                                           String authType) throws CertificateException {
            }

            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new SecureRandom());
        httpClient = HttpClient.newBuilder()
                .authenticator(new Authenticator() {
                    @Override
                    public PasswordAuthentication requestPasswordAuthenticationInstance(final String host,
                                                                                        final InetAddress addr,
                                                                                        final int port,
                                                                                        final String protocol,
                                                                                        final String prompt,
                                                                                        final String scheme,
                                                                                        final URL url,
                                                                                        final RequestorType reqType) {
                        return new PasswordAuthentication(username, password.toCharArray());
                    }
                })
                .sslContext(context)
                .build();
        parseBranchMapping(branchMapping);
    }

    public TeamCityApi(String host, int port, String username, String password, String branchMapping, boolean dryRun)
            throws Exception {
        this(host, port, username, password, branchMapping, dryRun, false);
    }

    private void parseBranchMapping(String mappings) {
        for (String mapping : mappings.split(",")) {
            String[] parts = mapping.split("=>");
            branchMapping.put(parts[0].trim(), parts[1].trim());
        }
        System.out.println("branchMapping = " + branchMapping);
    }

    private List<Integer> getQueuedBuildsInternally(String buildTypeId) {
        List<Integer> result = new LinkedList<>();

        if (disabled) {
            System.err.printf("Warning: TeamCity has been disabled via player.properties no queued build information is available.%n");
            return result;
        }
        try {
            //get = new HttpGet(baseUrl + "/app/rest/builds?locator=buildType:" + buildTypeId + ",branch:name:pull/" + pull + ",running:any,count:1");
            final HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/app/rest/buildQueue?locator=buildType:" + buildTypeId))
                    .GET()
                    .header("Accept-Encoding", "UTF-8")
                    .header("Accept", "application/json")
                    .build();
            final var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                System.err.println("Could not queued builds");
            }

            ModelNode node = ModelNode.fromJSONStream(response.body());
            ModelNode builds = node.get("build");
            if (!builds.isDefined() || builds.asList().isEmpty()) {
                return result;
            } else {
                for (ModelNode build : builds.asList()) {
                    if (!build.hasDefined("branchName")) {
                        continue;
                    }
                    String branch = build.get("branchName").asString();
                    if (!branch.contains("pull")) {
                        continue;
                    }
                    int pull = Integer.parseInt(branch.substring(branch.indexOf("/") + 1));
                    result.add(pull);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not obtain build list", e);
        }
        return result;
    }

    List<Integer> getQueuedBuilds() {
        List<Integer> result = new LinkedList<>();
        if (disabled) {
            System.err.printf("Warning: TeamCity has been disabled via player.properties no queued build information is available.%n");
            return result;
        }
        for (String buildType : branchMapping.values()) {
            result.addAll(getQueuedBuildsInternally(buildType));
        }
        return result;
    }

    protected boolean hasBranchMapping(String branch) {
        return branchMapping.containsKey(branch);
    }

    public TeamCityBuild findBuild(int pull, String hash, String branch) {
        if (disabled) {
            System.err.printf("Warning: TeamCity has been disabled via player.properties, dummy build information will be used.%n");
            return null;
            //return new TeamCityBuild(0, "disabled", false, "20160101T130000+0000");
        }

        String buildTypeId = branchMapping.get(branch);
        try {
            final HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/app/rest/builds?locator=buildType:" + buildTypeId + ",branch:name:pull/" + pull + ",running:any,canceled:any,failedToStart:any,count:1"))
                    .GET()
                    .header("Accept-Encoding", "UTF-8")
                    .header("Accept", "application/json")
                    .build();
            final var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                System.err.printf("Could not find build, for pull: %s%n", pull);
            }

            ModelNode node = ModelNode.fromJSONStream(response.body());
            ModelNode builds = node.get("build");
            if (!builds.isDefined() || builds.asList().isEmpty()) {
                return null;
            } else {
                ModelNode buildNode = builds.asList().get(0);
                String buildId = buildNode.get("id").asString();
                return getBuildById(buildId, hash);
            }


        } catch (Exception e) {
            throw new IllegalStateException("Could not obtain build list", e);
        }
    }

    private TeamCityBuild getBuildById(String id, String hash) {
        try {
            final HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/app/rest/builds/id:" + id))
                    .GET()
                    .header("Accept-Encoding", "UTF-8")
                    .header("Accept", "application/json")
                    .build();
            final var execute = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (execute.statusCode() != HttpURLConnection.HTTP_OK) {
                System.err.printf("Could not find build, for id: %s%n", id);
            }

            ModelNode build = ModelNode.fromJSONStream(execute.body());
            boolean found = false;
            for (ModelNode prop : build.get("properties", "property").asList()) {
                if ("hash".equals(prop.get("name").asString())) {
                    String value = prop.get("value").asString();
                    found = hash.equals(value);
                    break;
                }
            }
            System.out.println("Hash for last build matches: " + found);
            if (found) {
                String number = build.get("number").asString();
                final int num;
                try {//number can be N/A if it is no longer present on server
                    num = Integer.parseInt(number);
                } catch (NumberFormatException e) {
                    return null;
                }


                /*
                    "queuedDate" => "20150903T162504+0200",
                    "startDate" => "20150903T172007+0200",
                    "finishDate" => "20150903T172008+0200",
                 */

                return new TeamCityBuild(num, build.get("status").asString(),
                        build.hasDefined("running") && build.get("running").asBoolean(),
                        build.get("queuedDate").asString());
            } else {
                return null;
            }


        } catch (Exception e) {
            throw new IllegalStateException("Could not obtain build list", e);
        }
    }

    void triggerJob(int pull, String sha1, String branch) {
        if (disabled) {
            System.err.printf("Warning: TeamCity has been disabled via player.properties, build will not be triggered.%n");
            return;
        }
        System.out.println("triggering job for pull = " + pull);
        String buildTypeId = branchMapping.get(branch);
        if (dryRun) {
            System.out.printf("DryRun, not triggering for branch: '%s' build type id: '%s'%n", branch, buildTypeId);
            return;
        }

        try {
            ModelNode build = new ModelNode();
            build.get("branchName").set("pull/" + pull);
            ModelNode buildType = build.get("buildType");
            buildType.get("id").set(buildTypeId);
            ModelNode props = build.get("properties", "property");
            ModelNode prop = props.add();
            prop.get("name").set("hash");
            prop.get("value").set(sha1);
            prop = props.add();
            prop.get("name").set("pull");
            prop.get("value").set(pull);
            prop = props.add();
            prop.get("name").set("branch");
            prop.get("value").set(branch);
            final HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/app/rest/buildQueue"))
                    .POST(HttpRequest.BodyPublishers.ofString(build.toJSONString(false)))
                    .header("Accept-Encoding", "UTF-8")
                    .header("Accept", "application/json")
                    .build();
            final var execute = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (execute.statusCode() != HttpURLConnection.HTTP_OK) {
                System.err.printf("Problem triggering build for pull: %d sha1: %s%nResponse: %s %n", pull, sha1, execute.statusCode());
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
