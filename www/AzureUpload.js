var exec = require('cordova/exec');

var AzureUpload = {
    uploadFiles: function(fileList, sasToken, postId, successCallback, errorCallback) {
        exec(
            successCallback,
            errorCallback,
            'AzureUpload',
            'uploadFiles',
            [fileList, sasToken, postId]
        );
    }
};

module.exports = AzureUpload;
