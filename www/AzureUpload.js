var exec = require('cordova/exec');

var AzureUpload = {
    uploadFiles: function (fileList, sasToken, postId, successCallback, errorCallback) {
        // Convert fileList to JSON if needed
        if (typeof fileList !== 'string') {
            fileList = JSON.stringify(fileList);
        }

        exec(successCallback, errorCallback, 'AzureUpload', 'uploadFiles', [fileList, sasToken, postId]);
    }
};

module.exports = AzureUpload;
