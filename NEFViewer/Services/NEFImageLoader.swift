import Foundation
import ImageIO
import CoreGraphics

enum NEFImageLoader {
    /// 提取 NEF 内嵌 JPEG 预览（Z8 内嵌预览为全尺寸 8256×5504，提取约 0.1-0.2s）。
    /// maxPixelSize 为 nil 时返回原始尺寸，否则按最长边等比缩小（不会放大）。
    nonisolated static func embeddedPreview(from url: URL, maxPixelSize: Int?) -> CGImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        var options: [String: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways as String: true,
            kCGImageSourceShouldCacheImmediately as String: true,
            kCGImageSourceCreateThumbnailWithTransform as String: true
        ]
        if let maxPixelSize {
            options[kCGImageSourceThumbnailMaxPixelSize as String] = maxPixelSize
        }
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    /// 仅读文件头 EXIF 的拍摄时间（不解码图像，远快于提取预览）
    nonisolated static func captureDate(from url: URL) -> Date? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any],
              let exif = props[kCGImagePropertyExifDictionary as String] as? [String: Any],
              let dto = exif[kCGImagePropertyExifDateTimeOriginal as String] as? String else {
            return nil
        }
        return exifDateFormatter.date(from: dto)
    }

    nonisolated static let exifDateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy:MM:dd HH:mm:ss"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()
}

/// 文件选择界面的拍摄时间缓存（内存级，只读可见行，开销远小于缩略图）
actor SourceDateCache {
    static let shared = SourceDateCache()

    private var cache: [String: Date?] = [:]
    private var inFlight: [String: Task<Date?, Never>] = [:]

    private init() {}

    func captureDate(for url: URL) async -> Date? {
        let key = url.path
        if let cached = cache[key] { return cached }
        if let task = inFlight[key] { return await task.value }
        let task = Task<Date?, Never>(priority: .userInitiated) {
            NEFImageLoader.captureDate(from: url)
        }
        inFlight[key] = task
        let result = await task.value
        inFlight[key] = nil
        cache[key] = result
        return result
    }
}
