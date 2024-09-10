package com.azure.upload;

import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import android.util.Log;
import java.io.*;
import java.net.*;
import javax.net.ssl.HttpsURLConnection;

public class AzureUpload extends CordovaPlugin {

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("uploadFiles")) {
            JSONArray fileList = args.getJSONArray(0);
            String sasToken = args.getString(1);
            String postId = args.getString(2);

            uploadFiles(fileList, sasToken, postId, callbackContext);
            return true;
        }
        return false;
    }

    private void uploadFiles(JSONArray fileList, String sasToken, String postId, CallbackContext callbackContext) {
        try {
            for (int i = 0; i < fileList.length(); i++) {
                JSONObject file = fileList.getJSONObject(i);
                String fileName = file.getString("filename");
                String fileMime = file.getString("filemime");
                byte[] fileBinary = file.getString("filebinary").getBytes();

                // Upload each file in chunks
                boolean uploadSuccess = uploadChunked(fileName, fileMime, fileBinary, sasToken);
                if (!uploadSuccess) {
                    callbackContext.error("Failed to upload file: " + fileName);
                    return;
                }
            }

            // All files uploaded successfully, call completion API
            callCompletionAPI(postId, callbackContext);

        } catch (Exception e) {
            Log.e("AzureUpload", "Upload failed: " + e.getMessage());
            callbackContext.error("Upload failed: " + e.getMessage());
        }
    }

    private boolean uploadChunked(String fileName, String fileMime, byte[] fileBinary, String sasToken) throws IOException {
        // Chunk size (e.g., 1MB)
        int chunkSize = 1024 * 1024; // 1MB
        int totalSize = fileBinary.length;
        int numChunks = (int) Math.ceil((double) totalSize / chunkSize);

        for (int i = 0; i < numChunks; i++) {
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, totalSize);
            byte[] chunk = new byte[end - start];
            System.arraycopy(fileBinary, start, chunk, 0, chunk.length);

            // Construct URL for the Azure Blob Storage SAS token
            URL url = new URL("https://yourazureblobstorageurl/" + fileName + "?sv=" + sasToken);
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setRequestMethod("PUT");
            connection.setRequestProperty("x-ms-blob-type", "BlockBlob");
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(chunk);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 201) {
                Log.e("AzureUpload", "Failed to upload chunk: " + responseCode);
                return false; // Retry logic can be implemented here
            }
        }
        return true;
    }

    private void callCompletionAPI(String postId, CallbackContext callbackContext) {
        String apiUrl = "https://personal-fjlz3d21.outsystemscloud.com/arabicschooln/rest/post/completed?id=" + postId;
        try {
            URL url = new URL(apiUrl);
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            int responseCode = connection.getResponseCode();

            if (responseCode == 200) {
                callbackContext.success("All files uploaded and completion API called successfully.");
            } else {
                callbackContext.error("API call failed with response code: " + responseCode);
            }

        } catch (Exception e) {
            callbackContext.error("Error calling completion API: " + e.getMessage());
        }
    }
}
