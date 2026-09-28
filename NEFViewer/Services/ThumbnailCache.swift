import Foundation
import AppKit

/// 网格缩略图缓存：内存（NSCache）+ 磁盘（~/Library/Caches/NEFViewer/thumbs/<projectID>/）。
/// 磁盘缓存按 Photo.id 命名，删除项目时整体清理。
actor ThumbnailCache {
    static let shared = ThumbnailCache()
    static let pixelSize = 640

    private let memory = NSCache<NSString, CGImage>()
    private var inFlight: [UUID: Task<CGImage?, Never>] = [:]

    private init() {
        memory.countLimit = 400
    }

    nonisolated static func cacheDirectory(projectID: UUID) -> URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
        return base.appendingPathComponent("NEFViewer/thumbs/\(projectID.uuidString)", isDirectory: true)
    }

    nonisolated static func removeDiskCache(projectID: UUID) {
        try? FileManager.default.removeItem(at: cacheDirectory(projectID: projectID))
    }

    func thumbnail(for url: URL, key: UUID, projectID: UUID) async -> CGImage? {
        let cacheKey = key.uuidString as NSString
        if let cached = memory.object(forKey: cacheKey) { return cached }
        if let task = inFlight[key] { return await task.value }

        let task = Task<CGImage?, Never>(priority: .userInitiated) {
            let diskURL = Self.cacheDirectory(projectID: projectID).appendingPathComponent(key.uuidString + ".jpg")

            if let data = try? Data(contentsOf: diskURL),
               let source = CGImageSourceCreateWithData(data as CFData, nil),
               let image = CGImageSourceCreateImageAtIndex(source, 0, nil) {
                self.memory.setObject(image, forKey: cacheKey)
                return image
            }

            guard let image = NEFImageLoader.embeddedPreview(from: url, maxPixelSize: Self.pixelSize) else {
                return nil
            }
            try? FileManager.default.createDirectory(
                at: Self.cacheDirectory(projectID: projectID),
                withIntermediateDirectories: true
            )
            let rep = NSBitmapImageRep(cgImage: image)
            if let jpeg = rep.representation(using: .jpeg, properties: [.compressionFactor: 0.85]) {
                try? jpeg.write(to: diskURL, options: .atomic)
            }
            self.memory.setObject(image, forKey: cacheKey)
            return image
        }
        inFlight[key] = task
        let result = await task.value
        inFlight[key] = nil
        return result
    }
}
