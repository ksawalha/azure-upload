package com.azure.upload;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import javax.net.ssl.HttpsURLConnection;

public class AzureUpload extends CordovaPlugin {

    private static final String CHANNEL_ID = "AzureUploadChannel";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private CallbackContext callbackContext;
    private JSONArray fileList;
    private String sasToken;
    private String postId;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        this.callbackContext = callbackContext;
        if (action.equals("uploadFiles")) {
            this.fileList = args.getJSONArray(0);
            this.sasToken = args.getString(1);
            this.postId = args.getString(2);

            if (!hasPermissions()) {
                requestPermissions();
            } else {
                startUpload();
            }
            return true;
        }
        return false;
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(cordova.getActivity(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(cordova.getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(cordova.getActivity(), Manifest.permission.FOREGROUND_SERVICE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(cordova.getActivity(), new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.FOREGROUND_SERVICE
        }, PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startUpload();
            } else {
                callbackContext.error("Permission denied.");
            }
        }
    }

    private void startUpload() {
        try {
            cordova.getActivity().startService(new Intent(cordova.getActivity(), UploadService.class));
            uploadFiles();
        } catch (Exception e) {
            callbackContext.error("Upload failed: " + e.getMessage());
        }
    }

    private void uploadFiles() throws JSONException, IOException {
        for (int i = 0; i < fileList.length(); i++) {
            JSONObject file = fileList.getJSONObject(i);
            String fileName = file.getString("filename");
            String fileMime = file.getString("filemime");
            byte[] fileBinary = file.getString("filebinary").getBytes();

            // Upload each file in chunks
            boolean uploadSuccess = uploadChunked(fileName, fileMime, fileBinary, sasToken, i + 1, fileList.length());
            if (!uploadSuccess) {
                callbackContext.error("Failed to upload file: " + fileName);
                stopForegroundService();
                return;
            }
        }

        // All files uploaded successfully, call completion API
        callCompletionAPI(postId);
        stopForegroundService();
    }

    private boolean uploadChunked(String fileName, String fileMime, byte[] fileBinary, String sasToken, int fileIndex, int totalFiles) throws IOException {
        int chunkSize = 1024 * 1024; // 1MB
        int totalSize = fileBinary.length;
        int numChunks = (int) Math.ceil((double) totalSize / chunkSize);
        int uploadedChunks = 0;

        for (int i = 0; i < numChunks; i++) {
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, totalSize);
            byte[] chunk = new byte[end - start];
            System.arraycopy(fileBinary, start, chunk, 0, chunk.length);

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
                return false;
            }

            // Update upload progress in the notification
            uploadedChunks++;
            int progress = (int) (((float) uploadedChunks / numChunks) * 100);
            updateNotificationProgress(fileIndex, totalFiles, progress);
        }
        return true;
    }

    private void updateNotificationProgress(int fileIndex, int totalFiles, int progress) {
        NotificationManager notificationManager = (NotificationManager) cordova.getActivity().getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Upload Channel", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(cordova.getActivity(), CHANNEL_ID)
                .setContentTitle("Uploading File " + fileIndex + " of " + totalFiles)
                .setContentText("Upload progress: " + progress + "%")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setProgress(100, progress, false)
                .setOngoing(true);

        notificationManager.notify(1, builder.build());
    }

    private void stopForegroundService() {
        NotificationManager notificationManager = (NotificationManager) cordova.getActivity().getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(1);
    }

    private void callCompletionAPI(String postId) {
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
