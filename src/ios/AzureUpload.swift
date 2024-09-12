import Foundation
import UserNotifications
import AVFoundation
import UIKit
import ImageIO
import MobileCoreServices

@objc(AzureUpload) class AzureUpload: CDVPlugin {

    @objc(uploadFiles:command:)
    func uploadFiles(command: CDVInvokedUrlCommand) {
        guard
            let fileList = command.arguments[0] as? [[String: Any]],
            let sasToken = command.arguments[1] as? String,
            let postId = command.arguments[2] as? String
        else {
            self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Invalid arguments"), callbackId: command.callbackId)
            return
        }

        let totalFiles = fileList.count

        // Request notification permission
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, error in
            if granted {
                self.uploadFiles(fileList: fileList, sasToken: sasToken, postId: postId, totalFiles: totalFiles, command: command)
            } else {
                self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Notification permission not granted."), callbackId: command.callbackId)
            }
        }
    }

    func uploadFiles(fileList: [[String: Any]], sasToken: String, postId: String, totalFiles: Int, command: CDVInvokedUrlCommand) {
        DispatchQueue.global().async {
            var filesUploaded = 0

            for (index, file) in fileList.enumerated() {
                guard
                    let destination = file["destination"] as? String,
                    let fileName = file["filename"] as? String,
                    let originalName = file["originalname"] as? String,
                    let fileMime = file["filemime"] as? String,
                    let fileBinaryString = file["filebinary"] as? String,
                    let fileBinary = Data(base64Encoded: fileBinaryString)
                else {
                    self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Invalid file data"), callbackId: command.callbackId)
                    return
                }

                // Process the file and update the metadata if necessary
                self.processFile(file: [
                    "destination": destination,
                    "filename": fileName,
                    "originalname": originalName,
                    "filemime": fileMime,
                    "filebinary": fileBinaryString
                ]) { processedData in
                    guard let processedData = processedData else {
                        self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Failed to process file"), callbackId: command.callbackId)
                        return
                    }

                    let filePath = "https://arabicschool.blob.core.windows.net/arabicschool" + destination
                    let updatedFileName = (fileName as NSString).deletingPathExtension + ".jpeg"
                    let updatedOriginalName = (originalName as NSString).deletingPathExtension + ".jpeg"

                    self.uploadChunked(filePath: filePath, fileBinary: processedData, sasToken: sasToken, fileIndex: index + 1, totalFiles: totalFiles, fileMime: "image/jpeg", originalName: updatedOriginalName, postId: postId, command: command) { success in
                        if success {
                            filesUploaded += 1
                            // Check if all files have been uploaded
                            if filesUploaded == totalFiles {
                                self.callCompletionAPI(postId: postId, callbackId: command.callbackId)
                            }
                        } else {
                            self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Failed to upload file: \(fileName)"), callbackId: command.callbackId)
                            return
                        }
                    }
                }
            }
        }
    }

    func uploadChunked(filePath: String, fileBinary: Data, sasToken: String, fileIndex: Int, totalFiles: Int, fileMime: String, originalName: String, postId: String, command: CDVInvokedUrlCommand, completion: @escaping (Bool) -> Void) {
        let chunkSize = 1024 * 1024 // 1MB chunk size
        let totalSize = fileBinary.count
        let numChunks = Int(ceil(Double(totalSize) / Double(chunkSize)))
        var uploadedChunks = 0

        let dispatchGroup = DispatchGroup()

        for i in 0..<numChunks {
            dispatchGroup.enter()
            let start = i * chunkSize
            let end = min(start + chunkSize, totalSize)
            let chunk = fileBinary.subdata(in: start..<end)

            let uploadSuccess = self.uploadChunk(filePath: filePath, chunk: chunk, sasToken: sasToken)
            if uploadSuccess {
                uploadedChunks += 1
                let progress = Int(Double(uploadedChunks) / Double(numChunks) * 100)
                self.updateNotification(fileIndex: fileIndex, totalFiles: totalFiles, progress: progress)
            } else {
                dispatchGroup.leave()
                completion(false)
                return
            }
            dispatchGroup.leave()
        }

        dispatchGroup.notify(queue: .main) {
            self.commitUpload(postId: postId, fileMime: fileMime, originalName: originalName, fileUri: filePath, command: command, completion: completion)
        }
    }

    func uploadChunk(filePath: String, chunk: Data, sasToken: String) -> Bool {
        var request = URLRequest(url: URL(string: filePath + "?sv=" + sasToken)!)
        request.httpMethod = "PUT"
        request.setValue("BlockBlob", forHTTPHeaderField: "x-ms-blob-type")
        request.httpBody = chunk

        var success = false
        let semaphore = DispatchSemaphore(value: 0)

        let task = URLSession.shared.dataTask(with: request) { (_, response, error) in
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 201 {
                success = true
            } else {
                print("Failed to upload chunk: \(String(describing: error))")
            }
            semaphore.signal()
        }

        task.resume()
        semaphore.wait()

        return success
    }

    func updateNotification(fileIndex: Int, totalFiles: Int, progress: Int) {
        let content = UNMutableNotificationContent()
        content.title = "Uploading File \(fileIndex) of \(totalFiles)"
        content.body = "Upload progress: \(progress)%"
        content.sound = UNNotificationSound.default

        let request = UNNotificationRequest(identifier: "upload_progress", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    func commitUpload(postId: String, fileMime: String, originalName: String, fileUri: String, command: CDVInvokedUrlCommand, completion: @escaping (Bool) -> Void) {
        let url = URL(string: "https://personal-fjlz3d21.outsystemscloud.com/uploads/rest/a/commit?postid=" + postId)!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let json: [String: Any] = [
            "filemime": fileMime,
            "originalname": originalName,
            "URI": fileUri
        ]
        request.httpBody = try? JSONSerialization.data(withJSONObject: json, options: [])

        let task = URLSession.shared.dataTask(with: request) { (_, response, error) in
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                completion(true)
            } else {
                print("Failed to commit file: \(String(describing: error))")
                completion(false)
            }
        }

        task.resume()
    }

    func callCompletionAPI(postId: String, callbackId: String) {
        let url = URL(string: "https://personal-fjlz3d21.outsystemscloud.com/arabicschooln/rest/post/completed?id=" + postId)!
        var request = URLRequest(url: url)
        request.httpMethod = "GET"

        let task = URLSession.shared.dataTask(with: request) { (_, response, error) in
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: "All files uploaded and completion API called successfully."), callbackId: callbackId)
            } else {
                self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Completion API call failed with response code: \(String(describing: (response as? HTTPURLResponse)?.statusCode))"), callbackId: callbackId)
            }
        }

        task.resume()
    }

    func processFile(file: [String: Any], completion: @escaping (Data?) -> Void) {
        guard let mimeType = file["filemime"] as? String else {
            completion(nil)
            return
        }
        
        if mimeType.starts(with: "video") {
            // Process video file
            guard
                let fileBinary = file["filebinary"] as? String,
                let fileData = Data(base64Encoded: fileBinary)
            else {
                completion(nil)
                return
            }
            
            let videoFilePath = "https://arabicschool.blob.core.windows.net/arabicschool" + (file["destination"] as? String ?? "")
            extractThumbnail(from: fileData) { thumbnailData in
                // Assuming the video thumbnail is not required to be in WebP
                completion(thumbnailData)
            }
        } else if mimeType.starts(with: "image") {
            // Process image file
            guard
                let fileBinary = file["filebinary"] as? String,
                let fileData = Data(base64Encoded: fileBinary),
                let image = UIImage(data: fileData)
            else {
                completion(nil)
                return
            }
            
            if let jpegData = convertImageToJPEG(image) {
                completion(jpegData)
            } else {
                completion(nil)
            }
        } else {
            // Handle other file types if needed
            completion(Data(base64Encoded: file["filebinary"] as! String))
        }
    }

    func convertImageToJPEG(_ image: UIImage, quality: CGFloat = 0.7) -> Data? {
        // Convert the UIImage to JPEG format with the specified compression quality
        return image.jpegData(compressionQuality: quality)
    }
    
    func extractThumbnail(from videoData: Data, completion: @escaping (Data?) -> Void) {
        let asset = AVAsset(data: videoData)
        let imageGenerator = AVAssetImageGenerator(asset: asset)
        imageGenerator.appliesPreferredTrackTransform = true

        let time = CMTime(seconds: 1, preferredTimescale: 600)
        imageGenerator.copyCGImage(at: time, actualTime: nil) { (cgImage, error) in
            if let cgImage = cgImage {
                let image = UIImage(cgImage: cgImage)
                let jpegData = self.convertImageToJPEG(image)
                completion(jpegData)
            } else {
                completion(nil)
            }
        }
    }
}
