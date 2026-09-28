import Foundation
import ImageIO
import CoreGraphics

/// 源目录枚举出的待选文件（导入前的勾选清单用）
struct SourceFile: Sendable, Identifiable {
    let url: URL
    let relativePath: String
    let fileSize: Int64
    let modificationDate: Date?

    var id: String { relativePath }
    var fileName: String { url.lastPathComponent }
}

struct ImportedFile: Sendable {
    let relativePath: String
    let fileSize: Int64
    let dateTaken: Date?
    let width: Int
    let height: Int
}

enum ImportError: LocalizedError {
    case sourceNotFound
    case noNEFFound
    case destinationExists

    var errorDescription: String? {
        switch self {
        case .sourceNotFound: return "源目录不存在或不可用"
        case .noNEFFound: return "源目录中没有找到 NEF 文件"
        case .destinationExists: return "目标库文件夹已存在，请更换项目名称或库位置"
        }
    }
}

@MainActor @Observable
final class LibraryImporter {
    var progress: Double = 0
    var statusText = ""
    var processedCount = 0
    var totalCount = 0

    /// 枚举源目录下的全部 NEF（含大小和修改时间，供勾选界面展示；不解码，速度快）
    nonisolated static func enumerateSourceFiles(under root: URL) throws -> [SourceFile] {
        guard FileManager.default.fileExists(atPath: root.path) else {
            throw ImportError.sourceNotFound
        }
        guard let enumerator = FileManager.default.enumerator(
            at: root,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey],
            options: [.skipsHiddenFiles]
        ) else {
            throw ImportError.sourceNotFound
        }
        var files: [SourceFile] = []
        let prefixCount = root.path.count + 1
        for case let url as URL in enumerator {
            guard url.pathExtension.lowercased() == "nef" else { continue }
            let values = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
            files.append(SourceFile(
                url: url,
                relativePath: String(url.path.dropFirst(prefixCount)),
                fileSize: Int64(values?.fileSize ?? 0),
                modificationDate: values?.contentModificationDate
            ))
        }
        return files.sorted { $0.relativePath < $1.relativePath }
    }

    /// 导入用户勾选的文件子集
    func run(mode: ProjectMode, sourceRoot: URL, libraryRoot: URL, files: [SourceFile]) async throws -> [ImportedFile] {
        guard !files.isEmpty else { throw ImportError.noNEFFound }
        totalCount = files.count

        if mode == .copy {
            if FileManager.default.fileExists(atPath: libraryRoot.path) {
                throw ImportError.destinationExists
            }
            try FileManager.default.createDirectory(at: libraryRoot, withIntermediateDirectories: true)
        }

        do {
            return try await processAll(files: files, mode: mode, sourceRoot: sourceRoot, libraryRoot: libraryRoot)
        } catch {
            // 取消或失败：拷贝模式清理半成品，保证不产生残留项目
            if mode == .copy {
                try? FileManager.default.removeItem(at: libraryRoot)
            }
            throw error
        }
    }

    private func processAll(files: [SourceFile], mode: ProjectMode, sourceRoot: URL, libraryRoot: URL) async throws -> [ImportedFile] {
        var results: [ImportedFile] = []
        results.reserveCapacity(files.count)
        var nextIndex = 0
        let concurrency = 4

        try await withThrowingTaskGroup(of: ImportedFile.self) { group in
            func submit(_ file: SourceFile) {
                group.addTask {
                    try Self.processOne(file: file, mode: mode, sourceRoot: sourceRoot, libraryRoot: libraryRoot)
                }
            }
            while nextIndex < min(concurrency, files.count) {
                submit(files[nextIndex]); nextIndex += 1
            }
            while let item = try await group.next() {
                results.append(item)
                processedCount = results.count
                progress = Double(results.count) / Double(files.count)
                statusText = item.relativePath
                if Task.isCancelled {
                    group.cancelAll()
                    throw CancellationError()
                }
                if nextIndex < files.count {
                    submit(files[nextIndex]); nextIndex += 1
                }
            }
        }
        return results
    }

    nonisolated static func processOne(file: SourceFile, mode: ProjectMode, sourceRoot: URL, libraryRoot: URL) throws -> ImportedFile {
        if Task.isCancelled { throw CancellationError() }
        let rel = file.relativePath
        let target = mode == .copy ? libraryRoot.appendingPathComponent(rel) : file.url

        if mode == .copy {
            let fm = FileManager.default
            try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            if fm.fileExists(atPath: target.path) { try fm.removeItem(at: target) }
            try fm.copyItem(at: file.url, to: target)
        }

        let size = Int64((try? target.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? Int(file.fileSize))
        var width = 0
        var height = 0
        var dateTaken: Date? = nil

        if let src = CGImageSourceCreateWithURL(target as CFURL, nil),
           let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [String: Any] {
            width = props[kCGImagePropertyPixelWidth as String] as? Int ?? 0
            height = props[kCGImagePropertyPixelHeight as String] as? Int ?? 0
            // 竖拍照片以方向标记存储（像素仍是横向 8256×5504），布局用宽高需按方向归一化
            let orientation = props[kCGImagePropertyOrientation as String] as? Int ?? 1
            if (5...8).contains(orientation) {
                swap(&width, &height)
            }
            if let exif = props[kCGImagePropertyExifDictionary as String] as? [String: Any],
               let dto = exif[kCGImagePropertyExifDateTimeOriginal as String] as? String {
                dateTaken = NEFImageLoader.exifDateFormatter.date(from: dto)
            }
        }
        if dateTaken == nil {
            dateTaken = file.modificationDate
        }

        return ImportedFile(relativePath: rel, fileSize: size, dateTaken: dateTaken, width: width, height: height)
    }
}
