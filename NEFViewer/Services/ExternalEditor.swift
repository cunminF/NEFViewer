import Foundation
import AppKit

enum ExternalEditor {
    static let pixelmatorBundleID = "com.pixelmatorteam.pixelmator"

    /// 默认编辑器：Pixelmator Pro
    static func pixelmatorURL() -> URL? {
        if let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: pixelmatorBundleID) {
            return url
        }
        let candidates = [
            "/Applications/Pixelmator Pro.app",
            NSHomeDirectory() + "/Applications/Pixelmator Pro.app"
        ]
        return candidates
            .map { URL(fileURLWithPath: $0) }
            .first { FileManager.default.fileExists(atPath: $0.path) }
    }

    /// 通过 LaunchServices 发现系统中声明可打开该文件类型的应用（sampleURL 用一个真实 NEF）。
    /// Pixelmator Pro 保证在列（即使未声明 NEF），自身除外。
    static func candidateEditors(sampleURL: URL?) -> [URL] {
        var result: [URL] = []
        var seen = Set<String>()

        func append(_ url: URL) {
            guard url.path != Bundle.main.bundleURL.path else { return }
            if seen.insert(url.path).inserted { result.append(url) }
        }

        if let sampleURL {
            for url in NSWorkspace.shared.urlsForApplications(toOpen: sampleURL) {
                append(url)
            }
        }
        if let pixelmator = pixelmatorURL() {
            append(pixelmator)
        }
        return result.sorted {
            $0.deletingPathExtension().lastPathComponent.localizedCompare($1.deletingPathExtension().lastPathComponent) == .orderedAscending
        }
    }

    static func appName(_ url: URL) -> String {
        url.deletingPathExtension().lastPathComponent
    }

    @discardableResult
    static func open(_ urls: [URL], with app: URL) async -> Bool {
        guard !urls.isEmpty else { return false }
        do {
            try await NSWorkspace.shared.open(urls, withApplicationAt: app, configuration: NSWorkspace.OpenConfiguration())
            return true
        } catch {
            return false
        }
    }
}
