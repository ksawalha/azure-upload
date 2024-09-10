import Foundation
import UserNotifications

@objc(AzureUpload) class AzureUpload : CDVPlugin {

    @objc(uploadFiles:)
    func uploadFiles(command: CDVInvokedUrlCommand) {
        let fileList = command.arguments[0] as! [[String: Any]]
        let sasToken = command.arguments[1] as! String
        let postId = command.arguments[2] as! String
        
        let totalFiles = fileList.count
        
        // Ask for notification permission
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { (granted, error) in
            if granted {
                self.uploadFiles(fileList: fileList, sasToken: sasToken, postId: postId, totalFiles: totalFiles, command: command)
            } else {
                self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Notification permission not granted."), callbackId: command.callbackId)
            }
        }
    }

    func uploadFiles(fileList: [[String: Any]], sasToken: String, postId: String, totalFiles: Int, command: CDVInvokedUrlCommand) {
        DispatchQueue.global().async {
            for (index, file) in fileList.enumerated() {
                let fileName = file["filename"] as! String
                let fileMime = file["filemime"] as! String
                guard let fileBinaryString = file["filebinary"] as? String,
                      let fileBinary = Data(base64Encoded: fileBinaryString) else {
                    self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Invalid file data"), callbackId: command.callbackId)
                    return
                }

                let uploadSuccess = self.uploadChunked(fileName: fileName, fileMime: fileMime, fileBinary: fileBinary, sasToken: sasToken, fileIndex: index + 1, totalFiles: totalFiles)
                if !uploadSuccess {
                    self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Failed to upload file: \(fileName)"), callbackId: command.callbackId)
                    return
                }
            }
            
            // Call completion API after all files are uploaded
            self.callCompletionAPI(postId: postId, callbackId: command.callbackId)
        }
    }

    func uploadChunked(fileName: String, fileMime: String, fileBinary: Data, sasToken: String, fileIndex: Int, totalFiles: Int) -> Bool {
        let chunkSize = 1024 * 1024 // 1MB chunk size
        let totalSize = fileBinary.count
        let numChunks = Int(ceil(Double(totalSize) / Double(chunkSize)))
        var uploadedChunks = 0

        for i in 0..<numChunks {
            let start = i * chunkSize
            let end = min(start + chunkSize, totalSize)
            let chunk = fileBinary.subdata(in: start..<end)
            
            guard let url = URL(string: "https://yourazureblobstorageurl/\(fileName)?sv=\(sasToken)") else {
                print("Invalid URL")
                return false
            }
            
            var request = URLRequest(url: url)
            request.httpMethod = "PUT"
            request.setValue("BlockBlob", forHTTPHeaderField: "x-ms-blob-type")
            
            let uploadTask = URLSession.shared.uploadTask(with: request, from: chunk) { data, response, error in
                if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode != 201 {
                    print("Failed to upload chunk: \(httpResponse.statusCode)")
                    return
                }
                
                uploadedChunks += 1
                let progress = Int((Double(uploadedChunks) / Double(numChunks)) * 100)
                self.updateNotificationProgress(fileIndex: fileIndex, totalFiles: totalFiles, progress: progress)
            }
            uploadTask.resume()
        }
        
        return true
    }
    
    func updateNotificationProgress(fileIndex: Int, totalFiles: Int, progress: Int) {
        let content = UNMutableNotificationContent()
        content.title = "Uploading File \(fileIndex) of \(totalFiles)"
        content.body = "Upload progress: \(progress)%"
        content.sound = UNNotificationSound.default
        
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }

    func callCompletionAPI(postId: String, callbackId: String) {
        let apiUrl = "https://personal-fjlz3d21.outsystemscloud.com/arabicschooln/rest/post/completed?id=" + postId
        
        guard let url = URL(string: apiUrl) else {
            self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Invalid API URL"), callbackId: callbackId)
            return
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        
        let task = URLSession.shared.dataTask(with: request) { data, response, error in
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: "All files uploaded and completion API called successfully."), callbackId: callbackId)
            } else {
                self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "API call failed."), callbackId: callbackId)
            }
        }
        task.resume()
    }
}
