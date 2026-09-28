# AGENTS.md

## 项目概述

NEF Viewer：macOS 原生轻量 NEF（Nikon RAW）选片工具，SwiftUI + SwiftData，XcodeGen 生成工程。目标用户为单人本机使用，第一设计目标是**快**（替代 Lightroom/Bridge 的选片环节）。

## 构建与验证

```bash
xcodegen generate   # 新增/删除源文件后必须重跑；.xcodeproj 是生成物，勿手改
xcodebuild -scheme NEFViewer -configuration Debug build
```

- 无测试工程；验证方式 = 编译通过 + 用真实储存卡（`/Volumes/*/DCIM`）跑端到端流程
- 应用数据（重置时用）：`rm ~/Library/Application\ Support/default.store*` + `rm -rf ~/Library/Caches/NEFViewer`
- 每次重编译后 macOS 会重新弹「访问可移除宗卷」权限框（ad-hoc 签名 cdhash 变化），属正常现象

## 目录结构

```
project.yml                  # XcodeGen 工程定义（唯一事实源）
NEFViewer/
├── NEFViewerApp.swift       # @main + ModelContainer + Settings 场景
├── ContentView.swift        # NavigationSplitView 主框架 + 预渲染进度浮层
├── Models/                  # Project/Photo（SwiftData @Model）
├── Services/
│   ├── LibraryImporter.swift    # 枚举 SourceFile → 导入勾选子集（copy/link，并发 4）
│   ├── NEFImageLoader.swift     # ImageIO 内嵌预览提取 + EXIF 拍摄时间 + SourceDateCache
│   ├── ThumbnailCache.swift     # 640px 网格缩略图：内存 + 磁盘，按 Photo.id 命名
│   ├── PreviewCache.swift       # 3200px 标准档（磁盘持久化）+ 全尺寸单槽（内存，generation 取消）
│   ├── PreviewBuilder.swift     # 全量预渲染（全局单例 @Observable，驱动进度浮层）
│   ├── CacheManager.swift       # 缓存统计/清理/孤立缓存
│   ├── ExternalEditor.swift     # LaunchServices 编辑器发现 + NSWorkspace 打开
│   └── RatingStore.swift        # XMP sidecar 导出
└── Views/                   # ProjectListView / NewProjectSheet（两步向导）/
                             # ProjectBrowserView / PhotoCellView / SingleImageView /
                             # BrowserState（+PhotoItem）/ RatingControl / SettingsView
```

## 关键架构决策（都是用崩溃换来的，改动前先读）

1. **绝不在视图 body/Builder 中修改任何状态**。SwiftUI 会提前求值 contextMenu 等内容，渲染期写 `@Observable` 属性 → 无限失效循环（实测主线程 100% / AppKit `NSGenericException` 布局循环）。状态修改只能发生在事件回调（onTap、Button action、onAppear/onChange、task）里。
2. **绝不在 body 中访问 SwiftData 持久化属性做批量计算**。视图层用 `PhotoItem` 轻量快照（`BrowserState.swift`），SwiftData 模型只在快照构建（`.task`）和写入（评分）时触碰。body 中也不得有 LaunchServices/文件遍历等慢调用——全部挪到 `.task` 后台解析后存 `@State`。
3. **批量 SwiftData 写入分块 + `Task.yield()`**（200/块），避免单次失效风暴触发 AppKit 布局异常。
4. **图像管线不走 RAW 解码**：Z8 NEF 内嵌全尺寸 JPEG 预览。网格 640px / 单图标准档 3200px（均磁盘缓存），全尺寸 8256px 仅放大 >1.2× 时按需解码且翻页可取消（`PreviewCache.generation`）。改管线时保持「翻页轻路径」原则：快速连翻不能被大解码堵住。
5. **EXIF orientation 必须归一化**（orientation 5–8 交换宽高），否则竖拍照片布局全错。ImageIO 提取预览时用 `kCGImageSourceCreateThumbnailWithTransform = true` 应用方向。
6. **删除/清理永远不动源文件**：删除项目默认只删数据库记录和缓存；链接模式的源文件任何路径都不可写删（XMP 导出除外，那是用户显式动作）。

## 约定

- UI 文案一律中文；代码注释从简，只写「为什么」
- 新增缓存类目录要同时接入 `CacheManager`（设置页统计/清理）
- 设置项用 `@AppStorage`；`preferredEditorPath`、`autoBuildPreviews`、`libraryBasePath` 已被占用
- 外观跟随系统：不设 `preferredColorScheme`，颜色全用 semantic colors
