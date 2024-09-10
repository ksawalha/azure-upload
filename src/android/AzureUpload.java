package com.karamsawalha.arabicschool;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class AzureUpload extends CordovaPlugin {

    private static final String CHANNEL_ID = "upload_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final int CHUNK_SIZE = 1024 * 1024; // 1MB
    private static final String BASE_URL = "https://arabicschool.blob.core.windows.net/arabicschool";

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
        final int totalFiles = fileList.length();
        final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(4);

        for (int i = 0; i < totalFiles; i++) {
            try {
                JSONObject file = fileList.getJSONObject(i);
                String destination = file.getString("destination"); // Get the destination path
                String fileName = file.getString("filename");
                String fileMime = file.getString("filemime");
                String fileBinaryString = file.getString("filebinary");
                byte[] fileBinary = Base64.decode(fileBinaryString, Base64.DEFAULT);

                String filePath = BASE_URL + destination;

                executor.execute(() -> uploadFile(filePath, fileName, fileBinary, sasToken, i + 1, totalFiles, postId, callbackContext));
            } catch (JSONException e) {
                callbackContext.error("Error parsing file data");
                return;
            }
        }

        executor.shutdown();
    }

    private void uploadFile(String filePath, String fileName, byte[] fileBinary, String sasToken, int fileIndex, int totalFiles, String postId, CallbackContext callbackContext) {
        int totalSize = fileBinary.length;
        int numChunks = (int) Math.ceil((double) totalSize / CHUNK_SIZE);
        int[] uploadedChunks = {0};

        for (int i = 0; i < numChunks; i++) {
            int start = i * CHUNK_SIZE;
            int end = Math.min(start + CHUNK_SIZE, totalSize);
            byte[] chunk = new byte[end - start];
            System.arraycopy(fileBinary, start, chunk, 0, end - start);

            uploadChunk(filePath, chunk, sasToken, fileIndex, totalFiles, uploadedChunks, numChunks, postId, callbackContext);
        }
    }

    private void uploadChunk(String filePath, byte[] chunk, String sasToken, int fileIndex, int totalFiles, int[] uploadedChunks, int numChunks, String postId, CallbackContext callbackContext) {
        try {
            URL url = new URL(filePath + "?sv=" + sasToken);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoOutput(true);
            connection.setRequestMethod("PUT");
            connection.setRequestProperty("x-ms-blob-type", "BlockBlob");

            OutputStream outputStream = connection.getOutputStream();
            outputStream.write(chunk);
            outputStream.close();

            int responseCode = connection.getResponseCode();
            if (responseCode == 201) {
                uploadedChunks[0]++;
                int progress = (int) ((double) uploadedChunks[0] / numChunks * 100);
                updateNotification(fileIndex, totalFiles, progress);

                if (uploadedChunks[0] == numChunks) {
                    callCompletionAPI(postId, callbackContext);
                }
            } else {
                Log.e("AzureUpload", "Failed to upload chunk: " + responseCode);
            }
        } catch (IOException e) {
            Log.e("AzureUpload", "Error uploading chunk", e);
        }
    }

    private void updateNotification(int fileIndex, int totalFiles, int progress) {
        Context context = cordova.getActivity().getApplicationContext();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Upload Progress", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle("Uploading File " + fileIndex + " of " + totalFiles)
                .setContentText("Upload progress: " + progress + "%")
                .setPriority(NotificationCompat.PRIORITY_LOW);

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
    }

    private void callCompletionAPI(String postId, CallbackContext callbackContext) {
        try {
            URL url = new URL("https://personal-fjlz3d21.outsystemscloud.com/arabicschooln/rest/post/completed?id=" + postId);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                callbackContext.success("All files uploaded and completion API called successfully.");
            } else {
                callbackContext.error("API call failed with response code: " + responseCode);
            }
        } catch (IOException e) {
            callbackContext.error("Error calling completion API");
        }
    }
}
