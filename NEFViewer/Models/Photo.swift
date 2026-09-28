import Foundation
import SwiftData

@Model
final class Photo {
    var id: UUID = UUID()
    var relativePath: String
    var rating: Int = 0
    var dateTaken: Date?
    var fileSize: Int64 = 0
    var width: Int = 0
    var height: Int = 0
    var project: Project?

    init(relativePath: String, dateTaken: Date?, fileSize: Int64, width: Int, height: Int) {
        self.relativePath = relativePath
        self.dateTaken = dateTaken
        self.fileSize = fileSize
        self.width = width
        self.height = height
    }

    var fileName: String { URL(fileURLWithPath: relativePath).lastPathComponent }

    func absoluteURL(under root: URL) -> URL {
        root.appendingPathComponent(relativePath)
    }
}
