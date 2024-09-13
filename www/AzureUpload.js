var azureUpload = {
    uploadFiles: function (filesList, sasToken, postId, successCallback, errorCallback) {
        if (!filesList || !sasToken || !postId) {
            return errorCallback('Invalid arguments. Expected filesList, sasToken, and postId.');
        }

        cordova.exec(
            successCallback,     // Success callback
            errorCallback,       // Error callback
            'AzureUpload',       // Plugin class name (Java class in native code)
            'uploadFiles',       // Action name (the method to call in Java)
            [filesList, sasToken, postId] // Arguments passed to the native code
        );
    }
};

module.exports = azureUpload;
