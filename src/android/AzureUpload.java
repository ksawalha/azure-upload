package com.azure.upload;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.util.Base64;
import android.util.Log;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import androidx.core.app.NotificationCompat;
import androidx.annotation.NonNull;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
                    String filePath = file.getString("filepath");  // Changed to get file path instead of binary

                    // Debugging output
                    Log.d(TAG, "Destination: " + destination);
                    Log.d(TAG, "Original Name: " + originalName);
                    Log.d(TAG, "File MIME: " + fileMime);
                    Log.d(TAG, "File Path: " + filePath);  // Log the file path for debugging

                    // Read the file data from the file path
                    byte[] fileData = readFileFromPath(filePath);

                    // Handle image compression if needed
                    if (fileMime.startsWith("image/")) {
                        fileData = compressImage(fileData);
                        originalName = originalName.replaceFirst("[.][^.]+$", ".jpg"); // Change file extension to .jpg
                        fileMime = "image/jpeg";
                    } else if (fileMime.startsWith("video/")) {
                        fileData = extractThumbnailFromVideo(fileData);
                        originalName = originalName.replaceFirst("[.][^.]+$", ".jpg"); // Change file extension to .jpg
                        fileMime = "image/jpeg";
                    }

                    String fullFilePath = "https://arabicschool.blob.core.windows.net/arabicschool" + destination;

                    boolean success = uploadChunked(fullFilePath, fileData, sasToken, originalName, fileMime, originalName, postId);
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

    private byte[] readFileFromPath(String filePath) {
        try {
            File file = new File(filePath);
            FileInputStream fileInputStream = new FileInputStream(file);
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, bytesRead);
            }

            fileInputStream.close();
            return byteArrayOutputStream.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "File Read Error: " + e.getMessage());
            return new byte[0]; // Return an empty array if file reading fails
        }
    }

    private byte[] compressImage(byte[] imageData) {
        try {
            // Decode image data to Bitmap
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);

            // Compress the bitmap to JPEG format
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream); // 75 is the quality (0-100)
            return outputStream.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Image Compression Error: " + e.getMessage());
            return imageData; // Return original data if compression fails
        }
    }

    private byte[] extractThumbnailFromVideo(byte[] videoData) {
        try {
            // Save the video data to a temporary file
            File videoFile = new File(cordova.getActivity().getCacheDir(), "tempVideo.mp4");
            FileOutputStream fos = new FileOutputStream(videoFile);
            fos.write(videoData);
            fos.close();

            // Extract thumbnail from video
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoFile.getAbsolutePath());
            Bitmap bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);

            // Clean up
            videoFile.delete();

            if (bitmap != null) {
                // Compress the bitmap to JPEG format
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream); // 75 is the quality (0-100)
                return outputStream.toByteArray();
            } else {
                Log.e(TAG, "Failed to extract thumbnail from video.");
                return new byte[0]; // Return empty array if thumbnail extraction fails
            }
        } catch (Exception e) {
            Log.e(TAG, "Thumbnail Extraction Error: " + e.getMessage());
            return new byte[0]; // Return empty array if extraction fails
        }
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
            connection.setRequestProperty("x-ms-blob-type", "BlockBlob");
            connection.setRequestProperty("Content-Type", "application/xml");
            connection.setDoOutput(true);

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(blockListXml.getBytes("UTF-8"));
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

    private boolean commitUpload(String postId, String fileMime, String originalName, String filePath) {
        try {
            URL url = new URL("https://personal-fjlz3d21.outsystemscloud.com/uploads/rest/a/commit?postid=" + URLEncoder.encode(postId, "UTF-8")
                    + "&mime=" + URLEncoder.encode(fileMime, "UTF-8")
                    + "&filename=" + URLEncoder.encode(originalName, "UTF-8")
                    + "&path=" + URLEncoder.encode(filePath, "UTF-8"));
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                return true;
            } else {
                logErrorResponse(connection);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Commit Upload Error: " + e.getMessage());
            return false;
        }
    }

    private void logErrorResponse(HttpURLConnection connection) throws Exception {
        InputStream errorStream = connection.getErrorStream();
        if (errorStream != null) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream));
            StringBuilder errorResponse = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                errorResponse.append(line);
            }
            Log.e(TAG, "Error Response: " + errorResponse.toString());
        }
    }

    private void callCompletionAPI(String postId, CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                URL url = new URL("https://personal-fjlz3d21.outsystemscloud.com/uploads/rest/a/complete?postid=" + URLEncoder.encode(postId, "UTF-8"));
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    callbackContext.success("Upload completed successfully");
                } else {
                    logErrorResponse(connection);
                    callbackContext.error("Failed to complete upload");
                }
            } catch (Exception e) {
                callbackContext.error("Completion API Error: " + e.getMessage());
            }
        });
    }

    private void initializeNotification(int totalFiles) {
        Context context = cordova.getActivity().getApplicationContext();
        notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Upload Progress", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }

        notificationBuilder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("File Upload")
                .setContentText("0/" + totalFiles + " files uploaded")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true);
    }

    private void updateNotification(int filesUploaded, int totalFiles) {
        if (notificationBuilder != null && notificationManager != null) {
            notificationBuilder.setContentText(filesUploaded + "/" + totalFiles + " files uploaded");
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        }
    }

    private void removeNotification() {
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }
    }

    private void showErrorNotification(String message) {
        Context context = cordova.getActivity().getApplicationContext();
        NotificationCompat.Builder errorNotificationBuilder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Upload Error")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, errorNotificationBuilder.build());
        }
    }
}
