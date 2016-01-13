package com.example.tutorial.plugins;

import us.monoid.json.JSONArray;
import us.monoid.json.JSONException;
import us.monoid.json.JSONObject;
import us.monoid.web.JSONResource;
import us.monoid.web.Resty;

import com.atlassian.event.api.EventListener;
import com.atlassian.event.api.EventPublisher;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.config.properties.APKeys;
import com.atlassian.jira.event.issue.IssueEvent;
import com.atlassian.jira.event.type.EventType;
import com.atlassian.jira.issue.Issue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.io.IOException;

import static us.monoid.web.Resty.content;
import static us.monoid.web.Resty.put;

/**
 * Simple JIRA listener using the atlassian-event library and demonstrating
 * plugin lifecycle integration.
 */
public class IssueCreatedResolvedListener implements InitializingBean, DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(IssueCreatedResolvedListener.class);

    private static final String CODENVY_INSTANCE_API_URL = "http://internal.codenvycorp.com/api";
    private static final String CODENVY_JIRA_USERNAME    = "alt.ya-4gjloe3@yopmail.com";
    private static final String CODENVY_JIRA_PASSWORD    = "codenvy2015";

    private final EventPublisher eventPublisher;
    private final String         jiraBaseUrl;

    /**
     * Constructor.
     * @param eventPublisher injected {@code EventPublisher} implementation.
     */
    public IssueCreatedResolvedListener(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
        this.jiraBaseUrl = ComponentAccessor.getApplicationProperties().getString(APKeys.JIRA_BASEURL);
    }

    /**
     * Called when the plugin has been enabled.
     * @throws Exception
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        // register ourselves with the EventPublisher
        eventPublisher.register(this);
    }

    /**
     * Called when the plugin is being disabled or removed.
     * @throws Exception
     */
    @Override
    public void destroy() throws Exception {
        // unregister ourselves with the EventPublisher
        eventPublisher.unregister(this);
    }

    /**
     * Receives any {@code IssueEvent}s sent by JIRA.
     * @param issueEvent the IssueEvent passed to us
     */
    @EventListener
    public void onIssueEvent(IssueEvent issueEvent) {
        Long eventTypeId = issueEvent.getEventTypeId();
        Issue issue = issueEvent.getIssue();
        // if it's an event we're interested in, log it
        if (eventTypeId.equals(EventType.ISSUE_CREATED_ID)) {
            LOG.info("Issue {} has been created at {}.", issue.getKey(), issue.getCreated());

            try {
                JSONObject token = null;
                final Resty resty = new Resty();
                resty.withHeader("Authorization", "Basic YWRtaW46YWRtaW4=");

                // Authenticate on Codenvy as JIRA admin
                final String credentials =
                        "{ \"username\": \"" + CODENVY_JIRA_USERNAME + "\", \"password\": \"" + CODENVY_JIRA_PASSWORD + "\" }";
                JSONObject cred = new JSONObject(credentials);
                token = resty.json(CODENVY_INSTANCE_API_URL + "/auth/login", content(cred)).object();
                LOG.info("Codenvy token: " + token);


                if (token != null) {
                    // Get parent factory for project
                    final String issueKey = issue.getKey();
                    final String projectKey = issue.getProjectObject().getKey();
                    final String projectName = issue.getProjectObject().getName();
                    final String tokenValue = token.getString("value");
                    final JSONArray factories =
                            resty.json(CODENVY_INSTANCE_API_URL + "/factory/find?name=" + projectKey.toLowerCase() + "&token=" + tokenValue)
                                 .array();

                    if (factories.length() == 0) {
                        LOG.info("No factory found with name: " + projectKey.toLowerCase());
                        return;
                    }

                    JSONObject parentFactory = factories.getJSONObject(0);
                    LOG.info("Parent factory for project " + projectName + ": " + parentFactory);

                    // Set perUser policy & correct name (Develop factory)
                    final JSONObject developFactory = setCreatePolicy(parentFactory, "perUser");
                    developFactory.remove("name");
                    developFactory.put("name", issueKey + "-develop-factory");

                    // Clean id and creator
                    developFactory.remove("id");
                    developFactory.remove("creator");

                    // Set workspace.projects.source.parameters.branch = issue key
                    final JSONObject project = developFactory.getJSONObject("workspace").getJSONArray("projects").getJSONObject(0);
                    final JSONObject parameters = project.getJSONObject("source").getJSONObject("parameters");
                    parameters.put("branch", issueKey);

                    // Generate Develop factory
                    final JSONObject generatedDevelopFactory =
                            resty.json(CODENVY_INSTANCE_API_URL + "/factory", content(developFactory)).object();
                    LOG.info("Generated DEVELOP factory for issue " + issueKey + ": " + generatedDevelopFactory);

                    // Set perClick policy & correct name (Review factory)
                    final JSONObject reviewFactory = setCreatePolicy(developFactory, "perClick");
                    reviewFactory.remove("name");
                    reviewFactory.put("name", issueKey + "-review-factory");

                    // Generate Review factory
                    final JSONObject generatedReviewFactory =
                            resty.json(CODENVY_INSTANCE_API_URL + "/factory", content(reviewFactory)).object();
                    LOG.info("Generated REVIEW factory for issue " + issueKey + ": " + generatedReviewFactory);

                    // Get id of custom fields Develop & Review
                    JSONResource resultGetFields = resty.json(jiraBaseUrl + "/rest/api/2/field");

                    if (resultGetFields.http().getResponseCode() != 200) {
                        LOG.info("GET " + jiraBaseUrl + "/rest/api/2/field failed with code " +
                                 resultGetFields.http().getResponseCode() + ": " + resultGetFields.http().getResponseMessage());
                        return;
                    }

                    String developFieldId = null;
                    String reviewFieldId = null;
                    final JSONArray fields = resultGetFields.array();
                    for (int i = 0; i < fields.length(); i++) {
                        JSONObject field = fields.getJSONObject(i);
                        final boolean custom = field.getBoolean("custom");
                        final String name = field.getString("name");
                        if (custom) {
                            if ("Develop".equals(name)) {
                                developFieldId = field.getString("id");
                            }
                            if ("Review".equals(name)) {
                                reviewFieldId = field.getString("id");
                            }
                        }
                    }

                    // Set factory URLs in Develop & Review fields
                    if (developFieldId == null || reviewFieldId == null) {
                        LOG.info("Id of field Develop (" + developFieldId + ") and/or Review (" + reviewFieldId + ") is null.");
                        return;
                    }
                    String developFactoryUrl = getNamedFactoryUrl(generatedDevelopFactory);
                    String reviewFactoryUrl = getNamedFactoryUrl(generatedReviewFactory);

                    if (developFactoryUrl == null || reviewFactoryUrl == null) {
                        LOG.info("URL of factory Develop (" + developFactoryUrl + ") and/or Review (" + reviewFactoryUrl +
                                 ") is null.");
                        return;
                    }

                    JSONObject update = new JSONObject().put("fields",
                                                             new JSONObject().put(developFieldId, developFactoryUrl)
                                                                             .put(reviewFieldId, reviewFactoryUrl));
                    LOG.info("update: " + update);
                    JSONResource resultUpdateIssue =
                            resty.json(jiraBaseUrl + "/rest/api/2/issue/" + issueKey, put(content(update)));
                    int responseHttpCode = resultUpdateIssue.http().getResponseCode();
                    if (responseHttpCode != 204) {
                        LOG.info("Update of issue " + issueKey + " failed with code " + responseHttpCode + ": " +
                                 resultUpdateIssue.http().getResponseMessage());
                    } else {
                        LOG.info("Issue " + issueKey + " successfully updated with code " + responseHttpCode);
                    }
                }
            } catch (JSONException | IOException e) {
                LOG.info(e.getMessage());
            }
        }
    }

    private JSONObject setCreatePolicy(JSONObject factory, String createPolicy) throws JSONException {
        JSONObject newFactory = factory;
        JSONObject policies = newFactory.optJSONObject("policies");
        if (policies == null) {
            newFactory.put("policies", new JSONObject().put("create", createPolicy));
        } else {
            policies.remove("create");
            policies.put("create", createPolicy);
        }
        return newFactory;
    }

    private String getNamedFactoryUrl(JSONObject factory) throws JSONException {
        String factoryUrl = null;
        JSONArray links = factory.getJSONArray("links");
        for (int i = 0; i < links.length(); i++) {
            JSONObject link = links.getJSONObject(i);
            String rel = link.getString("rel");
            if ("accept-named".equals(rel)) {
                factoryUrl = link.getString("href");
                break;
            }
        }
        return factoryUrl;
    }
}