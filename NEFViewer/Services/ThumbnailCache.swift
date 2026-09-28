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

    /// 只查缓存（内存 + 磁盘），绝不提取 NEF：单图翻页热路径专用，
    /// 未命中时宁缺毋滥——为一张 640 去解析整张 NEF 会把翻页卡成"按了没反应"
    func cached(key: UUID, projectID: UUID) async -> CGImage? {
        let cacheKey = key.uuidString as NSString
        if let cached = memory.object(forKey: cacheKey) { return cached }
        let diskURL = Self.cacheDirectory(projectID: projectID)
            .appendingPathComponent(key.uuidString + ".jpg")
        guard let data = try? Data(contentsOf: diskURL),
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }
        memory.setObject(image, forKey: cacheKey)
        return image
    }

    /// 由已解码大图（3200 标准档）派生 640 网格档并落盘；磁盘已存在则跳过。
    /// 全量预渲染时调用：一次 NEF 解码同时产出两档，网格滚动不再二次解析原图
    func storeDerived(from source: CGImage, key: UUID, projectID: UUID) {
        let cacheKey = key.uuidString as NSString
        if memory.object(forKey: cacheKey) != nil { return }
        let diskURL = Self.cacheDirectory(projectID: projectID)
            .appendingPathComponent(key.uuidString + ".jpg")
        guard !FileManager.default.fileExists(atPath: diskURL.path) else { return }
        guard let thumb = Self.downscale(source, maxPixel: Self.pixelSize) else { return }
        try? FileManager.default.createDirectory(
            at: Self.cacheDirectory(projectID: projectID),
            withIntermediateDirectories: true
        )
        let rep = NSBitmapImageRep(cgImage: thumb)
        if let jpeg = rep.representation(using: .jpeg, properties: [.compressionFactor: 0.85]) {
            try? jpeg.write(to: diskURL, options: .atomic)
        }
        memory.setObject(thumb, forKey: cacheKey)
    }

    nonisolated private static func downscale(_ image: CGImage, maxPixel: Int) -> CGImage? {
        let longest = max(image.width, image.height)
        guard longest > maxPixel else { return image }
        let scale = CGFloat(maxPixel) / CGFloat(longest)
        let size = CGSize(
            width: max(1, Int((CGFloat(image.width) * scale).rounded())),
            height: max(1, Int((CGFloat(image.height) * scale).rounded()))
        )
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let ctx = CGContext(
                  data: nil, width: Int(size.width), height: Int(size.height),
                  bitsPerComponent: 8, bytesPerRow: 0, space: space,
                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
              ) else { return nil }
        ctx.interpolationQuality = .high
        ctx.draw(image, in: CGRect(origin: .zero, size: size))
        return ctx.makeImage()
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
