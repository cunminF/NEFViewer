import SwiftUI

struct PhotoCellView: View {
    let item: PhotoItem
    let rootURL: URL
    let projectID: UUID
    let isSelected: Bool
    let isPrimary: Bool

    @State private var image: CGImage?

    var body: some View {
        VStack(spacing: 4) {
            ZStack(alignment: .bottomLeading) {
                RoundedRectangle(cornerRadius: 8)
                    .fill(.quaternary)
                if let image {
                    Image(decorative: image, scale: 1)
                        .resizable()
                        .scaledToFit()
                        .transition(.opacity)
                } else {
                    Image(systemName: "photo")
                        .foregroundStyle(.tertiary)
                        .imageScale(.large)
                }
                if item.rating > 0 {
                    HStack(spacing: 2) {
                        Image(systemName: "star.fill")
                        Text("\(item.rating)")
                    }
                    .font(.caption2)
                    .fontWeight(.semibold)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 3)
                    .background(.black.opacity(0.55), in: Capsule())
                    .padding(6)
                }
            }
            .aspectRatio(item.aspect, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay {
                RoundedRectangle(cornerRadius: 8)
                    .stroke(isPrimary ? Color.accentColor : Color.accentColor.opacity(0.45),
                            lineWidth: isPrimary ? 3 : 2)
                    .opacity(isSelected ? 1 : 0)
            }
            .animation(.easeOut(duration: 0.15), value: image != nil)
            Text(item.fileName)
                .font(.caption)
                .foregroundStyle(isSelected ? .primary : .secondary)
                .lineLimit(1)
                .truncationMode(.middle)
        }
        .task {
            image = await ThumbnailCache.shared.thumbnail(
                for: item.absoluteURL(under: rootURL),
                key: item.id,
                projectID: projectID
            )
        }
    }
}
