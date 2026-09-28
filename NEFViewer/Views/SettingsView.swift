import SwiftUI
import SwiftData
import AppKit
import UniformTypeIdentifiers

struct SettingsView: View {
    var body: some View {
        TabView {
            GeneralSettingsTab()
                .tabItem { Label("通用", systemImage: "gear") }
            CacheSettingsTab()
                .tabItem { Label("缓存", systemImage: "externaldrive") }
        }
        .frame(width: 520, height: 360)
    }
}

// MARK: - 通用

private struct GeneralSettingsTab: View {
    @AppStorage("autoBuildPreviews") private var autoBuildPreviews = true
    @AppStorage("libraryBasePath") private var libraryBasePath = ""
    @AppStorage("preferredEditorPath") private var preferredEditorPath = ""

    private var libraryBaseDisplay: String {
        libraryBasePath.isEmpty
            ? FileManager.default.urls(for: .picturesDirectory, in: .userDomainMask).first!
                .appendingPathComponent("NEF Viewer").path
            : libraryBasePath
    }

    private var editorDisplay: String {
        if preferredEditorPath.isEmpty { return "自动（Pixelmator Pro）" }
        return ExternalEditor.appName(URL(fileURLWithPath: preferredEditorPath))
    }

    var body: some View {
        Form {
            Section("图库") {
                LabeledContent("默认库位置") {
                    HStack {
                        Text(libraryBaseDisplay)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Button("选择…") {
                            let panel = NSOpenPanel()
                            panel.canChooseFiles = false
                            panel.canChooseDirectories = true
                            panel.prompt = "选择"
                            if panel.runModal() == .OK, let url = panel.url {
                                libraryBasePath = url.path
                            }
                        }
                    }
                }
                Toggle("导入后自动生成全部标准预览", isOn: $autoBuildPreviews)
            }
            Section("外部编辑器") {
                LabeledContent("当前编辑器") {
                    Text(editorDisplay)
                        .foregroundStyle(.secondary)
                }
                HStack {
                    Button("选择其他应用…") {
                        let panel = NSOpenPanel()
                        panel.canChooseFiles = true
                        panel.canChooseDirectories = false
                        panel.allowedContentTypes = [.application]
                        panel.directoryURL = URL(fileURLWithPath: "/Applications")
                        panel.prompt = "选择"
                        if panel.runModal() == .OK, let url = panel.url {
                            preferredEditorPath = url.path
                        }
                    }
                    if !preferredEditorPath.isEmpty {
                        Button("恢复自动") { preferredEditorPath = "" }
                    }
                }
            }
        }
        .formStyle(.grouped)
        .padding()
    }
}

// MARK: - 缓存

private struct CacheSettingsTab: View {
    @Query(sort: \Project.createdAt, order: .reverse) private var projects: [Project]

    @State private var sizes: [UUID: (thumb: Int64, preview: Int64)] = [:]
    @State private var orphanSize: Int64 = 0
    @State private var loading = true

    private var totalSize: Int64 {
        sizes.values.reduce(0) { $0 + $1.thumb + $1.preview } + orphanSize
    }

    var body: some View {
        Form {
            Section("项目缓存（缩略图 + 标准预览）") {
                if loading {
                    HStack {
                        ProgressView()
                            .controlSize(.small)
                        Text("正在统计…")
                            .foregroundStyle(.secondary)
                    }
                } else if projects.isEmpty && orphanSize == 0 {
                    Text("暂无缓存")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(projects) { project in
                        let entry = sizes[project.id] ?? (0, 0)
                        HStack {
                            Text(project.name)
                                .lineLimit(1)
                            Spacer()
                            Text(formatBytes(entry.thumb + entry.preview))
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                            Button("清理") {
                                CacheManager.clearCache(projectID: project.id)
                                refresh()
                            }
                            .controlSize(.small)
                            .disabled(entry.thumb + entry.preview == 0)
                        }
                    }
                    if orphanSize > 0 {
                        HStack {
                            Text("孤立缓存（已删除项目的残留）")
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                            Spacer()
                            Text(formatBytes(orphanSize))
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                            Button("清理") {
                                CacheManager.clearOrphanedCaches(validProjectIDs: Set(projects.map(\.id)))
                                refresh()
                            }
                            .controlSize(.small)
                        }
                    }
                }
            }
            Section {
                HStack {
                    Text("总计：\(formatBytes(totalSize))")
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button("全部清理") {
                        for project in projects {
                            CacheManager.clearCache(projectID: project.id)
                        }
                        CacheManager.clearOrphanedCaches(validProjectIDs: Set(projects.map(\.id)))
                        refresh()
                    }
                    .disabled(totalSize == 0)
                }
            }
        }
        .formStyle(.grouped)
        .padding()
        .onAppear(perform: refresh)
    }

    private func refresh() {
        loading = true
        let projectIDs = projects.map(\.id)
        Task {
            let result = await Task.detached(priority: .userInitiated) { () -> ([UUID: (Int64, Int64)], Int64) in
                var map: [UUID: (Int64, Int64)] = [:]
                for id in projectIDs {
                    map[id] = (
                        CacheManager.thumbnailDirectorySize(projectID: id),
                        CacheManager.previewDirectorySize(projectID: id)
                    )
                }
                let orphan = CacheManager.orphanedCacheSize(validProjectIDs: Set(projectIDs))
                return (map, orphan)
            }.value
            sizes = result.0
            orphanSize = result.1
            loading = false
        }
    }

    private func formatBytes(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}
