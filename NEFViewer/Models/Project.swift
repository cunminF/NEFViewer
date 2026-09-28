import Foundation
import SwiftData

enum ProjectMode: String, Codable, CaseIterable {
    case copy
    case link

    var displayName: String {
        switch self {
        case .copy: return "拷贝到本地"
        case .link: return "保留原地（链接）"
        }
    }

    var icon: String {
        switch self {
        case .copy: return "internaldrive.fill"
        case .link: return "link"
        }
    }
}

@Model
final class Project {
    var id: UUID = UUID()
    var name: String
    var createdAt: Date = Date()
    var modeRaw: String
    var sourcePath: String
    var libraryPath: String

    @Relationship(deleteRule: .cascade, inverse: \Photo.project)
    var photos: [Photo] = []

    init(name: String, mode: ProjectMode, sourcePath: String, libraryPath: String) {
        self.name = name
        self.modeRaw = mode.rawValue
        self.sourcePath = sourcePath
        self.libraryPath = libraryPath
    }

    var mode: ProjectMode {
        get { ProjectMode(rawValue: modeRaw) ?? .link }
        set { modeRaw = newValue.rawValue }
    }

    var rootPath: String { mode == .copy ? libraryPath : sourcePath }

    var rootURL: URL { URL(fileURLWithPath: rootPath) }

    var isAvailable: Bool {
        FileManager.default.fileExists(atPath: rootPath)
    }
}
