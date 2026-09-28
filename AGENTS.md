# AGENTS.md

## 项目概述

NEF Viewer：轻量 NEF（Nikon RAW）选片工具，目标用户为单人本机使用，第一设计目标是**快**（替代 Lightroom/Bridge 的选片环节）。两个平台：

- **macOS 版**（本目录）：SwiftUI + SwiftData，XcodeGen 生成工程
- **Android Pad 版**（`NEFViewer-Android/`）：Kotlin + Jetpack Compose（Material 3）+ Room，目标机 Lenovo Y700（SM8850 / Adreno）

## Android 版（NEFViewer-Android/）

### 构建与验证

```bash
cd NEFViewer-Android
./build.sh installDebug        # 包装脚本：设 JAVA_HOME=openjdk@17、检查 7892 代理在线
./build.sh testDebugUnitTest   # JVM 单测直接解析 Mac 上 ~/Pictures/NEF Viewer 的真 NEF
```

- JDK 17 在 `/opt/homebrew/opt/openjdk@17`；Gradle 依赖走 `~/.gradle/gradle.properties` 里的 7892 代理（**代理必须在线**，build.sh 会警告）
- 真机：Lenovo Y700（无线调试 `adb connect <ip>:5555`）；UI 自动化用 `uitap.py <文本>` 拿坐标 + `adb shell input tap`
- 没有 instrumented 测试；验证 = JVM 单测 + adb 截图走端到端流程

### 目录结构

```
app/src/main/java/com/nefviewer/android/
├── MainActivity.kt            # NavHost + 全局预渲染进度浮层
├── data/                      # Room（Project/Photo）+ SettingsRepository（DataStore）
├── nef/NefParser.kt           # 纯 Kotlin TIFF 解析器（JVM 可测，无 Android 依赖）
├── pipeline/                  # PreviewExtractor（解码+GPU）/ GpuImageOps（hardware Canvas）
│                              # ThumbnailCache（640px 双级）/ PreviewCache（3200px + 全尺寸单槽）
│                              # PreviewBuilder（全量预渲染）/ CacheManager
├── importer/                  # LibraryImporter（SAF 枚举/copy/link）/ SafTree
├── editor/ExternalEditor.kt   # ACTION_EDIT 发现 + FileProvider
├── xmp/XmpExporter.kt         # xmp:Rating sidecar（与 macOS 版同格式）
└── ui/                        # projects（列表+向导）/ browser（网格+单图）/ settings / components
```

### Android 版关键决策（都是用真机调试换来的，改动前先读）

1. **图像必须 HARDWARE bitmap + hardware Canvas**（`GpuImageOps`）：解码产物进 GPU 显存，Compose 显示零上传；EXIF 方向 + 缩放一次 GPU draw 完成。但 **HARDWARE bitmap 不可 compress/读像素**——凡是要写盘的中间产物必须用 `hardwareOut = false` 走软件路径。验证：`dumpsys gfxinfo` 应见 `Pipeline=Skia (Vulkan)`、`meminfo` Graphics 段占大头。
2. **翻页手势分层**（`SingleImageScreen.ZoomablePhoto`）：未放大时单指滑动**绝不消费**（让给 HorizontalPager 翻页）；双指捏合或已放大（>1.05×）才接管变换手势。`detectTransformGestures` 会吃掉单指拖动导致 Pager 失效——这是第一个真机 bug。已放大时的单指平移带加速系数 `panBoost = scale.coerceIn(1f, 3f)`（用户实测 1:1 在 8256px 全图下"不跟手"）；调手感只改这个系数。
3. **不要假设能访问 adb push 的文件**：scoped storage 下应用连自己的 `Android/data` 里 shell 创建的文件都读不到（联想 ZUI/Android 16 实测）。一切外部文件走 **SAF**；SAF 授权要**读写**都持久化（写用于 XMP/批量删除）。测试数据推到 `/sdcard/Download/` 再 SAF 授权。
4. **照片访问 URI 优先**（`PhotoInputResolver`）：`documentUri` 非空走 PfdSeekableInput（链接源/SAF 图库副本），否则走图库文件路径。拷贝目标可以是应用私有目录（文件）或用户自选 SAF 目录（`NEF Viewer/<项目名>/` 子目录），删除项目/批量删除两条路径都要覆盖。
5. **排序实时应用但单图页序冻结**：打分后 Room 流刷新，网格列表立即重排；但单图视图使用进入时的**冻结列表快照**（`BrowserScreen.singleList`），翻页顺序不随评分变，星级显示走 `livePhotos` 的 id→rating 映射。网格交互：短按进单图、长按进多选（多选模式下短按=加选）——不要在 PhotoCell 上同时挂 onClick 和 onDoubleClick（单击会被双击判定延迟 300ms）。
6. **EXIF orientation 入库即归一化**（orientation 5–8 交换宽高），与 macOS 版同规则；Z8 竖拍靠这个。
7. **全尺寸解码单飞去重 + generation 作废**（2026-09 ANR 换来的教训，违反任何一条都是内存风暴）：a) `LaunchedEffect(scale)` 会在捏合**每帧**重启——全尺寸请求必须加"每页一次"闸（`fullRequested`，try/finally 复位）；b) `PreviewCache.fullSize` 用 Mutex+Deferred 去重，同照片并发共享、换照片取消旧的；c) 翻页 `bumpGeneration` **并 `clearFullSlot()`**——181MB 不随翻页常驻；d) **交给 UI 的 bitmap 绝不 recycle**（旧页可能还在 Compose 里绘制，recycle 即崩），只丢引用让 GC 收；e) `hardwareOut=true` 时原图也直接解成 HARDWARE（GPU→GPU 变换），峰值少一份 181MB 软件拷贝。实锤案例：连点放大数次→并发 3 个 181MB 解码→214 万缺页→主线程饿死 5.8s→ANR 弹窗→用户点关闭="闪退"。
8. Room 开发期允许 `fallbackToDestructiveMigration`（加字段直接升版本号，数据重来）。

## macOS 版（本目录）

### 构建与验证

```bash
xcodegen generate   # 新增/删除源文件后必须重跑；.xcodeproj 是生成物，勿手改
xcodebuild -scheme NEFViewer -configuration Debug build
```

- 无测试工程；验证方式 = 编译通过 + 用真实储存卡（`/Volumes/*/DCIM`）跑端到端流程
- 应用数据（重置时用）：`rm ~/Library/Application\ Support/default.store*` + `rm -rf ~/Library/Caches/NEFViewer`
- 每次重编译后 macOS 会重新弹「访问可移除宗卷」权限框（ad-hoc 签名 cdhash 变化），属正常现象

### 目录结构

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
│   ├── PreviewBuilder.swift     # 全量预渲染：3200+640 双档同出（全局单例 @Observable，驱动进度浮层）
│   ├── CacheManager.swift       # 缓存统计/清理/孤立缓存
│   ├── ExternalEditor.swift     # LaunchServices 编辑器发现 + NSWorkspace 打开
│   └── RatingStore.swift        # XMP sidecar 导出
└── Views/                   # ProjectListView / NewProjectSheet（两步向导）/
                             # ProjectBrowserView / PhotoCellView / SingleImageView /
                             # BrowserState（+PhotoItem）/ RatingControl / KeyMonitorView / SettingsView
```

### macOS 版关键架构决策（都是用崩溃换来的，改动前先读）

1. **绝不在视图 body/Builder 中修改任何状态**。SwiftUI 会提前求值 contextMenu 等内容，渲染期写 `@Observable` 属性 → 无限失效循环（实测主线程 100% / AppKit `NSGenericException` 布局循环）。状态修改只能发生在事件回调（onTap、Button action、onAppear/onChange、task）里。
2. **绝不在 body 中访问 SwiftData 持久化属性做批量计算**。视图层用 `PhotoItem` 轻量快照（`BrowserState.swift`），SwiftData 模型只在快照构建（`.task`）和写入（评分）时触碰。body 中也不得有 LaunchServices/文件遍历等慢调用——全部挪到 `.task` 后台解析后存 `@State`。
3. **批量 SwiftData 写入分块 + `Task.yield()`**（200/块），避免单次失效风暴触发 AppKit 布局异常。
4. **图像管线不走 RAW 解码**：Z8 NEF 内嵌全尺寸 JPEG 预览。网格 640px / 单图标准档 3200px（均磁盘缓存），全尺寸 8256px 仅放大 >1.2× 时按需解码且翻页可取消（`PreviewCache.generation`）。改管线时保持「翻页轻路径」原则：快速连翻不能被大解码堵住。
5. **EXIF orientation 必须归一化**（orientation 5–8 交换宽高），否则竖拍照片布局全错。ImageIO 提取预览时用 `kCGImageSourceCreateThumbnailWithTransform = true` 应用方向。
6. **删除/清理永远不动源文件**：删除项目默认只删数据库记录和缓存；链接模式的源文件任何路径都不可写删（XMP 导出除外，那是用户显式动作）。
7. **预渲染必须双档同出**：`PreviewBuilder` 解码 3200 后同步派生 640 落盘（`ThumbnailCache.storeDerived`）。只出 3200 会让网格滚动/单图翻页回退到逐张解析 NEF（实测 870 项目网格二次卡顿的元凶）。3200 已在磁盘时 640 直接由它缩放，增量补齐不碰 NEF。
8. **单图翻页热路径绝不为缩略图解析 NEF**：`loadCurrent` 用 `ThumbnailCache.cached`（只查内存+磁盘）与 3200 请求并发；未命中就跳过缩略图直接等标准档。曾用 `thumbnail(for:)`（miss 时现解析整张 NEF）串行挡在 3200 磁盘命中前面——表现为「按右键没反应，过一会才翻」。
9. **单图键盘走 NSEvent 本地监视器**（`KeyMonitorView`），不依赖 SwiftUI 焦点：`.onKeyPress` 在焦点被工具栏/菜单拿走后静默失效。监视器只处理本窗口、无弹层、非 Cmd 的按键，其余一律放行。

## 共同约定

- UI 文案一律中文；代码注释从简，只写「为什么」
- 新增缓存类目录要同时接入 `CacheManager`（设置页统计/清理）
- 设置项：macOS 用 `@AppStorage`（`preferredEditorPath`、`autoBuildPreviews`、`libraryBasePath` 已占用）；Android 用 DataStore（同名键已占用，另有 `libraryTreeUri/Display`）
- 外观跟随系统：不设 `preferredColorScheme`/硬编码主题色，颜色全用 semantic colors
- 两平台 XMP sidecar 格式保持一致（`xmp:Rating`），互相可读
