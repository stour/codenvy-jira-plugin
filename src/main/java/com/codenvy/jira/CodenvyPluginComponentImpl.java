/*******************************************************************************
 * Copyright (c) 2012-2016 Codenvy
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
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
            return "codenvyPluginComponent:" + applicationProperties.getDisplayName();
        }
        
        return "codenvyPluginComponent";
    }
}