import Foundation
import AppKit

/// 单图视图预览缓存：
/// - standard：3200px 屏幕适配档。**磁盘持久化**（~/Library/Caches/NEFViewer/previews/<projectID>/），
///   支撑翻页极速与「导入后全量预渲染」；内存 LRU 容量 6 兜底
/// - full：全尺寸内嵌预览（8256×5504，约 180MB 解码后），仅内存、仅保留当前一张，
///   只在放大查看时按需解码；翻页通过 generation 递增使其过期并取消未完成任务
actor PreviewCache {
    static let shared = PreviewCache()

    enum Tier: Sendable {
        case standard
        case full
    }

    private var standard: [UUID: CGImage] = [:]
    private var standardOrder: [UUID] = []
    private let standardCapacity = 6
    private var full: (id: UUID, image: CGImage)?
    private var inFlight: [String: Task<CGImage?, Never>] = [:]
    private var generation = 0

    private init() {}

    nonisolated static func cacheDirectory(projectID: UUID) -> URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
        return base.appendingPathComponent("NEFViewer/previews/\(projectID.uuidString)", isDirectory: true)
    }

    nonisolated static func removeDiskCache(projectID: UUID) {
        try? FileManager.default.removeItem(at: cacheDirectory(projectID: projectID))
    }

    nonisolated static func diskPreviewCount(projectID: UUID) -> Int {
        (try? FileManager.default.contentsOfDirectory(atPath: cacheDirectory(projectID: projectID).path))?
            .filter { $0.hasSuffix(".jpg") }.count ?? 0
    }

    func image(id: UUID, url: URL, projectID: UUID, tier: Tier) async -> CGImage? {
        switch tier {
        case .standard:
            if let cached = standard[id] {
                touch(id)
                return cached
            }
        case .full:
            if let current = full, current.id == id {
                return current.image
            }
        }

        let flightKey = "\(id.uuidString)-\(tier)"
        if let task = inFlight[flightKey] { return await task.value }
        let myGeneration = generation

        let task = Task<CGImage?, Never>(priority: .userInitiated) {
            let diskURL = Self.cacheDirectory(projectID: projectID)
                .appendingPathComponent(id.uuidString + ".jpg")

            if tier == .standard {
                if let data = try? Data(contentsOf: diskURL),
                   let source = CGImageSourceCreateWithData(data as CFData, nil),
                   let image = CGImageSourceCreateImageAtIndex(source, 0, nil) {
                    return image
                }
            }

            if Task.isCancelled { return nil }
            let maxSize: Int? = tier == .standard ? 3200 : nil
            guard let image = NEFImageLoader.embeddedPreview(from: url, maxPixelSize: maxSize) else {
                return nil
            }

            if tier == .standard {
                try? FileManager.default.createDirectory(
                    at: Self.cacheDirectory(projectID: projectID),
                    withIntermediateDirectories: true
                )
                let rep = NSBitmapImageRep(cgImage: image)
                if let jpeg = rep.representation(using: .jpeg, properties: [.compressionFactor: 0.85]) {
                    try? jpeg.write(to: diskURL, options: .atomic)
                }
            }
            return image
        }
        inFlight[flightKey] = task
        let result = await task.value
        inFlight[flightKey] = nil

        guard let result else { return nil }
        switch tier {
        case .standard:
            insert(result, for: id)
        case .full:
            // 解码期间翻页了：丢弃过期的全尺寸，不替换当前槽位
            guard myGeneration == generation, !Task.isCancelled else { return nil }
            full = (id, result)
        }
        return result
    }

    /// 翻页调用：作废旧全尺寸并取消未完成的全尺寸解码，让读卡带宽始终服务于当前页
    func cancelPendingFull() {
        generation += 1
        for (key, task) in inFlight where key.hasSuffix("-full") {
            task.cancel()
        }
        full = nil
    }

    func clearFull() {
        full = nil
    }

    private func touch(_ id: UUID) {
        standardOrder.removeAll { $0 == id }
        standardOrder.append(id)
    }

    private func insert(_ image: CGImage, for id: UUID) {
        standard[id] = image
        touch(id)
        while standardOrder.count > standardCapacity {
            let evict = standardOrder.removeFirst()
            standard[evict] = nil
        }
    }
}
