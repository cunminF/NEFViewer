import SwiftUI
import SwiftData

struct ProjectListView: View {
    @Environment(\.modelContext) private var modelContext
    @Query(sort: \Project.createdAt, order: .reverse) private var projects: [Project]
    @Binding var selection: Project?

    @State private var showingNewProject = false
    @State private var renamingProject: Project?
    @State private var deletingProject: Project?

    var body: some View {
        List(selection: $selection) {
            Section("项目") {
                ForEach(projects) { project in
                    HStack(spacing: 8) {
                        Image(systemName: project.mode.icon)
                            .foregroundStyle(.primary)
                            .opacity(project.isAvailable ? 1 : 0.4)
                        Text(project.name)
                            .lineLimit(1)
                        Spacer()
                        Text("\(project.photos.count)")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                    }
                    .tag(project)
                    .contextMenu {
                        Button("重命名…") { renamingProject = project }
                        Divider()
                        Button("生成全部标准预览") {
                            PreviewBuilder.shared.start(project: project)
                        }
                        Button("清理项目缓存") {
                            CacheManager.clearCache(projectID: project.id)
                        }
                        Divider()
                        Button("删除项目…", role: .destructive) { deletingProject = project }
                    }
                }
            }
        }
        .listStyle(.sidebar)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showingNewProject = true
                } label: {
                    Image(systemName: "plus")
                }
                .help("新建项目")
            }
        }
        .sheet(isPresented: $showingNewProject) {
            NewProjectSheet { selection = $0 }
        }
        .sheet(item: $renamingProject) { project in
            RenameProjectSheet(project: project)
        }
        .sheet(item: $deletingProject) { project in
            DeleteProjectSheet(project: project, selection: $selection)
        }
    }
}

private struct RenameProjectSheet: View {
    @Bindable var project: Project
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("重命名项目")
                .font(.headline)
            TextField("项目名称", text: $project.name)
                .textFieldStyle(.roundedBorder)
            HStack {
                Spacer()
                Button("完成") { dismiss() }
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 320)
    }
}

private struct DeleteProjectSheet: View {
    let project: Project
    @Binding var selection: Project?
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss
    @State private var deleteFiles = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("删除项目「\(project.name)」？")
                .font(.headline)
            Text("将从应用中移除该项目、全部评分记录和缩略图缓存。")
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if project.mode == .copy {
                Toggle(isOn: $deleteFiles) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("同时删除库文件夹")
                        Text(project.libraryPath)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                }
                .foregroundStyle(deleteFiles ? .red : .primary)
            } else {
                Label("链接模式：源文件不会被改动。", systemImage: "checkmark.shield")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            HStack {
                Spacer()
                Button("取消", role: .cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button("删除", role: .destructive) { performDelete() }
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 440)
    }

    private func performDelete() {
        if project.mode == .copy && deleteFiles {
            try? FileManager.default.removeItem(atPath: project.libraryPath)
        }
        CacheManager.clearCache(projectID: project.id)
        if selection == project { selection = nil }
        modelContext.delete(project)
        try? modelContext.save()
        dismiss()
    }
}
