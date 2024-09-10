var exec = require('cordova/exec');

exports.uploadFiles = function(fileList, sasToken, postId, successCallback, errorCallback) {
    exec(successCallback, errorCallback, 'AzureUpload', 'uploadFiles', [fileList, sasToken, postId]);
};
