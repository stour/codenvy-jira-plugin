package ut.com.codenvy.jira;

import org.junit.Test;

import com.codenvy.jira.CodenvyPluginComponent;
import com.codenvy.jira.CodenvyPluginComponentImpl;

import static org.junit.Assert.assertEquals;

public class CodenvyPluginComponentUnitTest {
    @Test
    public void testGetName() {
        CodenvyPluginComponent component = new CodenvyPluginComponentImpl(null);
        assertEquals("names do not match!", "codenvyPluginComponent", component.getName());
    }
}