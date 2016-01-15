AJS.toInit(function() {
    var baseUrl = AJS.contextPath();

    function populateForm() {
        AJS.$.ajax({
            url: baseUrl + "/rest/codenvy-admin/1.0/",
            dataType: "json",
            success: function(config) {
                AJS.$("#instanceUrl").attr("value", config.instanceUrl);
                AJS.$("#username").attr("value", config.username);
                AJS.$("#password").attr("value", config.password);
            }
        });
    }
    function updateConfig() {
        AJS.$.ajax({
            url: baseUrl + "/rest/codenvy-admin/1.0/",
            type: "PUT",
            contentType: "application/json",
            data: '{ "instanceUrl": "' + AJS.$("#instanceUrl").attr("value") + '", "username": "' + AJS.$("#username").attr("value") + '", "password": "' + AJS.$("#password").attr("value") + '" }',
            processData: false
        }).done(function( data ) {
            alert("Codenvy data successfully saved.");
        });
    }
    populateForm();

    AJS.$("#admin").submit(function(e) {
        e.preventDefault();
        updateConfig();
    });
});