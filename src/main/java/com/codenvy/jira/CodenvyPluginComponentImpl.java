package com.codenvy.jira;

import com.atlassian.sal.api.ApplicationProperties;

public class CodenvyPluginComponentImpl implements CodenvyPluginComponent
{
    private final ApplicationProperties applicationProperties;

    public CodenvyPluginComponentImpl(ApplicationProperties applicationProperties)
    {
        this.applicationProperties = applicationProperties;
    }

    public String getName()
    {
        if(null != applicationProperties)
        {
            return "CodenvyComponent:" + applicationProperties.getDisplayName();
        }
        
        return "CodenvyComponent";
    }
}