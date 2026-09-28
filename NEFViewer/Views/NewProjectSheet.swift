import SwiftUI
import SwiftData
import AppKit

struct NewProjectSheet: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss
    var onCreated: ((Project) -> Void)? = nil

    private enum Step {
        case config
        case files
    }

    @State private var step: Step = .config
    @State private var name = ""
    @State private var mode: ProjectMode = .copy
    @State private var sourceURL: URL?
    @State private var libraryBaseURL: URL = FileManager.default
        .urls(for: .picturesDirectory, in: .userDomainMask).first!
        .appendingPathComponent("NEF Viewer", isDirectory: true)

    @State private var enumerating = false
    @State private var sourceFiles: [SourceFile] = []
    @State private var selectedPaths: Set<String> = []
    @State private var fileAnchor: String?

    @State private var importing = false
    @State private var importer = LibraryImporter()
    @State private var importTask: Task<Void, Never>?
    @State private var errorMessage: String?

    @AppStorage("autoBuildPreviews") private var autoBuildPreviews = true
    @AppStorage("libraryBasePath") private var libraryBasePath = ""

    private var trimmedName: String { name.trimmingCharacters(in: .whitespaces) }
    private var libraryRoot: URL { libraryBaseURL.appendingPathComponent(trimmedName, isDirectory: true) }
    private var canProceed: Bool { !trimmedName.isEmpty && sourceURL != nil }

    /// 检测 /Volumes 下含 DCIM 的卷（相机储存卡），并展开 DCIM 子文件夹
    private var detectedCards: [(name: String, folders: [URL])] {
        let fm = FileManager.default
        guard let volumes = try? fm.contentsOfDirectory(atPath: "/Volumes") else { return [] }
        var cards: [(String, [URL])] = []
        for volume in volumes.sorted() {
            let dcimPath = "/Volumes/" + volume + "/DCIM"
            var isDir: ObjCBool = false
            guard fm.fileExists(atPath: dcimPath, isDirectory: &isDir), isDir.boolValue else { continue }
            var folders = [URL(fileURLWithPath: dcimPath)]
            if let subs = try? fm.contentsOfDirectory(atPath: dcimPath) {
                for sub in subs.sorted() {
                    var subIsDir: ObjCBool = false
                    let path = dcimPath + "/" + sub
                    if !sub.hasPrefix("."),
                       fm.fileExists(atPath: path, isDirectory: &subIsDir), subIsDir.boolValue {
                        folders.append(URL(fileURLWithPath: path))
                    }
                }
            }
            cards.append((volume.trimmingCharacters(in: .whitespaces), folders))
        }
        return cards
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text(step == .config ? "新建项目" : "选择要导入的文件")
                .font(.title2)
                .fontWeight(.semibold)

            if importing {
                progressSection
            } else if enumerating {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("正在枚举源目录中的 NEF 文件…")
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 8)
            } else if step == .config {
                formSection
            } else {
                filesSection
            }
        }
        .padding(24)
        .frame(width: 560)
        .onAppear {
            if !libraryBasePath.isEmpty {
                libraryBaseURL = URL(fileURLWithPath: libraryBasePath)
            }
        }
        .alert("导入失败", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) {
            Button("好") { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
    }

    // MARK: - 第一步：项目配置

    private var formSection: some View {
        Group {
            Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 14) {
                GridRow {
                    Text("名称")
                        .foregroundStyle(.secondary)
                        .gridColumnAlignment(.trailing)
                    TextField("例如：2026-09 北海道", text: $name)
                        .textFieldStyle(.roundedBorder)
                        .accessibilityLabel("项目名称")
                }
                GridRow {
                    Text("模式")
                        .foregroundStyle(.secondary)
                    Picker("", selection: $mode) {
                        ForEach(ProjectMode.allCases, id: \.self) { m in
                            Text(m.displayName).tag(m)
                        }
                    }
                    .labelsHidden()
                    .pickerStyle(.segmented)
                }
                GridRow {
                    Text("源目录")
                        .foregroundStyle(.secondary)
                    HStack {
                        Text(sourceURL?.path ?? "未选择（储存卡或任意文件夹）")
                            .foregroundStyle(sourceURL == nil ? .tertiary : .primary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Spacer()
                        Button("选择…") {
                            if let url = chooseDirectory(prompt: "选择") {
                                sourceURL = url
                                if name.isEmpty {
                                    name = suggestName(from: url)
                                }
                            }
                        }
                        .accessibilityLabel("选择源目录")
                    }
                }
                if sourceURL == nil {
                    ForEach(detectedCards, id: \.name) { card in
                        GridRow {
                            Text("")
                            VStack(alignment: .leading, spacing: 6) {
                                Text("检测到储存卡 \(card.name)：")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                HStack(spacing: 8) {
                                    ForEach(card.folders, id: \.path) { url in
                                        Button {
                                            sourceURL = url
                                            if name.isEmpty {
                                                name = suggestName(from: url)
                                            }
                                        } label: {
                                            Text(url.lastPathComponent == "DCIM" ? "整卡" : url.lastPathComponent)
                                                .font(.callout)
                                                .padding(.horizontal, 10)
                                                .padding(.vertical, 4)
                                                .background(.quaternary, in: Capsule())
                                        }
                                        .buttonStyle(.plain)
                                        .accessibilityLabel("使用文件夹 \(url.lastPathComponent)")
                                    }
                                }
                            }
                        }
                    }
                }
                if mode == .copy {
                    GridRow {
                        Text("库位置")
                            .foregroundStyle(.secondary)
                        HStack {
                            Text(libraryBaseURL.path)
                                .lineLimit(1)
                                .truncationMode(.middle)
                            Spacer()
                            Button("选择…") {
                                if let url = chooseDirectory(prompt: "选择") {
                                    libraryBaseURL = url
                                    libraryBasePath = url.path
                                }
                            }
                        }
                    }
                    GridRow {
                        Text("")
                        Text("将拷贝到：\(libraryRoot.path)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                }
            }

            HStack {
                Spacer()
                Button("取消", role: .cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button("下一步") { loadFileList() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(!canProceed)
                    .buttonStyle(.borderedProminent)
                    .accessibilityLabel("下一步")
            }
        }
    }

    // MARK: - 第二步：逐文件勾选

    private var selectedFiles: [SourceFile] {
        sourceFiles.filter { selectedPaths.contains($0.id) }
    }

    private var selectedTotalSize: Int64 {
        selectedFiles.reduce(0) { $0 + $1.fileSize }
    }

    private var filesSection: some View {
        Group {
            HStack {
                Text("\(selectedPaths.count) / \(sourceFiles.count) 个文件")
                    .fontWeight(.medium)
                Text("· 共 \(ByteCountFormatter.string(fromByteCount: selectedTotalSize, countStyle: .file))")
                    .foregroundStyle(.secondary)
                Spacer()
                Button("全选") { selectedPaths = Set(sourceFiles.map(\.id)) }
                Button("全不选") { selectedPaths = [] }
            }
            Text("拍摄时间取自 EXIF（加载中先显示文件修改时间）；点击勾选，⇧点击连选一段")
                .font(.caption)
                .foregroundStyle(.secondary)

            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(sourceFiles) { file in
                        SourceFileRow(
                            file: file,
                            isSelected: selectedPaths.contains(file.id)
                        )
                        .onTapGesture { toggleFile(file) }
                    }
                }
                .padding(.vertical, 2)
            }
            .frame(minHeight: 320, maxHeight: 400)

            Toggle(isOn: $autoBuildPreviews) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("导入后生成全部标准预览")
                    Text("后台逐个生成 3200px 预览，之后单图翻页秒开；也可稍后在项目右键菜单手动生成")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            HStack {
                Button("← 返回") { step = .config }
                Spacer()
                Button("取消", role: .cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button("创建并导入 (\(selectedPaths.count))") { startImport() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(selectedPaths.isEmpty)
                    .buttonStyle(.borderedProminent)
                    .accessibilityLabel("创建并导入")
            }
        }
    }

    private func toggleFile(_ file: SourceFile) {
        let modifiers = NSEvent.modifierFlags
        if modifiers.contains(.shift),
           let anchor = fileAnchor,
           let from = sourceFiles.firstIndex(where: { $0.id == anchor }),
           let to = sourceFiles.firstIndex(where: { $0.id == file.id }) {
            // ⇧ 连选：范围应用锚点当前的选中状态（先全不选再 ⇧ 选一段，或反向取消一段）
            let anchorSelected = selectedPaths.contains(anchor)
            for i in min(from, to)...max(from, to) {
                if anchorSelected {
                    selectedPaths.insert(sourceFiles[i].id)
                } else {
                    selectedPaths.remove(sourceFiles[i].id)
                }
            }
        } else {
            if selectedPaths.contains(file.id) {
                selectedPaths.remove(file.id)
            } else {
                selectedPaths.insert(file.id)
            }
            fileAnchor = file.id
        }
    }

    // MARK: - 导入

    private var progressSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(mode == .copy ? "正在拷贝并索引…" : "正在索引…")
                    .fontWeight(.medium)
                Spacer()
                Text("\(importer.processedCount) / \(importer.totalCount)")
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
            }
            ProgressView(value: importer.progress)
            Text(importer.statusText)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.middle)
            HStack {
                Spacer()
                Button("取消", role: .cancel) { importTask?.cancel() }
            }
        }
        .padding(.vertical, 8)
    }

    private func loadFileList() {
        guard let sourceURL else { return }
        enumerating = true
        Task {
            do {
                let files = try await Task.detached(priority: .userInitiated) {
                    try LibraryImporter.enumerateSourceFiles(under: sourceURL)
                }.value
                guard !Task.isCancelled else {
                    enumerating = false
                    return
                }
                sourceFiles = files
                selectedPaths = Set(files.map(\.id))
                fileAnchor = nil
                enumerating = false
                step = .files
            } catch {
                errorMessage = error.localizedDescription
                enumerating = false
            }
        }
    }

    private func startImport() {
        guard let sourceURL else { return }
        let chosen = selectedFiles
        importing = true
        let projectName = trimmedName
        let targetLibrary = libraryRoot
        importTask = Task {
            do {
                let files = try await importer.run(mode: mode, sourceRoot: sourceURL, libraryRoot: targetLibrary, files: chosen)
                guard !Task.isCancelled else {
                    importing = false
                    return
                }
                let project = Project(
                    name: projectName,
                    mode: mode,
                    sourcePath: sourceURL.path,
                    libraryPath: mode == .copy ? targetLibrary.path : ""
                )
                modelContext.insert(project)
                // 分块插入并让出主线程，避免单次事务风暴触发 AppKit 布局循环崩溃
                let chunkSize = 200
                var index = 0
                while index < files.count {
                    let end = min(index + chunkSize, files.count)
                    for f in files[index..<end] {
                        let photo = Photo(
                            relativePath: f.relativePath,
                            dateTaken: f.dateTaken,
                            fileSize: f.fileSize,
                            width: f.width,
                            height: f.height
                        )
                        photo.project = project
                        modelContext.insert(photo)
                    }
                    index = end
                    await Task.yield()
                }
                try modelContext.save()
                onCreated?(project)
                if autoBuildPreviews {
                    PreviewBuilder.shared.start(project: project)
                }
                dismiss()
            } catch is CancellationError {
                importing = false
            } catch {
                errorMessage = error.localizedDescription
                importing = false
            }
        }
    }

    private func suggestName(from url: URL) -> String {
        let df = DateFormatter()
        df.dateFormat = "yyyy-MM"
        if url.lastPathComponent.uppercased() == "DCIM" {
            let parent = url.deletingLastPathComponent().lastPathComponent
                .trimmingCharacters(in: .whitespaces)
            return "\(df.string(from: Date())) \(parent)"
        }
        return url.lastPathComponent
    }

    private func chooseDirectory(prompt: String) -> URL? {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = false
        panel.prompt = prompt
        panel.message = "选择包含 NEF 文件的文件夹"
        return panel.runModal() == .OK ? panel.url : nil
    }
}

// MARK: - 文件选择列表行

private struct SourceFileRow: View {
    let file: SourceFile
    let isSelected: Bool

    /// EXIF 拍摄时间（后台读文件头，加载前先用文件修改时间垫底）
    @State private var captureDate: Date?

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: isSelected ? "checkmark.square.fill" : "square")
                .foregroundStyle(isSelected ? Color.accentColor : Color.secondary)
                .imageScale(.large)
            Text(file.fileName)
                .lineLimit(1)
            Spacer()
            if let date = captureDate ?? file.modificationDate {
                Text(date, format: .dateTime.year().month(.twoDigits).day(.twoDigits).hour().minute())
                    .font(.callout)
                    .foregroundStyle(captureDate != nil ? .secondary : .tertiary)
                    .monospacedDigit()
            }
            Text(ByteCountFormatter.string(fromByteCount: file.fileSize, countStyle: .file))
                .foregroundStyle(.secondary)
                .font(.callout)
                .frame(width: 72, alignment: .trailing)
        }
        .padding(.vertical, 5)
        .padding(.horizontal, 6)
        .contentShape(Rectangle())
        .opacity(isSelected ? 1 : 0.5)
        .task {
            captureDate = await SourceDateCache.shared.captureDate(for: file.url)
        }
    }
}
