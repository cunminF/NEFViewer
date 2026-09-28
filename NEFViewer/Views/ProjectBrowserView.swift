import SwiftUI
import SwiftData
import AppKit
import UniformTypeIdentifiers

struct ProjectBrowserView: View {
    let project: Project

    @Environment(\.modelContext) private var modelContext
    @AppStorage("preferredEditorPath") private var preferredEditorPath = ""
    @State private var state = BrowserState()
    /// 轻量快照：body 中只做纯值筛选排序，不触碰 SwiftData 持久化属性
    @State private var items: [PhotoItem] = []
    @State private var photoLookup: [UUID: Photo] = [:]
    @State private var columnCount = 4
    @State private var showingEditorNotFound = false
    @State private var infoMessage: String?
    @FocusState private var gridFocused: Bool

    private var displayed: [PhotoItem] { state.displayedItems(from: items) }

    /// ⌘E 目标：单图模式取当前图，网格模式取选中集（仅在点击时计算，不进 body）
    private func currentTargets() -> [PhotoItem] {
        if state.isViewingSingle, let id = state.viewingID,
           let item = (state.viewingList ?? items).first(where: { $0.id == id }) {
            return [item]
        }
        return items.filter { state.selection.contains($0.id) }
    }

    // MARK: - 外部编辑器

    // LaunchServices 查询是 XPC 调用且可能触及储存卡，绝不能放在 body 里每次求值
    @State private var editorCandidates: [URL] = []
    @State private var editorURL: URL?

    private var editorName: String {
        editorURL.map(ExternalEditor.appName) ?? "外部编辑器"
    }

    private func resolveEditor() async {
        let sample = items.first?.absoluteURL(under: project.rootURL)
        let result = await Task.detached(priority: .userInitiated) { () -> (candidates: [URL], fallback: URL?) in
            let candidates = ExternalEditor.candidateEditors(sampleURL: sample)
            let fallback = ExternalEditor.pixelmatorURL() ?? candidates.first
            return (candidates, fallback)
        }.value
        editorCandidates = result.candidates
        if !preferredEditorPath.isEmpty {
            let url = URL(fileURLWithPath: preferredEditorPath)
            if FileManager.default.fileExists(atPath: url.path) {
                editorURL = url
                return
            }
        }
        editorURL = result.fallback
    }

    var body: some View {
        Group {
            if let list = state.viewingList, let viewingID = state.viewingID {
                SingleImageView(
                    items: list,
                    rootURL: project.rootURL,
                    projectID: project.id,
                    currentID: Binding(
                        get: { viewingID },
                        set: { state.viewingID = $0 }
                    ),
                    editorName: editorName,
                    onClose: { state.closeSingle() },
                    onRate: { id, rating in applyRating(rating, to: [id]) },
                    onOpenExternal: { _ in openInEditor() }
                )
            } else {
                gridContent
            }
        }
        .navigationTitle(project.name)
        .toolbar { toolbarContent }
        .task {
            snapshotItems()
            await resolveEditor()
        }
        .task(id: preferredEditorPath) {
            await resolveEditor()
        }
        .alert("无法打开编辑器", isPresented: $showingEditorNotFound) {
            Button("好", role: .cancel) {}
        } message: {
            Text("未能用 \(editorName) 打开文件。请在右上角菜单中检查外部编辑器设置。")
        }
        .alert("提示", isPresented: Binding(
            get: { infoMessage != nil },
            set: { if !$0 { infoMessage = nil } }
        )) {
            Button("好") { infoMessage = nil }
        } message: {
            Text(infoMessage ?? "")
        }
    }

    /// 一次性快照（仅此一处批量访问 SwiftData 属性，主线程一次过，约几十毫秒）
    private func snapshotItems() {
        let photos = project.photos
        photoLookup = Dictionary(photos.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        items = photos.map(PhotoItem.init)
    }

    // MARK: - 网格

    private var gridContent: some View {
        VStack(spacing: 0) {
            if !project.isAvailable {
                Label("库当前离线：\(project.rootPath)（已缓存的缩略图仍可浏览）", systemImage: "externaldrive.badge.xmark")
                    .font(.callout)
                    .foregroundStyle(.orange)
                    .padding(8)
                    .frame(maxWidth: .infinity)
                    .background(.orange.opacity(0.1))
            }
            if displayed.isEmpty {
                if items.isEmpty {
                    ContentUnavailableView {
                        Label("项目为空", systemImage: "photo.on.rectangle.angled")
                    } description: {
                        Text("该项目还没有导入任何照片。")
                    }
                } else {
                    ContentUnavailableView {
                        Label("没有符合筛选条件的照片", systemImage: "line.3.horizontal.decrease.circle")
                    } description: {
                        Text("试试调整筛选条件，或打开「显示未评分」。")
                    }
                }
            } else {
                GeometryReader { geo in
                    ScrollViewReader { proxy in
                        ScrollView {
                            LazyVGrid(
                                columns: [GridItem(.adaptive(minimum: 180, maximum: 320), spacing: 12)],
                                spacing: 12
                            ) {
                                ForEach(displayed) { item in
                                    PhotoCellView(
                                        item: item,
                                        rootURL: project.rootURL,
                                        projectID: project.id,
                                        isSelected: state.selection.contains(item.id),
                                        isPrimary: state.anchorID == item.id
                                    )
                                    .id(item.id)
                                    .onTapGesture(count: 2) {
                                        state.openSingle(at: item, from: displayed)
                                    }
                                    .onTapGesture { handleTap(item) }
                                    .contextMenu { cellMenu(for: item) }
                                }
                            }
                            .padding(16)
                        }
                        .focusable()
                        .focused($gridFocused)
                        .onAppear {
                            updateColumnCount(geo.size.width)
                            gridFocused = true
                        }
                        .onChange(of: geo.size.width) { _, newWidth in
                            updateColumnCount(newWidth)
                        }
                        .onKeyPress(.leftArrow) { moveSelection(by: -1, proxy: proxy) }
                        .onKeyPress(.rightArrow) { moveSelection(by: 1, proxy: proxy) }
                        .onKeyPress(.upArrow) { moveSelection(by: -columnCount, proxy: proxy) }
                        .onKeyPress(.downArrow) { moveSelection(by: columnCount, proxy: proxy) }
                        .onKeyPress(.return) {
                            if let anchor = state.anchorID,
                               let item = displayed.first(where: { $0.id == anchor }) {
                                state.openSingle(at: item, from: displayed)
                            }
                            return .handled
                        }
                        .onKeyPress("1") { rateSelection(1) }
                        .onKeyPress("2") { rateSelection(2) }
                        .onKeyPress("3") { rateSelection(3) }
                        .onKeyPress("4") { rateSelection(4) }
                        .onKeyPress("5") { rateSelection(5) }
                        .onKeyPress("0") { rateSelection(0) }
                    }
                }
            }
        }
    }

    private func updateColumnCount(_ width: CGFloat) {
        columnCount = max(1, Int((width - 32) / 192))
    }

    private func handleTap(_ item: PhotoItem) {
        let modifiers = NSEvent.modifierFlags
        if modifiers.contains(.command) {
            if state.selection.contains(item.id) {
                state.selection.remove(item.id)
            } else {
                state.selection.insert(item.id)
            }
            state.anchorID = item.id
        } else if modifiers.contains(.shift),
                  let anchor = state.anchorID,
                  let from = displayed.firstIndex(where: { $0.id == anchor }),
                  let to = displayed.firstIndex(where: { $0.id == item.id }) {
            state.selection = Set(displayed[min(from, to)...max(from, to)].map(\.id))
        } else {
            state.selection = [item.id]
            state.anchorID = item.id
        }
        gridFocused = true
    }

    private func moveSelection(by delta: Int, proxy: ScrollViewProxy) -> KeyPress.Result {
        guard !displayed.isEmpty else { return .ignored }
        let currentIndex = state.anchorID.flatMap { id in displayed.firstIndex(where: { $0.id == id }) } ?? 0
        let newIndex = min(max(0, currentIndex + delta), displayed.count - 1)
        let item = displayed[newIndex]
        state.selection = [item.id]
        state.anchorID = item.id
        proxy.scrollTo(item.id)
        return .handled
    }

    // MARK: - 评分

    private func rateSelection(_ rating: Int) -> KeyPress.Result {
        guard !state.selection.isEmpty else { return .ignored }
        applyRating(rating, to: state.selection)
        return .handled
    }

    /// 一次写入三处：SwiftData 模型（持久化）、items 快照（网格角标）、viewingList 快照（单图星级）
    private func applyRating(_ rating: Int, to ids: Set<UUID>) {
        for id in ids {
            photoLookup[id]?.rating = rating
        }
        try? modelContext.save()
        for i in items.indices where ids.contains(items[i].id) {
            items[i].rating = rating
        }
        if var list = state.viewingList {
            var changed = false
            for i in list.indices where ids.contains(list[i].id) {
                list[i].rating = rating
                changed = true
            }
            if changed { state.viewingList = list }
        }
    }

    // MARK: - 菜单与工具栏

    @ViewBuilder
    private func cellMenu(for item: PhotoItem) -> some View {
        Menu("评分") {
            ForEach(1...5, id: \.self) { n in
                Button("\(n) 星") {
                    ensureSelected(item)
                    applyRating(n, to: state.selection)
                }
            }
            Divider()
            Button("清除评分") {
                ensureSelected(item)
                applyRating(0, to: state.selection)
            }
        }
        Button("打开单图视图") {
            state.openSingle(at: item, from: displayed)
        }
        Divider()
        Button("在 \(editorName) 中编辑") {
            ensureSelected(item)
            openInEditor()
        }
        Button("在 Finder 中显示") {
            NSWorkspace.shared.activateFileViewerSelecting([item.absoluteURL(under: project.rootURL)])
        }
    }

    /// 右键菜单动作生效前，先把被点的项纳入选中集（绝不能在建菜单时改状态）
    private func ensureSelected(_ item: PhotoItem) {
        if !state.selection.contains(item.id) {
            state.selection = [item.id]
            state.anchorID = item.id
        }
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItemGroup(placement: .primaryAction) {
            if !state.isViewingSingle {
                Picker("排序", selection: $state.sortOrder) {
                    ForEach(SortOrder.allCases) { order in
                        Text(order.displayName).tag(order)
                    }
                }
                .pickerStyle(.menu)
                .fixedSize()

                Menu {
                    Button("全部") { state.ratingFilter = .all }
                    Divider()
                    ForEach(1...5, id: \.self) { n in
                        Button("≥ \(n) 星") { state.ratingFilter = .atLeast(n) }
                    }
                    Divider()
                    ForEach(1...5, id: \.self) { n in
                        Button("≤ \(n) 星") { state.ratingFilter = .atMost(n) }
                    }
                } label: {
                    Label(state.ratingFilter == .all ? "筛选" : state.ratingFilter.displayName,
                          systemImage: "line.3.horizontal.decrease.circle")
                }

                Button {
                    state.showUnrated.toggle()
                } label: {
                    Image(systemName: state.showUnrated ? "star" : "star.slash")
                }
                .help(state.showUnrated ? "点击隐藏未评分照片" : "点击显示未评分照片")
            }

            Button {
                openInEditor()
            } label: {
                Label("在 \(editorName) 中编辑", systemImage: "arrow.up.forward.app")
            }
            .keyboardShortcut("e", modifiers: .command)
            .disabled(state.selection.isEmpty && !state.isViewingSingle)

            Menu {
                Menu("外部编辑器：\(editorName)") {
                    ForEach(editorCandidates, id: \.path) { appURL in
                        Button {
                            preferredEditorPath = appURL.path
                        } label: {
                            HStack {
                                Text(ExternalEditor.appName(appURL))
                                if appURL == editorURL {
                                    Image(systemName: "checkmark")
                                }
                            }
                        }
                    }
                    Divider()
                    Button("选择其他应用…") { chooseEditorApp() }
                }
                Divider()
                Button("将评分导出到 XMP sidecar") { exportXMP() }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
        }
    }

    private func openInEditor() {
        let urls = currentTargets().map { $0.absoluteURL(under: project.rootURL) }
        guard !urls.isEmpty, let app = editorURL else {
            showingEditorNotFound = true
            return
        }
        Task {
            if await !ExternalEditor.open(urls, with: app) {
                showingEditorNotFound = true
            }
        }
    }

    private func chooseEditorApp() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.allowedContentTypes = [.application]
        panel.directoryURL = URL(fileURLWithPath: "/Applications")
        panel.prompt = "选择"
        panel.message = "选择用于编辑照片的应用"
        if panel.runModal() == .OK, let url = panel.url {
            preferredEditorPath = url.path
        }
    }

    private func exportXMP() {
        do {
            let count = try RatingStore.exportXMP(project: project)
            infoMessage = count > 0
                ? "已将 \(count) 条评分导出为 XMP sidecar 文件（与 NEF 同目录）。"
                : "没有已评分的照片，无需导出。"
        } catch {
            infoMessage = "导出失败：\(error.localizedDescription)"
        }
    }
}
