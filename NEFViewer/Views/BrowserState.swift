import Foundation
import CoreGraphics

/// 视图层使用的轻量照片快照：避免在 body 渲染中访问 SwiftData 持久化属性
/// （1330 个模型在 body 中排序曾导致 AttributeGraph 无限更新，主线程 99% 卡死）
struct PhotoItem: Identifiable, Equatable, Sendable {
    let id: UUID
    let relativePath: String
    let fileName: String
    var rating: Int
    let dateTaken: Date?
    let width: Int
    let height: Int

    init(photo: Photo) {
        id = photo.id
        relativePath = photo.relativePath
        fileName = photo.fileName
        rating = photo.rating
        dateTaken = photo.dateTaken
        width = photo.width
        height = photo.height
    }

    var aspect: CGFloat {
        width > 0 && height > 0 ? CGFloat(width) / CGFloat(height) : 1.5
    }

    func absoluteURL(under root: URL) -> URL {
        root.appendingPathComponent(relativePath)
    }
}

enum SortOrder: String, CaseIterable, Identifiable {
    case ratingDesc
    case dateTaken

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .ratingDesc: return "评分（高→低）"
        case .dateTaken: return "拍摄时间"
        }
    }
}

enum RatingFilter: Equatable {
    case all
    case atLeast(Int)
    case atMost(Int)

    var displayName: String {
        switch self {
        case .all: return "全部"
        case .atLeast(let n): return "≥ \(n) 星"
        case .atMost(let n): return "≤ \(n) 星"
        }
    }
}

@Observable
final class BrowserState {
    var sortOrder: SortOrder = .ratingDesc
    var ratingFilter: RatingFilter = .all
    var showUnrated = true
    var selection: Set<UUID> = []
    var anchorID: UUID?
    /// 单图视图的照片快照（打开时取自筛选结果，避免评分后列表在脚下重排）
    var viewingList: [PhotoItem]?
    var viewingID: UUID?

    func displayedItems(from items: [PhotoItem]) -> [PhotoItem] {
        items
            .filter { item in
                if item.rating == 0 { return showUnrated }
                switch ratingFilter {
                case .all: return true
                case .atLeast(let n): return item.rating >= n
                case .atMost(let n): return item.rating <= n
                }
            }
            .sorted { a, b in
                switch sortOrder {
                case .ratingDesc:
                    if a.rating != b.rating { return a.rating > b.rating }
                    return (a.dateTaken ?? .distantPast) < (b.dateTaken ?? .distantPast)
                case .dateTaken:
                    let da = a.dateTaken ?? .distantPast
                    let db = b.dateTaken ?? .distantPast
                    if da != db { return da < db }
                    return a.relativePath < b.relativePath
                }
            }
    }

    var isViewingSingle: Bool { viewingList != nil && viewingID != nil }

    func openSingle(at item: PhotoItem, from displayed: [PhotoItem]) {
        viewingList = displayed
        viewingID = item.id
    }

    func closeSingle() {
        viewingList = nil
        viewingID = nil
    }
}
