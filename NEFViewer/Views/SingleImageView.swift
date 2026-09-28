import SwiftUI
import AppKit

struct SingleImageView: View {
    let items: [PhotoItem]
    let rootURL: URL
    let projectID: UUID
    @Binding var currentID: UUID
    var editorName: String
    var onClose: () -> Void
    var onRate: (UUID, Int) -> Void
    var onOpenExternal: (PhotoItem) -> Void

    @State private var displayImage: CGImage?
    /// 已加载全尺寸的照片 id（放大超过 1.2× 才按需解码）
    @State private var fullResID: UUID?
    @State private var scale: CGFloat = 1
    @State private var baseScale: CGFloat?
    @State private var offset: CGSize = .zero
    @State private var dragStart: CGSize?

    private var index: Int {
        items.firstIndex { $0.id == currentID } ?? 0
    }

    private var current: PhotoItem { items[index] }

    var body: some View {
        ZStack {
            Color(nsColor: .underPageBackgroundColor)
                .ignoresSafeArea()

            if let displayImage {
                Image(decorative: displayImage, scale: 1)
                    .resizable()
                    .scaledToFit()
                    .scaleEffect(scale)
                    .offset(offset)
                    .gesture(magnifyGesture)
                    .simultaneousGesture(panGesture)
                    .onTapGesture(count: 2) {
                        onOpenExternal(current)
                    }
            } else {
                ProgressView()
                    .controlSize(.large)
            }
        }
        .overlay(alignment: .topLeading) {
            Button {
                onClose()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.body.weight(.semibold))
                    .padding(10)
                    .background(.ultraThinMaterial, in: Circle())
            }
            .buttonStyle(.plain)
            .padding(14)
            .help("返回网格（Esc）")
        }
        .overlay(alignment: .bottom) {
            HStack(spacing: 16) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(current.fileName)
                        .font(.callout)
                        .fontWeight(.medium)
                    Text("\(index + 1) / \(items.count)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
                Spacer()
                RatingControl(rating: current.rating) { rating in
                    onRate(current.id, rating)
                }
                Spacer()
                Text("0-5 打分 · ←→ 翻页 · 捏合或 +/- 缩放 · 双击在\(editorName)中编辑 · Esc 返回")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
            .background(.bar)
        }
        .background(KeyMonitorView { event in handleKey(event) })
        .onDisappear {
            Task { await PreviewCache.shared.clearFull() }
        }
        .task(id: currentID) { await loadCurrent() }
        .onChange(of: scale) { _, newScale in
            if newScale > 1.2 { ensureFullRes() }
        }
    }

    private var magnifyGesture: some Gesture {
        MagnifyGesture()
            .onChanged { value in
                if baseScale == nil { baseScale = scale }
                scale = min(10, max(1, (baseScale ?? 1) * value.magnification))
            }
            .onEnded { _ in baseScale = nil }
    }

    private var panGesture: some Gesture {
        DragGesture()
            .onChanged { value in
                guard scale > 1 else { return }
                if dragStart == nil { dragStart = offset }
                offset = CGSize(
                    width: (dragStart?.width ?? 0) + value.translation.width,
                    height: (dragStart?.height ?? 0) + value.translation.height
                )
            }
            .onEnded { _ in dragStart = nil }
    }

    private func zoom(by factor: CGFloat) {
        withAnimation(.easeOut(duration: 0.12)) {
            scale = min(10, max(1, scale * factor))
            if scale <= 1 { offset = .zero }
        }
    }

    private func step(by delta: Int) {
        let newIndex = index + delta
        guard items.indices.contains(newIndex) else { return }
        currentID = items[newIndex].id
    }

    /// 键盘监听回调（NSEvent 本地监视器，不依赖焦点）：←→ 翻页、Esc 返回、0-5 打分、+/- 缩放
    private func handleKey(_ event: NSEvent) -> Bool {
        switch event.keyCode {
        case 123: step(by: -1); return true   // ←
        case 124: step(by: 1); return true    // →
        case 53: onClose(); return true       // Esc
        default: break
        }
        guard let chars = event.charactersIgnoringModifiers, let key = chars.first else { return false }
        switch key {
        case "0", "1", "2", "3", "4", "5":
            onRate(current.id, Int(String(key))!)
            return true
        case "+", "=": zoom(by: 1.35); return true
        case "-": zoom(by: 1 / 1.35); return true
        default: return false
        }
    }

    /// 翻页轻路径：640px 网格缓存（只查缓存，绝不为它现解析整张 NEF）→ 3200px 标准档。
    /// 两路并发，缩略图只为垫背；不做全尺寸解码——快速连翻时读卡带宽始终服务于当前页
    private func loadCurrent() async {
        scale = 1
        offset = .zero
        fullResID = nil
        await PreviewCache.shared.cancelPendingFull()
        let item = current
        let url = item.absoluteURL(under: rootURL)

        async let standardResult = PreviewCache.shared.image(
            id: item.id, url: url, projectID: projectID, tier: .standard
        )
        if let thumb = await ThumbnailCache.shared.cached(key: item.id, projectID: projectID) {
            guard !Task.isCancelled, current.id == item.id else { return }
            displayImage = thumb
        }
        if let standard = await standardResult {
            guard !Task.isCancelled, current.id == item.id else { return }
            displayImage = standard
        }
        prefetchAround()
    }

    /// 放大超过 1.2× 才按需解码全尺寸内嵌预览（翻页时取消上一张未完成的解码）
    private func ensureFullRes() {
        guard fullResID != current.id else { return }
        let item = current
        let url = item.absoluteURL(under: rootURL)
        Task {
            if let full = await PreviewCache.shared.image(id: item.id, url: url, projectID: projectID, tier: .full) {
                guard current.id == item.id else { return }
                displayImage = full
                fullResID = item.id
            }
        }
    }

    private func prefetchAround() {
        for delta in [-2, -1, 1, 2] {
            let target = index + delta
            guard items.indices.contains(target) else { continue }
            let item = items[target]
            let url = item.absoluteURL(under: rootURL)
            Task(priority: .userInitiated) {
                _ = await PreviewCache.shared.image(id: item.id, url: url, projectID: projectID, tier: .standard)
            }
        }
    }
}
