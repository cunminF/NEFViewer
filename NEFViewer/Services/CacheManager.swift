import Foundation

/// 缓存统计与清理：缩略图（640px）、标准预览（3200px），按项目分目录
enum CacheManager {

    struct ProjectCacheInfo: Identifiable {
        let id: UUID
        let name: String
        var thumbnailBytes: Int64
        var previewBytes: Int64
        var totalBytes: Int64 { thumbnailBytes + previewBytes }
    }

    nonisolated static func directorySize(_ url: URL) -> Int64 {
        guard let enumerator = FileManager.default.enumerator(
            at: url,
            includingPropertiesForKeys: [.fileSizeKey]
        ) else { return 0 }
        var total: Int64 = 0
        for case let file as URL in enumerator {
            total += Int64((try? file.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0)
        }
        return total
    }

    nonisolated static func thumbnailDirectorySize(projectID: UUID) -> Int64 {
        directorySize(ThumbnailCache.cacheDirectory(projectID: projectID))
    }

    nonisolated static func previewDirectorySize(projectID: UUID) -> Int64 {
        directorySize(PreviewCache.cacheDirectory(projectID: projectID))
    }

    /// 磁盘上存在但已不属于任何项目的缓存目录（例如异常退出时未被清理）
    nonisolated static func orphanedCacheSize(validProjectIDs: Set<UUID>) -> Int64 {
        var total: Int64 = 0
        for parent in [thumbnailCacheRoot(), previewCacheRoot()] {
            guard let dirs = try? FileManager.default.contentsOfDirectory(atPath: parent.path) else { continue }
            for dir in dirs {
                guard let id = UUID(uuidString: dir), !validProjectIDs.contains(id) else {
                    if UUID(uuidString: dir) == nil, dir != ".DS_Store" {
                        total += directorySize(parent.appendingPathComponent(dir))
                    }
                    continue
                }
                total += directorySize(parent.appendingPathComponent(dir))
            }
        }
        return total
    }

    nonisolated static func clearOrphanedCaches(validProjectIDs: Set<UUID>) {
        for parent in [thumbnailCacheRoot(), previewCacheRoot()] {
            guard let dirs = try? FileManager.default.contentsOfDirectory(atPath: parent.path) else { continue }
            for dir in dirs {
                guard let id = UUID(uuidString: dir), !validProjectIDs.contains(id) else { continue }
                try? FileManager.default.removeItem(at: parent.appendingPathComponent(dir))
            }
        }
    }

    nonisolated static func clearCache(projectID: UUID) {
        ThumbnailCache.removeDiskCache(projectID: projectID)
        PreviewCache.removeDiskCache(projectID: projectID)
    }

    nonisolated private static func thumbnailCacheRoot() -> URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
            .appendingPathComponent("NEFViewer/thumbs", isDirectory: true)
    }

    nonisolated private static func previewCacheRoot() -> URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
            .appendingPathComponent("NEFViewer/previews", isDirectory: true)
    }
}
