import Foundation
import UserNotifications
import AVFoundation
import UIKit

@objc(AzureUpload) class AzureUpload: CDVPlugin {
    
    @objc(uploadFiles:)
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

                let filePath = "https://arabicschool.blob.core.windows.net/arabicschool" + destination

                self.uploadChunked(filePath: filePath, fileBinary: fileBinary, sasToken: sasToken, fileIndex: index + 1, totalFiles: totalFiles, fileMime: fileMime, originalName: originalName, postId: postId, command: command) { success in
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
                if let thumbnailData = thumbnailData {
                    let thumbnailFilePath = videoFilePath.replacingOccurrences(of: "video", with: "thumbnail").replacingOccurrences(of: ".mp4", with: ".webp")
                    self.uploadChunked(filePath: thumbnailFilePath, fileBinary: thumbnailData, sasToken: "", fileIndex: 0, totalFiles: 0, fileMime: "image/webp", originalName: "", postId: "") { success in
                        if success {
                            completion(fileData)
                        } else {
                            completion(nil)
                        }
                    }
                } else {
                    completion(nil)
                }
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
            
            if let compressedImageData = compressAndConvertImage(image) {
                completion(compressedImageData)
            } else {
                completion(nil)
            }
        } else {
            completion(Data(base64Encoded: file["filebinary"] as! String))
        }
    }
    
    func extractThumbnail(from videoData: Data, completion: @escaping (Data?) -> Void) {
        let tempFileURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathExtension("mp4")
        
        do {
            try videoData.write(to: tempFileURL)
            
            let asset = AVAsset(url: tempFileURL)
            let assetImageGenerator = AVAssetImageGenerator(asset: asset)
            assetImageGenerator.appliesPreferredTrackTransform = true
            
            assetImageGenerator.copyCGImage(at: CMTime(seconds: 1, preferredTimescale: 600), actualTime: nil) { cgImage, _ in
                if let cgImage = cgImage {
                    let image = UIImage(cgImage: cgImage)
                    if let webpData = self.convertImageToWebP(image) {
                        completion(webpData)
                    } else {
                        completion(nil)
                    }
                } else {
                    completion(nil)
                }
            }
        } catch {
            completion(nil)
        }
    }
    
    func convertImageToWebP(_ image: UIImage) -> Data? {
        // Placeholder for WebP conversion
        return nil
    }
    
    func compressAndConvertImage(_ image: UIImage) -> Data? {
        guard let compressedImage = image.jpegData(compressionQuality: 0.7) else {
            return nil
        }
        return convertImageToWebP(UIImage(data: compressedImage)!)
    }
}
