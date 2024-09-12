package com.azure.upload;

import android.util.Base64;
import android.util.Log;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import androidx.core.app.NotificationCompat;
import android.os.Build;
import androidx.annotation.NonNull;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.UUID;

public class AzureUpload extends CordovaPlugin {

    private static final String TAG = "AzureUpload";
    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationBuilder;
    private static final String CHANNEL_ID = "upload_channel";
    private static final int NOTIFICATION_ID = 1;

    @Override
    public boolean execute(@NonNull String action, JSONArray args, @NonNull CallbackContext callbackContext) throws JSONException {
        if (action.equals("uploadFiles")) {
            JSONArray fileList = args.getJSONArray(0);
            String sasToken = args.getString(1);
            String postId = args.getString(2);
            uploadFiles(fileList, sasToken, postId, callbackContext);
            return true;
        }
        return false;
    }

    private void uploadFiles(JSONArray fileList, String sasToken, String postId, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                int totalFiles = fileList.length();
                int filesUploaded = 0;
                initializeNotification(totalFiles);  // Initialize notification here

                for (int i = 0; i < totalFiles; i++) {
                    JSONObject file = fileList.getJSONObject(i);

                    String destination = file.getString("destination");
                    String originalName = file.getString("originalname");
                    String fileMime = file.getString("filemime");
                    String fileBinary = file.getString("filebinary");

                    // Debugging output
                    Log.d(TAG, "Destination: " + destination);
                    Log.d(TAG, "Original Name: " + originalName);
                    Log.d(TAG, "File MIME: " + fileMime);
                    Log.d(TAG, "File Binary Length: " + fileBinary.length());

                    byte[] fileData = Base64.decode(fileBinary, Base64.DEFAULT);
                    String filePath = "https://arabicschool.blob.core.windows.net/arabicschool" + destination;

                    boolean success = uploadChunked(filePath, fileData, sasToken, originalName, fileMime, originalName, postId);
                    if (success) {
                        filesUploaded++;
                        updateNotification(filesUploaded, totalFiles);  // Update notification progress
                        if (filesUploaded == totalFiles) {
                            callCompletionAPI(postId, callbackContext);
                            removeNotification();  // Remove notification once upload is complete
                        }
                    } else {
                        callbackContext.error("Failed to upload file: " + originalName);
                        showErrorNotification("Failed to upload file: " + originalName);
                        return;
                    }
                }
            } catch (JSONException e) {
                callbackContext.error("JSON Error: " + e.getMessage());
            }
        });
    }

    private boolean uploadChunked(String filePath, byte[] fileBinary, String sasToken, String originalName, String fileMime, String originalNameForLogging, String postId) {
        try {
            final int chunkSize = 1024 * 1024; // 1MB chunk size
            int totalSize = fileBinary.length;
            int numChunks = (int) Math.ceil((double) totalSize / chunkSize);

            String blockIdBase = UUID.randomUUID().toString().replace("-", "");
            StringBuilder blockList = new StringBuilder();

            for (int i = 0; i < numChunks; i++) {
                int start = i * chunkSize;
                int end = Math.min(start + chunkSize, totalSize);
                byte[] chunk = new byte[end - start];
                System.arraycopy(fileBinary, start, chunk, 0, chunk.length);

                String blockId = String.format("%05d", i);
                blockList.append("<Block>").append(Base64.encodeToString(blockId.getBytes(), Base64.NO_WRAP)).append("</Block>");

                boolean uploadSuccess = uploadBlock(filePath, chunk, sasToken, blockId);
                if (!uploadSuccess) {
                    return false;
                }
            }

            boolean commitSuccess = commitBlockList(filePath, sasToken, blockList.toString());
            if (!commitSuccess) {
                return false;
            }

            // Once the file is successfully uploaded, commit it to the database
            return commitUpload(postId, fileMime, originalName, filePath);
        } catch (Exception e) {
            Log.e(TAG, "Upload Chunked Error: " + e.getMessage());
            return false;
        }
    }

    private boolean uploadBlock(String filePath, byte[] chunk, String sasToken, String blockId) {
        try {
            String encodedSasToken = URLEncoder.encode(sasToken, "UTF-8");
            URL url = new URL(filePath + "?comp=block&blockid=" + Base64.encodeToString(blockId.getBytes(), Base64.NO_WRAP) + "&sv=" + encodedSasToken);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("PUT");
            connection.setRequestProperty("x-ms-blob-type", "BlockBlob");
            connection.setDoOutput(true);

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(chunk);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_CREATED) {
                logErrorResponse(connection);
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Upload Block Error: " + e.getMessage());
            return false;
        }
    }

    private boolean commitBlockList(String filePath, String sasToken, String blockListXml) {
        try {
            String encodedSasToken = URLEncoder.encode(sasToken, "UTF-8");
            URL url = new URL(filePath + "?comp=blocklist&sv=" + encodedSasToken);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("PUT");
            connection.setRequestProperty("Content-Type", "application/xml");
            connection.setDoOutput(true);

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(blockListXml.getBytes());
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_CREATED) {
                logErrorResponse(connection);
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Commit Block List Error: " + e.getMessage());
            return false;
        }
    }

    private boolean commitUpload(String postId, String fileMime, String originalName, String fileUri) {
        try {
            // Create the URL for the API endpoint, with the postId as a parameter
            URL url = new URL("https://personal-fjlz3d21.outsystemscloud.com/uploads/rest/a/commit?postid=" + URLEncoder.encode(postId, "UTF-8"));
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            // Configure the HTTP request (POST)
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);

            // Create the JSON object to send in the request body
            JSONObject json = new JSONObject();
            json.put("filemime", fileMime);
            json.put("originalname", originalName);
            json.put("URI", fileUri);

            // Send the JSON data in the request body
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(json.toString().getBytes());
            }

            // Get the response code and check if it was successful
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                // If the response was not HTTP OK, log the error response
                logErrorResponse(connection);
                return false;
            }

            return true; // Return true if the API call was successful
        } catch (Exception e) {
            // Log the error and return false in case of an exception
            Log.e(TAG, "Commit Upload Error: " + e.getMessage());
            return false;
        }
    }

    private void callCompletionAPI(String postId, CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                URL url = new URL("https://personal-fjlz3d21.outsystemscloud.com/arabicschooln/rest/post/completed?id=" + URLEncoder.encode(postId, "UTF-8"));
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    callbackContext.success("All files uploaded and completion API called successfully.");
                } else {
                    callbackContext.error("Completion API call failed with response code: " + responseCode);
                }
            } catch (Exception e) {
                callbackContext.error("Completion API Error: " + e.getMessage());
            }
        });
    }

    private void logErrorResponse(HttpURLConnection connection) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()))) {
            String line;
            StringBuilder response = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            Log.e(TAG, "Error Response: " + response.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error Logging Response: " + e.getMessage());
        }
    }

    private void initializeNotification(int totalFiles) {
        notificationManager = (NotificationManager) cordova.getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "File Upload", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }

        notificationBuilder = new NotificationCompat.Builder(cordova.getContext(), CHANNEL_ID)
                .setContentTitle("File Upload")
                .setContentText("Uploading files")
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(totalFiles, 0, false)
                .setOngoing(true);

        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
    }

    private void updateNotification(int filesUploaded, int totalFiles) {
        notificationBuilder.setProgress(totalFiles, filesUploaded, false)
                .setContentText("Uploaded " + filesUploaded + " of " + totalFiles + " files");
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
    }

    private void removeNotification() {
        notificationManager.cancel(NOTIFICATION_ID);
    }

    private void showErrorNotification(String errorMessage) {
        Notification errorNotification = new NotificationCompat.Builder(cordova.getContext(), CHANNEL_ID)
                .setContentTitle("File Upload Failed")
                .setContentText(errorMessage)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();
        notificationManager.notify(NOTIFICATION_ID + 1, errorNotification);  // Use a different ID for the error notification
    }
}
