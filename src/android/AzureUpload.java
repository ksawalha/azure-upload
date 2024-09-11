package com.karamsawalha.arabicschool;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.MediaMetadataRetriever;
import android.os.Build;
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

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class AzureUpload extends CordovaPlugin {

    private static final String CHANNEL_ID = "upload_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final int CHUNK_SIZE = 1024 * 1024; // 1MB
    private static final String BASE_URL = "https://arabicschool.blob.core.windows.net/arabicschool";

    private int totalFilesUploaded = 0;
    private int totalFiles;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("uploadFiles")) {
            JSONArray fileList = args.getJSONArray(0);
            String sasToken = args.getString(1);
            String postId = args.getString(2);
            totalFiles = fileList.length();
            uploadFiles(fileList, sasToken, postId, callbackContext);
            return true;
        }
        return false;
    }

    private void uploadFiles(JSONArray fileList, String sasToken, String postId, CallbackContext callbackContext) {
        final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(4);

        for (int i = 0; i < totalFiles; i++) {
            try {
                JSONObject file = fileList.getJSONObject(i);
                String destination = file.getString("destination");
                String fileName = file.getString("filename");
                String originalName = file.getString("originalname"); // Include original name
                String fileMime = file.getString("filemime");
                String fileBinaryString = file.getString("filebinary");
                byte[] fileBinary = Base64.decode(fileBinaryString, Base64.DEFAULT);

                String filePath = BASE_URL + destination;

                executor.execute(() -> {
                    try {
                        // Handle file based on type (video, image, or others)
                        handleFileUpload(filePath, fileName, originalName, fileBinary, fileMime, sasToken, i + 1, totalFiles, postId, callbackContext);
                    } catch (Exception e) {
                        callbackContext.error("Error processing file: " + e.getMessage());
                    }
                });
            } catch (JSONException e) {
                callbackContext.error("Error parsing file data");
                return;
            }
        }

        executor.shutdown();
    }

    private void handleFileUpload(String filePath, String fileName, String originalName, byte[] fileBinary, String fileMime, String sasToken, int fileIndex, int totalFiles, String postId, CallbackContext callbackContext) throws IOException {
        if (fileMime.startsWith("video")) {
            // Generate thumbnail for video and upload it
            byte[] thumbnail = generateVideoThumbnail(fileBinary);
            String thumbnailPath = filePath.replace(fileName, "thumbnail/" + originalName.replaceAll("\\.[^.]+$", ".webp"));
            uploadFile(thumbnailPath, thumbnail, "image/webp", sasToken, postId, callbackContext);
        } else if (fileMime.startsWith("image")) {
            // Compress and convert image to WebP
            byte[] compressedImage = compressAndConvertToWebP(fileBinary);
            uploadFile(filePath, compressedImage, "image/webp", sasToken, postId, callbackContext);
        } else {
            // Upload the file as is
            uploadFile(filePath, fileBinary, fileMime, sasToken, postId, callbackContext);
        }
    }

    private byte[] generateVideoThumbnail(byte[] videoData) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(new ByteArrayInputStream(videoData), new HashMap<>());
        Bitmap bitmap = retriever.getFrameAtTime(1000000); // 1 second mark
        retriever.release();
        return bitmapToWebP(bitmap);
    }

    private byte[] compressAndConvertToWebP(byte[] imageData) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
        return bitmapToWebP(bitmap);
    }

    private byte[] bitmapToWebP(Bitmap bitmap) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.WEBP, 80, outputStream);
        return outputStream.toByteArray();
    }

    private void uploadFile(String filePath, byte[] fileBinary, String fileMime, String sasToken, String postId, CallbackContext callbackContext) {
        int totalSize = fileBinary.length;
        int numChunks = (int) Math.ceil((double) totalSize / CHUNK_SIZE);
        int[] uploadedChunks = {0};

        for (int i = 0; i < numChunks; i++) {
            int start = i * CHUNK_SIZE;
            int end = Math.min(start + CHUNK_SIZE, totalSize);
            byte[] chunk = new byte[end - start];
            System.arraycopy(fileBinary, start, chunk, 0, end - start);

            uploadChunk(filePath, chunk, sasToken, fileMime, uploadedChunks, numChunks, postId, callbackContext);
        }
    }

    private void uploadChunk(String filePath, byte[] chunk, String sasToken, String fileMime, int[] uploadedChunks, int numChunks, String postId, CallbackContext callbackContext) {
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
                updateNotification(progress);

                if (uploadedChunks[0] == numChunks) {
                    // File upload completed, commit it to the database
                    String fileUri = filePath;
                    commitUpload(postId, fileMime, fileUri, callbackContext);
                }
            } else {
                Log.e("AzureUpload", "Failed to upload chunk: " + responseCode);
            }
        } catch (IOException e) {
            Log.e("AzureUpload", "Error uploading chunk", e);
        }
    }

    private void updateNotification(int progress) {
        Context context = cordova.getActivity().getApplicationContext();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Upload Progress", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle("Upload Progress")
                .setContentText("Upload progress: " + progress + "%")
                .setPriority(NotificationCompat.PRIORITY_LOW);

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
    }

    private void commitUpload(String postId, String fileMime, String fileUri, CallbackContext callbackContext) {
        try {
            URL url = new URL("https://personal-fjlz3d21.outsystemscloud.com/uploads/rest/a/commit?postid=" + postId);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");

            JSONObject json = new JSONObject();
            json.put("filemime", fileMime);
            json.put("URI", fileUri);

            DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream());
            outputStream.writeBytes(json.toString());
            outputStream.flush();
            outputStream.close();

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                totalFilesUploaded++;
                callbackContext.success("File committed successfully.");
            } else {
                callbackContext.error("Commit failed with response code: " + responseCode);
            }
        } catch (IOException | JSONException e) {
            callbackContext.error("Error committing file: " + e.getMessage());
        }
    }
}
