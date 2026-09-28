import SwiftUI
import SwiftData

struct ContentView: View {
    @State private var selection: Project?
    @State private var builder = PreviewBuilder.shared

    var body: some View {
        NavigationSplitView {
            ProjectListView(selection: $selection)
                .navigationSplitViewColumnWidth(min: 200, ideal: 230, max: 300)
        } detail: {
            if let project = selection, !project.isDeleted {
                ProjectBrowserView(project: project)
                    .id(project.id)
            } else {
                ContentUnavailableView {
                    Label("选择或新建一个项目", systemImage: "photo.on.rectangle.angled")
                } description: {
                    Text("每个项目对应一个图片库：可以从储存卡拷贝，也可以原地链接。")
                }
            }
        }
        .frame(minWidth: 960, minHeight: 600)
        .overlay(alignment: .bottom) {
            if builder.isBuilding {
                HStack(spacing: 12) {
                    ProgressView(value: builder.progress)
                        .frame(width: 140)
                    Text("正在生成预览 \(builder.processedCount)/\(builder.totalCount)")
                        .font(.callout)
                        .monospacedDigit()
                    Text(builder.projectName)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    Button("取消") { builder.cancel() }
                        .controlSize(.small)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(.ultraThinMaterial, in: Capsule())
                .shadow(radius: 8)
                .padding(.bottom, 18)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.25), value: builder.isBuilding)
    }
}

#Preview {
    ContentView()
        .modelContainer(for: [Project.self, Photo.self], inMemory: true)
}
