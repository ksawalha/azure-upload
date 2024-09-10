import Foundation
import UserNotifications

@objc(AzureUpload) class AzureUpload : CDVPlugin {

    @objc(uploadFiles:)
    func uploadFiles(command: CDVInvokedUrlCommand) {
        let fileList = command.arguments[0] as! [[String: Any]]
        let sasToken = command.arguments[1] as! String
        let postId = command.arguments[2] as! String
        
        let totalFiles = fileList.count
        for (index, file) in fileList.enumerated() {
            let fileName = file["filename"] as! String
            let fileMime = file["filemime"] as! String
            let fileBinary = Data(base64Encoded: file["filebinary"] as! String)!

            let uploadSuccess = uploadChunked(fileName: fileName, fileMime: fileMime, fileBinary: fileBinary, sasToken: sasToken, fileIndex: index + 1, totalFiles: totalFiles)
            if !uploadSuccess {
                self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Failed to upload file: \(fileName)"), callbackId: command.callbackId)
                return
            }
        }

        // Call completion API after all files are uploaded
        callCompletionAPI(postId: postId, callbackId: command.callbackId)
    }

    func uploadChunked(fileName: String, fileMime: String, fileBinary: Data, sasToken: String, fileIndex: Int, totalFiles: Int) -> Bool {
        let chunkSize = 1024 * 1024 // 1MB chunk size
        let totalSize = fileBinary.count
        let numChunks = Int(ceil(Double(totalSize) / Double(chunkSize)))

        for i in 0..<numChunks {
            let start = i * chunkSize
            let end = min(start + chunkSize, totalSize)
            let chunk = fileBinary.subdata(in: start..<end)

            let urlStr = "https://yourazureblobstorageurl/\(fileName)?sv=\(sasToken)"
            guard let url = URL(string: urlStr) else { return false }
            var request = URLRequest(url: url)
            request.httpMethod = "PUT"
            request.setValue("BlockBlob", forHTTPHeaderField: "x-ms-blob-type")
            request.httpBody = chunk

            let (data, response, error) = URLSession.shared.syncRequest(with: request)
            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode != 201 {
                return false
            }

            // Update progress notification
            let progress = Float(i + 1) / Float(numChunks) * 100
            sendProgressNotification(fileIndex: fileIndex, totalFiles: totalFiles, progress: progress)
        }
        return true
    }

    func sendProgressNotification(fileIndex: Int, totalFiles: Int, progress: Float) {
        let content = UNMutableNotificationContent()
        content.title = "Uploading File \(fileIndex) of \(totalFiles)"
        content.body = "Upload progress: \(Int(progress))%"
        content.sound = UNNotificationSound.default

        let request = UNNotificationRequest(identifier: "uploadProgress", content: content, trigger: nil)
        let notificationCenter = UNUserNotificationCenter.current()
        notificationCenter.add(request, withCompletionHandler: nil)
    }

    func callCompletionAPI(postId: String, callbackId: String) {
        let urlStr = "https://personal-fjlz3d21.outsystemscloud.com/arabicschooln/rest/post/completed?id=\(postId)"
        guard let url = URL(string: urlStr) else { return }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"

        let (data, response, error) = URLSession.shared.syncRequest(with: request)
        if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
            self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: "Upload and API call successful"), callbackId: callbackId)
        } else {
            self.commandDelegate!.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "API failed"), callbackId: callbackId)
        }
    }
}

extension URLSession {
    func syncRequest(with request: URLRequest) -> (Data?, URLResponse?, Error?) {
        let semaphore = DispatchSemaphore(value: 0)
        var data: Data?
        var response: URLResponse?
        var error: Error?

        let task = self.dataTask(with: request) { taskData, taskResponse, taskError in
            data = taskData
            response = taskResponse
            error = taskError
            semaphore.signal()
        }

        task.resume()
        semaphore.wait()

        return (data, response, error)
    }
}
