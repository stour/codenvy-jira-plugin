package com.codenvy.jira;

import us.monoid.json.JSONArray;
import us.monoid.json.JSONException;
import us.monoid.json.JSONObject;
import us.monoid.web.Resty;

import com.atlassian.crowd.embedded.api.User;
import com.atlassian.event.api.EventListener;
import com.atlassian.event.api.EventPublisher;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.FieldException;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.bc.issue.IssueService;
import com.atlassian.jira.event.issue.IssueEvent;
import com.atlassian.jira.event.type.EventType;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueInputParameters;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.ApplicationUsers;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.io.IOException;
import java.util.Set;

import static us.monoid.web.Resty.content;

/**
 * JIRA listener that generates Codenvy factories for factory activated issues.
 */
public class IssueCreatedListener implements InitializingBean, DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(IssueCreatedListener.class);

    private final EventPublisher        eventPublisher;
    private final PluginSettingsFactory pluginSettingsFactory;
    private final IssueService          issueService;
    private final FieldManager          fieldManager;

    /**
     * Constructor.
     * @param eventPublisher injected {@code EventPublisher} implementation.
     */
    public IssueCreatedListener(EventPublisher eventPublisher, PluginSettingsFactory pluginSettingsFactory,
                                IssueService issueService, FieldManager fieldManager) {
        this.eventPublisher = eventPublisher;
        this.pluginSettingsFactory = pluginSettingsFactory;
        this.issueService = issueService;
        this.fieldManager = fieldManager;
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
        final Long eventTypeId = issueEvent.getEventTypeId();
        final Issue issue = issueEvent.getIssue();
        // if it's an event we're interested in, log it
        if (eventTypeId.equals(EventType.ISSUE_CREATED_ID)) {
            LOG.info("Issue {} has been created at {}.", issue.getKey(), issue.getCreated());

            // Get plugin settings
            PluginSettings settings = pluginSettingsFactory.createGlobalSettings();
            final String codenvyUrl = (String)settings.get("codenvy.admin.instanceurl");
            final String codenvyUsername = (String)settings.get("codenvy.admin.username");
            final String codenvyPassword = (String)settings.get("codenvy.admin.password");

            if (codenvyUrl == null || codenvyUsername == null || codenvyPassword == null) {
                LOG.info("codenvyUrl (" + codenvyUrl + ") or codenvyUsername (" + codenvyUsername + ") " +
                         "or codenvyPassword (" + codenvyPassword + ") is not set.");
                return;
            }

            try {

                final String issueKey = issue.getKey();
                final String projectKey = issue.getProjectObject().getKey();
                final String projectName = issue.getProjectObject().getName();

                // Get current JIRA user
                final User eventUser = issueEvent.getUser();
                if (eventUser == null) {
                    LOG.info("No user given in issue event.");
                    return;
                }
                final ApplicationUser appUser = ApplicationUsers.from(eventUser);

                // Get id of custom fields Develop & Review
                String developFieldId = null;
                String reviewFieldId = null;
                final Set<CustomField> customFields = fieldManager.getAvailableCustomFields(eventUser, issue);
                for (CustomField cf : customFields) {
                    if ("Develop".equals(cf.getFieldName())) {
                        developFieldId = cf.getId();
                    }
                    if ("Review".equals(cf.getFieldName())) {
                        reviewFieldId = cf.getId();
                    }
                }

                // Continue only if Develop and Review fields are available on the issue
                if (developFieldId == null || reviewFieldId == null) {
                    LOG.info("Field Develop (" + developFieldId + ") and/or Review (" + reviewFieldId + ") are not available for issue " +
                             issueKey + ".");
                    return;
                }

                JSONObject token = null;
                final Resty resty = new Resty();

                // Authenticate on Codenvy as JIRA admin
                final String credentials =
                        "{ \"username\": \"" + codenvyUsername + "\", \"password\": \"" + codenvyPassword + "\" }";
                JSONObject cred = new JSONObject(credentials);
                token = resty.json(codenvyUrl + "/api/auth/login", content(cred)).object();

                if (token == null) {
                    LOG.info("No Codenvy Token obtained.");
                    return;
                }

                // Get parent factory for project
                final String tokenValue = token.getString("value");
                final JSONArray factories =
                        resty.json(codenvyUrl + "/api/factory/find?name=" + projectKey.toLowerCase() + "&token=" + tokenValue)
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
                        resty.json(codenvyUrl + "/api/factory", content(developFactory)).object();
                LOG.info("Generated DEVELOP factory for issue " + issueKey + ": " + generatedDevelopFactory);

                // Set perClick policy & correct name (Review factory)
                final JSONObject reviewFactory = setCreatePolicy(developFactory, "perClick");
                reviewFactory.remove("name");
                reviewFactory.put("name", issueKey + "-review-factory");

                // Generate Review factory
                final JSONObject generatedReviewFactory =
                        resty.json(codenvyUrl + "/api/factory", content(reviewFactory)).object();
                LOG.info("Generated REVIEW factory for issue " + issueKey + ": " + generatedReviewFactory);

                // Set factory URLs in Develop & Review fields
                String developFactoryUrl = getNamedFactoryUrl(generatedDevelopFactory);
                String reviewFactoryUrl = getNamedFactoryUrl(generatedReviewFactory);

                if (developFactoryUrl == null || reviewFactoryUrl == null) {
                    LOG.info("URL of factory Develop (" + developFactoryUrl + ") and/or Review (" + reviewFactoryUrl +
                             ") is null.");
                    return;
                }

                updateIssue(appUser, issueKey, developFieldId, developFactoryUrl, reviewFieldId, reviewFactoryUrl);
            } catch (JSONException | IOException | FieldException e) {
                LOG.info(e.getMessage());
            }
        }
    }

    private void updateIssue(ApplicationUser appUser, String issueKey, String developFieldId, String developValue,
                             String reviewFieldId, String reviewValue) {
        // Get the issue from the key that's passed in
        IssueService.IssueResult issueResult = issueService.getIssue(appUser, issueKey);
        MutableIssue issue = issueResult.getIssue();
        // Next we need to validate the updated issue
        IssueInputParameters issueInputParameters = issueService.newIssueInputParameters();
        issueInputParameters.addCustomFieldValue(developFieldId, developValue);
        issueInputParameters.addCustomFieldValue(reviewFieldId, reviewValue);
        IssueService.UpdateValidationResult result = issueService.validateUpdate(appUser, issue.getId(),
                                                                                 issueInputParameters);
        if (result.getErrorCollection().hasAnyErrors()) {
            LOG.info("Issue " + issueKey + " not updated due to error(s): " + result.getErrorCollection().getErrorMessages() + ".");
        } else {
            // Validation passes
            issueService.update(appUser, result);
            LOG.info("Issue " + issueKey + " successfully updated.");
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