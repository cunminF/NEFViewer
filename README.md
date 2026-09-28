# NEF Viewer

一个为 macOS 打造的轻量 Nikon RAW（NEF）选片工具——替代 Adobe Lightroom/Bridge 完成「导入 → 快速浏览 → 打分筛选 → 送外部编辑器」的选片（culling）工作流。设计目标只有一个：**快**。

## 功能

**项目管理**
- 每个项目对应一个图片库：**拷贝模式**（从储存卡拷贝到本地，保留源目录相对结构）或**链接模式**（原地索引，不动文件）
- 自动检测已挂载的相机储存卡（含 DCIM 的卷），整卡或卡内具体文件夹一键作为源
- 导入前**逐文件勾选**：列表含文件名、EXIF 拍摄时间、大小；支持全选/全不选/⇧连选范围——一个文件夹跨两个项目时只选其中一段
- 导入后可自动**全量生成标准预览**（类 Adobe 的预览构建，带全局进度条、可取消）
- 链接模式下储存卡拔出：项目显示离线横幅，已缓存缩略图仍可浏览

**浏览与选片**
- 网格视图：内嵌 JPEG 预览提取（不解 RAW），缩略图磁盘缓存，第二次打开秒开
- 单图视图：640px 缩略图（瞬时）→ 3200px 标准档（磁盘缓存）渐进显示；前后 ±2 张预取；放大超过 1.2× 才按需解码全尺寸（8256×5504）内嵌预览
- 打分 `1`–`5`、`0` 清除；排序（评分↓/拍摄时间）；筛选（≥N/≤N/隐藏未评分）
- `⌘E` 或双击在外部编辑器打开（默认 Pixelmator Pro，可换任意 App）
- 评分存应用数据库，可一键导出 **XMP sidecar**（Lightroom/Bridge 可读）

**缓存管理**
- 设置页（`⌘,`）：通用设置（默认库位置、自动预渲染、外部编辑器）+ 缓存管理（按项目查看/清理、孤立缓存清理、全部清理）
- 项目右键菜单：生成全部标准预览 / 清理项目缓存 / 重命名 / 删除

## 快捷键

| 位置 | 按键 | 作用 |
|------|------|------|
| 网格 | `0`–`5` | 给选中照片打分/清除 |
| 网格 | `←→↑↓` | 移动选中 |
| 网格 | `⏎` / 双击 | 进入单图视图 |
| 单图 | `←→` | 翻页 |
| 单图 | `0`–`5` | 给当前照片打分 |
| 单图 | `+` / `-` 或触控板捏合 | 缩放（>1.2× 自动加载全尺寸） |
| 单图 | 双击 | 在外部编辑器打开 |
| 单图 | `Esc` | 返回网格 |
| 全局 | `⌘E` | 在外部编辑器打开选中项 |
| 全局 | `⌘,` | 设置 |

## 系统要求

- macOS 26+（开发环境 macOS 27 / Xcode 26 SDK / Swift 6.4）
- 依赖 Apple ImageIO 的 Nikon RAW 支持（Z8 已实测；其他机型需验证，HE/HE* 高效率压缩需另行确认）

## 构建

```bash
brew install xcodegen   # 一次性
cd NEFViewer
xcodegen generate
xcodebuild -scheme NEFViewer -configuration Debug build
```

产物：`~/Library/Developer/Xcode/DerivedData/NEFViewer-*/Build/Products/Debug/NEF Viewer.app`

注意：新增/删除源文件后必须重新 `xcodegen generate`（工程文件由 `project.yml` 生成，不要手改 `.xcodeproj`）。

## 数据位置

| 内容 | 位置 |
|------|------|
| 项目/照片/评分数据库（SwiftData） | `~/Library/Application Support/default.store` |
| 网格缩略图缓存（640px） | `~/Library/Caches/NEFViewer/thumbs/<项目ID>/` |
| 标准预览缓存（3200px） | `~/Library/Caches/NEFViewer/previews/<项目ID>/` |
| 默认图库根目录（拷贝模式） | `~/Pictures/NEF Viewer/<项目名>/`（可在设置中更改） |

## 技术要点

- **SwiftUI + SwiftData**，无 Sandbox（个人工具，直接文件路径访问）
- **不解 RAW**：Z8 NEF 内嵌全尺寸 JPEG 预览（提取 ~0.2s），浏览/选片全程走内嵌预览；ImageIO 全尺寸解码已实测可用（0.85s/张）作为缩放兜底
- 竖拍照片：导入时读取 EXIF orientation 并归一化宽高，保证网格布局正确
- 评分可导出 XMP sidecar 与 Adobe 生态互操作

## v1 明确不做

剔除标记（Reject）、颜色标签、EXIF 信息面板、RAW 全解码查看、编辑功能、云同步、Sandbox/App Store 分发。

## 许可证

[GPL-2.0](LICENSE) © 2026 cunminF
