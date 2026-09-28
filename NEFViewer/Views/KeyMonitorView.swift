import SwiftUI
import AppKit

/// 本地键盘事件监视器：不依赖 SwiftUI 焦点。
/// .onKeyPress 在焦点被工具栏/菜单拿走后静默失效（单图翻页"偶发翻不动"的根源之一），
/// 这里改为 NSEvent 本地监视器，只在事件属于本窗口且无弹层时接管
struct KeyMonitorView: NSViewRepresentable {
    /// 返回 true 表示消费该事件
    let onKeyDown: (NSEvent) -> Bool

    func makeNSView(context: Context) -> AnchorView { AnchorView() }

    func updateNSView(_ nsView: AnchorView, context: Context) {
        nsView.onKeyDown = onKeyDown
    }

    final class AnchorView: NSView {
        var onKeyDown: ((NSEvent) -> Bool)?
        private var monitor: Any?

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            if window != nil {
                guard monitor == nil else { return }
                monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
                    guard let self, let handler = self.onKeyDown,
                          event.window === self.window,
                          self.window?.attachedSheet == nil,
                          !event.modifierFlags.contains(.command)
                    else { return event }
                    return handler(event) ? nil : event
                }
            } else if let monitor {
                NSEvent.removeMonitor(monitor)
                self.monitor = nil
            }
        }

        deinit {
            if let monitor { NSEvent.removeMonitor(monitor) }
        }
    }
}
