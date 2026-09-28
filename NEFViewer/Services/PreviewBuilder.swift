import Foundation

/// 全量标准预览生成器（类 Adobe 的 1:1 预览构建）：后台并发生成 3200px 磁盘缓存，
/// 主界面底部显示全局进度浮层，可取消；已存在的自动跳过（增量）
@MainActor @Observable
final class PreviewBuilder {
    static let shared = PreviewBuilder()
    private init() {}

    var isBuilding = false
    var projectName = ""
    var processedCount = 0
    var totalCount = 0

    private var buildTask: Task<Void, Never>?

    var progress: Double {
        totalCount > 0 ? Double(processedCount) / Double(totalCount) : 0
    }

    func start(project: Project) {
        cancel()
        let root = project.rootURL
        let projectID = project.id
        // 在主线程取出轻量元组，避免把 SwiftData 模型传入后台任务
        let targets = project.photos.map { (id: $0.id, url: $0.absoluteURL(under: root)) }
        guard !targets.isEmpty else { return }

        projectName = project.name
        totalCount = targets.count
        processedCount = 0
        isBuilding = true

        buildTask = Task {
            await buildAll(targets: targets, projectID: projectID)
            isBuilding = false
        }
    }

    func cancel() {
        buildTask?.cancel()
        buildTask = nil
        isBuilding = false
    }

    private func buildAll(targets: [(id: UUID, url: URL)], projectID: UUID) async {
        var next = 0
        let concurrency = 4
        await withTaskGroup(of: Void.self) { group in
            func submit(_ target: (id: UUID, url: URL)) {
                group.addTask {
                    _ = await PreviewCache.shared.image(
                        id: target.id,
                        url: target.url,
                        projectID: projectID,
                        tier: .standard
                    )
                }
            }
            while next < min(concurrency, targets.count) {
                submit(targets[next]); next += 1
            }
            while await group.next() != nil {
                if Task.isCancelled {
                    group.cancelAll()
                    return
                }
                processedCount += 1
                if next < targets.count {
                    submit(targets[next]); next += 1
                }
            }
        }
    }
}
