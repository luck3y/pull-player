package org.jboss.pull.player;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;

/**
 * @author Tomaz Cerar (c) 2013 Red Hat Inc.
 */
public class GitHubApi {
    private static final int CACHE_SIZE = 1000;
    private static final String GITHUB_API_URL = "https://api.github.com";
    private final Path cacheFileName = Util.BASE_DIR.toPath().resolve("github-api.cache");
    private final HttpClient httpClient;
    private final String baseUrl;
    private final boolean dryRun;
    private final AtomicBoolean cacheDirty = new AtomicBoolean(false);
    private final Properties cache = getCache();

    public GitHubApi(String authToken, String repository, boolean dryRun) {
        this.dryRun = dryRun;
        this.baseUrl = GITHUB_API_URL + "/repos/" + repository;
        this.httpClient = HttpClient.newHttpClient();
    }

    private Properties getCache() {
        Properties p = new Properties();
        if (Files.exists(cacheFileName)) {
            try (Reader r = Files.newBufferedReader(cacheFileName, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                System.out.println("could not load cache");
                e.printStackTrace(System.out);
            }
        }
        if (p.size() > CACHE_SIZE) {
            System.out.println("Size of cache got too big, clearing out cache");
            p.clear();
            cacheDirty.set(true);
        }
        return p;
    }

    List<Comment> getComments(final String commentsUrl) {
        List<Comment> comments = new ArrayList<>();
        try {
            String url = commentsUrl;
            while (url != null) {
                final var response = execute(URI.create(url));
                url = nextLink(response);
                if (notModified(response)) {
                    return null;
                }
                ModelNode node = ModelNode.fromJSONStream(response.body());
                List<ModelNode> modelNodes = node.asList();
                if (modelNodes.size() == 0) {
                    continue;
                }
                /*
                   "created_at" => "2015-09-01T15:35:01Z",
                    "updated_at" => "2015-09-01T15:35:01Z",
                 */
                for (ModelNode comment : modelNodes) {
                    comments.add(create(comment));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        return comments;
    }

    /**
     * @return returns all pull requests that might need processing
     */
    List<ModelNode> getPullRequests() {
        List<ModelNode> result = new ArrayList<>();
        String lastCheck = cache.getProperty("LAST_CHECK", ZonedDateTime.ofInstant(Instant.EPOCH, ZoneId.systemDefault())
                .toString());
        try {
            String url = baseUrl + "/pulls?state=open";
            while (url != null) {
                final var response = execute(URI.create(url));
                url = nextLink(response);
                if (notModified(response)) {
                    continue;
                }
                ModelNode node = ModelNode.fromJSONStream(response.body());

                Instant last = ZonedDateTime.parse(lastCheck).toInstant();
                result.addAll(filterNonModifiedPullRequests(node.asList(), last));
            }
            updateLastCheck();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;

    }

    /**
     * @return returns all pull requests that might need processing
     */
    private ModelNode getPullRequest(String url) {
        try {
            final var response = execute(URI.create(url));
            return ModelNode.fromJSONStream(response.body());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return new ModelNode().setEmptyObject();

    }

    /**
     * @return returns the details of a particular pull request
     */
    ModelNode getPullRequestDetails(final int pullRequest) {
        try {
            String url = baseUrl + "/pulls/" + pullRequest;
            final var response = execute(URI.create(url));
            if (notModified(response)) {
                return null;
            }
            return ModelNode.fromJSONStream(response.body());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    void updateLastCheck() {
        cache.putIfAbsent("LAST_CHECK", ZonedDateTime.now().toString());
    }

    private List<ModelNode> filterNonModifiedPullRequests(List<ModelNode> pulls, Instant lastCheck) {
        final List<ModelNode> res = new LinkedList<>();
        for (ModelNode pull : pulls) {
            String updatedAtString = pull.get("updated_at").asString(); //get timestamp when was PR last updated.
            Instant updatedAt = ZonedDateTime.parse(updatedAtString).toInstant();
            if (updatedAt.isAfter(lastCheck)) {
                res.add(pull);
            }
        }
        return res;
    }

    private String nextLink(HttpResponse<?> response) {
        final Optional<String> linkHeader = response.headers().firstValue("link");
        if (linkHeader.isPresent()) {
            for (String headerValue : response.headers().allValues("link")) {
                // link: <https://api.github.com/repositories/1300192/issues?page=2>; rel="prev",
                //      <https://api.github.com/repositories/1300192/issues?page=4>; rel="next",
                //      <https://api.github.com/repositories/1300192/issues?page=515>; rel="last",
                //      <https://api.github.com/repositories/1300192/issues?page=1>; rel="first"
                final String[] parts = headerValue.split(",");
                for (String part : parts) {
                    if (part.contains("rel=\"next\"")) {
                        final int start = part.indexOf('<');
                        final int end = part.indexOf('>');
                        if (start >= 0 && end > (start + 1)) {
                            return part.substring(start + 1, end);
                        }
                    }
                }
            }
        }
        return null;
    }

    public void postComment(int number, String comment) {
        System.out.println("Posting: " + comment);
        if (this.dryRun) {
            System.out.println("Dry run - Not posting to github");
            return;
        }
        final String requestUrl = baseUrl + "/issues/" + number + "/comments";

        try {
            execute(URI.create(requestUrl), "POST", HttpRequest.BodyPublishers.ofString("{\"body\": \"" + comment + "\"}"));
            updateLastCheck();
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }


    public List<ModelNode> getIssuesWithPullRequests() throws IOException {
        final List<ModelNode> pulls = new LinkedList<>();
        try {
            // Get all the issues for the repository
            String url = baseUrl + "/issues?state=open&filter=all";
            while (url != null) {
                final var response = execute(URI.create(url));
                url = nextLink(response);
                if (notModified(response)) { //noting new to do
                    return Collections.emptyList();
                }
                ModelNode responseResult = ModelNode.fromJSONStream(response.body());
                for (ModelNode node : responseResult.asList()) {
                    // We only want issues with a pull request
                    if (node.hasDefined("pull_request") && node.hasDefined("labels")) {
                        String prUrl = node.get("pull_request", "url").asString();
                        ModelNode pr = getPullRequest(prUrl);
                        for (Property p : pr.asPropertyList()) {//lets just copy everything
                            node.get(p.getName()).set(p.getValue());
                        }
                        node.remove("user");
                        node.remove("head");
                        node.remove("repo");
                        node.remove("base");
                        node.remove("_links");
                        pulls.add(node);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        return pulls;
    }

    private ModelNode getLabelsForIssue(final List<ModelNode> allIssues, int number) {
        Optional<ModelNode> res = allIssues.stream()
                .filter(node -> node.get("number").asInt() == number)
                .findFirst();
        if (res.isPresent()) {
            return res.get().get("labels");
        }
        return new ModelNode().setEmptyList();
    }


    public void setLabels(final String issueUrl, final Collection<String> labels) {
        System.out.println("Setting labels for issue: " + issueUrl + ", labels: " + labels);
        if (this.dryRun) {
            System.out.println("Dry run - Not posting to github");
            return;
        }
        // Build a list of the new labels
        final String sb = getLabelsArray(labels);
        try {
            execute(URI.create(issueUrl + "/labels"), "POST", HttpRequest.BodyPublishers.ofString(sb));
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    public void addLabels(final String issueUrl, final Collection<String> labels) {
        System.out.println("adding labels for issue: " + issueUrl + ", labels: " + labels);
        if (this.dryRun) {
            System.out.println("Dry run - Not posting to github");
            return;
        }
        // Build a list of the new labels
        final String sb = getLabelsArray(labels);
        try {
            execute(URI.create(issueUrl + "/labels"), "POST", HttpRequest.BodyPublishers.ofString(sb));
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    private String getLabelsArray(Collection<String> labels) {
        final StringBuilder sb = new StringBuilder(32).append('[').append('\n');
        final int size = labels.size();
        int counter = 0;
        for (String label : labels) {
            sb.append('"').append(label).append('"');
            if (++counter < size) {
                sb.append(',');
            }
            sb.append("\n");
        }
        sb.append(']');
        return sb.toString();
    }

    private boolean notModified(HttpResponse<InputStream> response) {
        return response.statusCode() == HttpURLConnection.HTTP_NOT_MODIFIED;

    }

    private HttpResponse<InputStream> execute(final URI uri) throws IOException {
        return execute(uri, "GET", HttpRequest.BodyPublishers.noBody());
    }

    private HttpResponse<InputStream> execute(final URI uri, final String method, final
    HttpRequest.BodyPublisher publisher)
            throws IOException {

        final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .method(method, publisher)
                .header("Authorization", "Bearer " + Util.require("github.token"))
                .header("User-Agent", "WildFly-Pull-Player");

        requestBuilder.header("accept-encoding", "UTF-8");

        final String cacheKey = uri.toString();
        final String value = cache.getProperty(cacheKey);
        if (method.equalsIgnoreCase("GET") && value != null) {
            requestBuilder.header("If-None-Match", value);
        }

        final HttpRequest request = requestBuilder.build();
        final HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        final int responseStatus = response.statusCode();

        //System.out.println("Next page for "+request.getURI()+": " + nextLink(response));
        if (responseStatus == HttpURLConnection.HTTP_NOT_MODIFIED) {
            System.out.println("url " + response.uri() + " is not modified");
        } else {
            if (responseStatus != HttpURLConnection.HTTP_CREATED && responseStatus != HttpURLConnection.HTTP_OK && responseStatus != HttpURLConnection.HTTP_NO_CONTENT) {
                final ByteArrayOutputStream content = new ByteArrayOutputStream();
                final byte[] buffer = new byte[1024];
                try (InputStream in = response.body()) {
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        content.write(buffer, 0, len);
                    }
                }
                System.err.printf("Could not %s to %s %n\t%s: %s%n", request.method(), request.uri(), response.statusCode(), content.toString(StandardCharsets.UTF_8));
            }
            if (request.method().equalsIgnoreCase("GET")) {
                Optional<String> eTag = response.headers().firstValue("ETag");
                if (eTag.isEmpty()) {
                    System.out.println("ETag is not defined for uri: " + request.uri());
                } else {
                    cache.put(cacheKey, eTag.get());
                    cacheDirty.compareAndSet(false, true);
                }
            }
        }

        String remaining = response.headers().firstValue("X-RateLimit-Remaining").orElse(null);
        System.out.println("X-RateLimit-Remaining: " + remaining);

        /*for (Header h : response.getAllHeaders()){
            System.out.println(String.format("Header: %s",h));
        }*/
        return response;
    }

    public void close() throws IOException {
        if (cacheDirty.get()) {
            cache.store(Files.newBufferedWriter(cacheFileName, StandardCharsets.UTF_8, StandardOpenOption.CREATE), null);
        }
    }

    private Comment create(ModelNode comment) {
        String createdAt = comment.get("created_at").asString();
        return new Comment(comment.get("user", "login").asString(), comment.get("body")
                .asString(), createdAt, comment.get("id").asString());
    }


    List<Comment> getCommentsForIssue(final int issueId) {
        List<Comment> comments = new ArrayList<>();
        try {
            String url = baseUrl + "/issues/" + issueId + "/comments";
            while (url != null) {
                final var response = execute(URI.create(url));
                url = nextLink(response);
                if (notModified(response)) {
                    return null;
                }
                ModelNode node = ModelNode.fromJSONStream(response.body());
                List<ModelNode> modelNodes = node.asList();
                if (modelNodes.size() == 0) {
                    continue;
                }
                for (ModelNode comment : modelNodes) {
                    //comments.add(new Comment(comment.get("user", "login").asString(), comment.get("body").asString(), createdAt, id));
                    comments.add(create(comment));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        return comments;
    }

    public void deleteComment(Comment comment) {
        System.out.printf("Deleting comment id: '%s' %n", comment.id);
        if (this.dryRun) {
            System.out.println("Dry run - Not posting to github");
            return;
        }
        //final String requestUrl = baseUrl + "/issues/" + issue + "/comments/" + comment.id;
        final String requestUrl = baseUrl + "/issues/comments/" + comment.id;

        try {
            execute(URI.create(requestUrl), "DELETE", HttpRequest.BodyPublishers.noBody());
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }


    public List<ModelNode> getAllIssues() {
        final List<ModelNode> resultIssues = new LinkedList<>();
        try {
            // Get all the issues for the repository
            String url = baseUrl + "/issues?state=open&filter=all";
            while (url != null) {
                final var response = execute(URI.create(baseUrl));
                url = nextLink(response);
                if (notModified(response)) { //noting new to do
                    return Collections.emptyList();
                }
                ModelNode responseResult = ModelNode.fromJSONStream(response.body());
                resultIssues.addAll(responseResult.asList());
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        return resultIssues;
    }
}
