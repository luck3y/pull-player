package org.jboss.pull.player;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import org.jboss.dmr.ModelNode;

/**
 * @author Tomaz Cerar (c) 2013 Red Hat Inc.
 */
public class PullPlayer {

    // old control strings, deprecated
    private static final Pattern okToTest = Pattern.compile(".*ok\\W+to\\W+test.*", Pattern.DOTALL);
    private static final Pattern retest = Pattern.compile(".*retest\\W+this\\W+please.*", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final GitHubApi gitHubApi;
    private final TeamCityApi teamCityApi;
    private final LabelProcessor labelProcessor;
    private final boolean whitelistEnabled;
    private final String skipLabel;
    private String githubLogin;

    protected PullPlayer(final boolean dryRun) throws Exception {
        String teamcityHost = Util.require("teamcity.host");
        int teamcityPort = Integer.parseInt(Util.require("teamcity.port"));
        String teamcityBranchMapping = Util.require("teamcity.build.branch-mapping");
        githubLogin = Util.require("github.login");
        String githubToken = Util.require("github.token");
        String githubRepo = Util.require("github.repo");
        String user = Util.require("teamcity.user");
        String password = Util.require("teamcity.password");
        gitHubApi = new GitHubApi(githubToken, githubRepo, dryRun);

        final boolean disabled = Util.optionalBoolean("teamcity.disabled", false);
        this.whitelistEnabled = Util.optionalBoolean("whitelist.enabled", true);
        System.out.println("White list enabled: " + whitelistEnabled);
        teamCityApi = new TeamCityApi(teamcityHost, teamcityPort, user, password, teamcityBranchMapping, dryRun, disabled);
        labelProcessor = new LabelProcessor(gitHubApi);
        skipLabel = Util.getProperties().getProperty("labels.skip", "");
    }

    static String getTime() {
        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        Date date = new Date();
        return dateFormat.format(date);
    }


    boolean noBuildPending(int pull, List queue) {
        return !queue.contains(pull);
    }

    private void processPulls(PersistentList whiteList, PersistentList adminList, List<ModelNode> nodes) {
        final List<Integer> queue = teamCityApi.getQueuedBuilds();
        for (ModelNode pull : nodes) {
            System.out.println("---------------------------------------------------------------------------------");
            int pullNumber = pull.get("number").asInt();
            String user = pull.get("user", "login").asString();
            // note this sha is the commit id of the PR, not the test merge sha, that is fetched below.
            String sha1 = pull.get("head", "sha").asString();
            String branch = pull.get("base", "ref").asString();
            if (sha1 == null) {
                System.err.println("Could not get sha1 for pull: " + pullNumber);
                continue;
            }
            System.out.printf("number: %d login: %s sha1: %s, branch: %s\n", pullNumber, user, sha1, branch);

            if (!teamCityApi.hasBranchMapping(branch)) {
                System.out.printf("Pull request %s send against target branch %s, but there is no build type defined for it.\n", pullNumber, branch);
                continue;
            }

            // Add the pull to the label processor
            labelProcessor.add(pull);

            boolean help = false;
            boolean retrigger = false;
            boolean retriggerFailed = false;
            Instant retriggerDate = null;
            boolean whitelistNotify = true;
            String commentId = "";

            ModelNode prDetails = gitHubApi.getPullRequestDetails(pullNumber);

            // not all pr information is available on the pr list, and in order for a test merge to be created
            // the pr details have to be either fetched or looked at in the browser. If mergeable is false or null
            // then the pr is skipped and rechecked on the next run
            // see: https://developer.github.com/v3/git/#checking-mergeability-of-pull-requests
            boolean mergeable = false;
            String mergeCommitSha = null;

            if (prDetails != null) {
                mergeable = prDetails.get("mergeable").asString("false").equals("true");
            }

            // this is the test commit created that is a merge onto the branch the pr has been opened against.
            // see: https://developer.github.com/v3/pulls/#response-1
            // this also need the vcs root to be configured against +:refs/(pull/*)/merge in order to match
            // if the PR is marked as mergeable and the mergeCommitSha is not null or missing, the /merge test ref
            // is present and the PR can be tested
            if (mergeable) {
                mergeCommitSha = prDetails.get("merge_commit_sha").asString();
            }
            String commentsUrl = pull.get("comments_url").asString(); //get url for comments
            List<Comment> comments = gitHubApi.getComments(commentsUrl);

            String job = null;
            // if mergeCommitSha isn't set, we're still waiting on the gh api to update the /merge ref, so we'll retry
            if (mergeCommitSha != null) {
                job = Jobs.getCompletedJob(sha1);
                // also look for a previously completed job via mergecommitsha also, this is for compat with some already run jobs
                if (job == null) {
                    job = Jobs.getCompletedJob(mergeCommitSha);
                }
            }
            // comments == null indicates a NOT-MODIFIED response. A new PR will have an empty
            // but not null comments collection.
            if (comments != null) {
                for (Comment comment : comments) {
                    commentId = comment.id;

                    if (whiteList.has(user) && whiteList.has(comment.user) && job != null && (retest.matcher(comment.comment).matches() || comment.comment.startsWith(Command.RETEST.getCommand()))) {
                        retriggerDate = comment.created;
                        retrigger = true;
                        continue;
                    }

                    if (!whiteList.has(user) && adminList.has(comment.user) && okToTest.matcher(comment.comment).matches() || comment.comment.startsWith(Command.OK_TO_TEST.getCommand())) {
                        whiteList.add(user);
                        retriggerDate = comment.created;
                        retrigger = true;
                    }

                    if (!whiteList.has(user) && adminList.has(comment.user) && comment.comment.startsWith(Command.RETEST_FAILED.getCommand())) {
                        whiteList.add(user);
                        retriggerDate = comment.created;
                        retrigger = false;
                        retriggerFailed = true;
                    }

                    if (githubLogin.equals(comment.user) && comment.comment.contains("triggering")) {
                        retrigger = false;
                        continue;
                    }

                    if (githubLogin.equals(comment.user) && comment.comment.contains("running")) {
                        retrigger = false;
                        continue;
                    }

                    if (githubLogin.equals(comment.user) && comment.comment.contains("verify this patch")) {
                        whitelistNotify = false;
                        help=true;
                        continue;
                    }

                }
            } else {
                // not modified since last time we checked the comments
                whitelistNotify = false;
                retrigger = false;
            }

            if (whitelistEnabled) {
                if (job == null && !verifyWhitelist(whiteList, user, pullNumber, whitelistNotify)) {
                    System.out.println("User not on approved tester list, user: " + user);
                    continue;
                }
            }

            if (mergeable == true && mergeCommitSha == null) {
                System.out.println("No valid merge_commit_sha found on PR, skipping.");
                continue;
            }
            System.out.printf("merge commit sha: %s\n", mergeCommitSha);
            TeamCityBuild build = null;
            if (mergeCommitSha != null) {
                build = teamCityApi.findBuild(pullNumber, sha1, branch);
                if (build != null) {
                    System.out.println("found build: " + build.toString());
                }
                // for legacy compatability and to avoid requeuing all jobs, we check if build is null for a build with the previously used mergecommitsha as well
                if (build == null) {
                    build = teamCityApi.findBuild(pullNumber, mergeCommitSha, branch);
                    if (build != null) {
                        System.out.println("sha1 build: " + build.toString());
                    }
                }
            }

            System.out.println("retrigger = " + retrigger);
            if (retrigger) {
                if (build != null && build.getQueuedDate().isAfter(retriggerDate)) {
                    System.out.println("Not triggering as newer build already exists");
                    retrigger = false;
                } else if (queue.contains(pullNumber)) {
                    System.out.println("Build already queued");
                } else if (build != null && build.isRunning()) {
                    System.out.println("Build already running");
                } else {
                    job = null;
                    Jobs.remove(sha1);
                }
            }

            if (job != null) {
                System.out.println("Already done: " + pullNumber);
                continue;
            }

            if (skipBuild(pullNumber, prDetails)) {
                continue;
            }

            if (build != null && !retrigger) {
                if (build.getStatus() != null) {
                    Jobs.storeCompletedJob(sha1, pullNumber, build.getBuild());
                } else {
                    System.out.println("In progress, skipping: " + pullNumber);
                }
            } else if (mergeable && mergeCommitSha != null && sha1 != null && noBuildPending(pullNumber, queue)) {
                teamCityApi.triggerJob(pullNumber, sha1, branch);
            } else {
                System.out.println("Pending build, skipping: " + pullNumber);
            }
        }
    }

    private boolean skipBuild(final int pullNumber, final ModelNode model) {
        if (model == null) {
            System.out.printf("Not skipping build %s even though the model is null.%n", pullNumber);
            return false;
        }
        if (skipLabel.isBlank()) {
            return false;
        }
        // Check the labels
        final List<ModelNode> labels = model.get("labels").asList();
        for (ModelNode label : labels) {
            if (skipLabel.equals(label.get("name").asString())) {
                System.out.printf("Skipping build %s as it's labeled with %s.%n", pullNumber, skipLabel);
                return true;
            }
        }
        return false;
    }

    private boolean verifyWhitelist(PersistentList whiteList, String user, int pullNumber, boolean notify) {
        if (!whiteList.has(user)) {
            System.out.printf("Skipping %s\n", user);
            if (notify) {
                StringBuilder buf = new StringBuilder();
                buf.append("<p>Hello, " + user + ". I'm waiting for one of the admins to verify this patch with " + Command.OK_TO_TEST.getCommand() + " in a comment.</p>");
                //buf.append(help());
                gitHubApi.postComment(pullNumber, buf.toString());
            }
            return false;
        }

        return true;
    }

    public static String help() {
        StringBuilder buf = new StringBuilder();
        buf.append("<p>Available Commands:</p>");
        for (Command c : Command.values()) {
            if (c.enabled()) {
                buf.append("<p><b>" + c.getCommand() + "</b> " + c.getDescription() + "</p>");
            }
        }
        return buf.toString();
    }

    public void dumpPullRequestData(final int prNumber) {
        ModelNode node = gitHubApi.getPullRequestDetails(prNumber);
        System.out.println("Dumping PR: " + prNumber);
        System.out.println(node.toJSONString(false));
        boolean mergeable = false;
        String mergeablestr = node.get("mergeable").asString("");
        System.out.println("mergeable on PR details: mergeable = " + mergeablestr);
        mergeable = node.get("mergeable").asString("false").equals("true");
        System.out.println("Using translated mergeable value of: " + mergeable);
        String mergeCommitSha = node.get("merge_commit_sha").asString();
        System.out.println("merge_commit_sha: " + mergeCommitSha);
    }

    protected void checkPullRequests() {
        final PersistentList whiteList = PersistentList.loadList("white-list");
        final PersistentList adminList = PersistentList.loadList("admin-list");

        List<ModelNode> nodes = gitHubApi.getPullRequests();
        processPulls(whiteList, adminList, nodes);

        // Process the labels after each pull has been added
        labelProcessor.process();
    }

    protected void cleanup() throws IOException {
        gitHubApi.close();
    }

    protected void checkRebaseRequired() throws IOException {
        List<ModelNode> nodes = gitHubApi.getIssuesWithPullRequests();
        labelProcessor.process(nodes);

    }

    void cleanupComments(int pullId) {
        List<Comment> comments = gitHubApi.getCommentsForIssue(pullId);
        System.out.println("All comments on issue "+comments.size());
        comments.stream()
                .filter(comment -> comment.user.equals(githubLogin))
                .filter(comment -> comment.comment.contains("verify this patch"))
                .forEach(comment -> gitHubApi.deleteComment(comment));


    }

    void cleanupComments() {
        gitHubApi.getAllIssues().stream()
                .filter(node -> node.get("comments").asInt() > 2)
                .forEach(node -> {
                    gitHubApi.getCommentsForIssue(node.get("number").asInt()).stream()
                            .filter(comment -> comment.user.equals(githubLogin))
                            .forEach(gitHubApi::deleteComment);
                });


    }
}
