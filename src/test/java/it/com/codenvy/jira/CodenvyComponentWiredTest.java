package it.com.codenvy.jira;

import org.junit.Test;
import org.junit.runner.RunWith;

import com.atlassian.plugins.osgi.test.AtlassianPluginsTestRunner;
import com.atlassian.sal.api.ApplicationProperties;
import com.codenvy.jira.CodenvyPluginComponent;

import static org.junit.Assert.assertEquals;

@RunWith(AtlassianPluginsTestRunner.class)
public class CodenvyComponentWiredTest {
    private final ApplicationProperties  applicationProperties;
    private final CodenvyPluginComponent codenvyPluginComponent;

    public CodenvyComponentWiredTest(ApplicationProperties applicationProperties, CodenvyPluginComponent codenvyPluginComponent) {
        this.applicationProperties = applicationProperties;
        this.codenvyPluginComponent = codenvyPluginComponent;
    }

    @Test
    public void testMyName() {
        assertEquals("names do not match!", "codenvyPluginComponent:" + applicationProperties.getDisplayName(), codenvyPluginComponent.getName());
    }
}