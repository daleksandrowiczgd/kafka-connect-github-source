package com.daleksandrowicz.kafka;

import com.daleksandrowicz.kafka.model.Issue;
import com.daleksandrowicz.kafka.model.PullRequest;
import com.daleksandrowicz.kafka.model.User;
import com.daleksandrowicz.kafka.utils.DateUtils;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;


public class GitHubSourceTask extends SourceTask {
    private static final Logger log = LoggerFactory.getLogger(GitHubSourceTask.class);
    public GitHubSourceConnectorConfig config;

    protected Instant nextQuerySince;
    protected Integer lastIssueNumber;
    protected Integer nextPageToVisit = 1;
    protected Instant lastUpdatedAt;

    GitHubAPIHttpClient gitHubHttpAPIClient;

    @Override
    public String version() {
        return VersionUtil.getVersion();
    }

    @Override
    public void start(Map<String, String> map) {
        //Do things here that are required to start your task. This could be open a connection to a database, etc.
        config = new GitHubSourceConnectorConfig(map);
        initializeLastVariables();
        gitHubHttpAPIClient = new GitHubAPIHttpClient(config);
    }

    private void initializeLastVariables(){
        Map<String, Object> lastSourceOffset = null;
        lastSourceOffset = context.offsetStorageReader().offset(sourcePartition());
        if( lastSourceOffset == null){
            // we haven't fetched anything yet, so we initialize to 7 days ago
            nextQuerySince = config.getSince();
            lastIssueNumber = -1;
        } else {
            Object updatedAt = lastSourceOffset.get(GitHubSchemas.UPDATED_AT_FIELD);
            Object issueNumber = lastSourceOffset.get(GitHubSchemas.NUMBER_FIELD);
            Object nextPage = lastSourceOffset.get(GitHubSchemas.NEXT_PAGE_FIELD);
            if(updatedAt != null && (updatedAt instanceof String)){
                nextQuerySince = Instant.parse((String) updatedAt);
            }
            if(issueNumber != null && (issueNumber instanceof String)){
                lastIssueNumber = Integer.valueOf((String) issueNumber);
            }
            if (nextPage != null && (nextPage instanceof String)){
                nextPageToVisit = Integer.valueOf((String) nextPage);
            }
        }
    }



    @Override
    public List<SourceRecord> poll() throws InterruptedException {
        gitHubHttpAPIClient.sleepIfNeed();

        // fetch data
        final ArrayList<SourceRecord> records = new ArrayList<>();
        JSONArray issues = gitHubHttpAPIClient.getNextIssues(nextPageToVisit, nextQuerySince);
        // we'll count how many results we get with i
        int i = 0;
        for (Object obj : issues) {
            Issue issue = Issue.fromJson((JSONObject) obj);
            SourceRecord sourceRecord = generateSourceRecord(issue);
            records.add(sourceRecord);
            i += 1;
            lastUpdatedAt = issue.getUpdatedAt();
        }
        if (i > 0) log.info(String.format("Fetched %s record(s)", i));
        if (i == 100){
            // we have reached a full batch, we need to get the next one
            nextPageToVisit += 1;
        }
        else {
            nextQuerySince = lastUpdatedAt.plusSeconds(1);
            nextPageToVisit = 1;
            gitHubHttpAPIClient.sleep();
        }
        return records;
    }

    private SourceRecord generateSourceRecord(Issue issue) {
        return new SourceRecord(
                sourcePartition(),
                sourceOffset(issue.getUpdatedAt()),
                config.getTopic(),
                null, // partition will be inferred by the framework
                GitHubSchemas.KEY_SCHEMA,
                buildRecordKey(issue),
                GitHubSchemas.VALUE_SCHEMA,
                buildRecordValue(issue),
                issue.getUpdatedAt().toEpochMilli());
    }

    @Override
    public void stop() {
        // Do whatever is required to stop your task.
    }

    private Map<String, String> sourcePartition() {
        Map<String, String> map = new HashMap<>();
        map.put(GitHubSchemas.OWNER_FIELD, config.getOwnerConfig());
        map.put(GitHubSchemas.REPOSITORY_FIELD, config.getRepoConfig());
        return map;
    }

    private Map<String, String> sourceOffset(Instant updatedAt) {
        Map<String, String> map = new HashMap<>();
        map.put(GitHubSchemas.UPDATED_AT_FIELD, DateUtils.MaxInstant(updatedAt, nextQuerySince).toString());
        map.put(GitHubSchemas.NEXT_PAGE_FIELD, nextPageToVisit.toString());
        return map;
    }

    private Struct buildRecordKey(Issue issue){
        // Key Schema
        Struct key = new Struct(GitHubSchemas.KEY_SCHEMA)
                .put(GitHubSchemas.OWNER_FIELD, config.getOwnerConfig())
                .put(GitHubSchemas.REPOSITORY_FIELD, config.getRepoConfig())
                .put(GitHubSchemas.NUMBER_FIELD, issue.getNumber());

        return key;
    }

    public Struct buildRecordValue(Issue issue){

        // Issue top level fields
        Struct valueStruct = new Struct(GitHubSchemas.VALUE_SCHEMA)
                .put(GitHubSchemas.URL_FIELD, issue.getUrl())
                .put(GitHubSchemas.TITLE_FIELD, issue.getTitle())
                .put(GitHubSchemas.CREATED_AT_FIELD, Date.from(issue.getCreatedAt()))
                .put(GitHubSchemas.UPDATED_AT_FIELD, Date.from(issue.getUpdatedAt()))
                .put(GitHubSchemas.NUMBER_FIELD, issue.getNumber())
                .put(GitHubSchemas.STATE_FIELD, issue.getState());

        // User is mandatory
        User user = issue.getUser();
        Struct userStruct = new Struct(GitHubSchemas.USER_SCHEMA)
                .put(GitHubSchemas.USER_URL_FIELD, user.getUrl())
                .put(GitHubSchemas.USER_ID_FIELD, user.getId())
                .put(GitHubSchemas.USER_LOGIN_FIELD, user.getLogin());
        valueStruct.put(GitHubSchemas.USER_FIELD, userStruct);

        // Pull request is optional
        PullRequest pullRequest = issue.getPullRequest();
        if (pullRequest != null) {
            Struct prStruct = new Struct(GitHubSchemas.PR_SCHEMA)
                    .put(GitHubSchemas.PR_URL_FIELD, pullRequest.getUrl())
                    .put(GitHubSchemas.PR_HTML_URL_FIELD, pullRequest.getHtmlUrl());
            valueStruct.put(GitHubSchemas.PR_FIELD, prStruct);
        }

        return valueStruct;
    }

}