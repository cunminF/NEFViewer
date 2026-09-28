# NEF Viewer Android

macOS 版 NEF Viewer 的 Android Pad 版本：轻量 Nikon RAW（NEF）选片工具——导入 → 快速浏览 → 打分筛选 → 批量处理/送外部应用。Kotlin + Jetpack Compose（Material 3，跟随系统深色模式）+ Room。

## 功能

**项目管理**
- 每个项目一个图片库：**拷贝模式**（复制到图库目录）或**链接模式**（SAF 原地登记，不动文件）
- 来源一步直达：新建项目页直接选「外接储存卡」（自动定位读卡器卷）或「本机文件夹」
- **逐文件勾选**：文件名 + EXIF 拍摄时间 + 大小 + 归一化尺寸；全选/全不选/**区间选择**（点头尾两个文件选中整段，一个文件夹跨两个项目时只取一段）
- 图库位置可在设置中更改（应用私有目录 或 任意 SAF 文件夹，副本按 `NEF Viewer/<项目名>/` 归档）
- 导入后可自动**全量生成标准预览**（类 Adobe 预渲染，全局进度条、可取消）

**浏览与选片**
- 网格视图：640px 缩略图（内存 + 磁盘双级缓存），评分徽标，点选/双击进单图/长按多选
- 单图视图：缩略图 → 3200px 标准档渐进显示；**左右滑动翻页**；放大 >1.2× 才解码全尺寸（8256×5504）；前后 ±2 张预取
- 打分 `0`–`5`（触控星条或外接键盘数字键）；排序（评分↓/拍摄时间）；筛选（≥3/≥4/仅5/≤2/隐藏未评分）
- 排序实时应用：打分后照片立即按新分数重排
- **批量删除**：删除未评分照片 / 删除当前筛选之外的照片（链接模式会删储存卡源文件，有醒目警告）
- 评分可导出 **XMP sidecar**（Lightroom/Bridge 可读）

**外部应用**
- 双击/编辑按钮用外部编辑器打开 NEF（`ACTION_EDIT`，可在设置中指定默认应用）
- **分享标准预览 JPEG**：像素蛋糕等未注册 NEF 编辑的应用，通过系统分享接收 3200px JPEG

**缓存管理**
- 设置页：按项目查看/清理缓存、孤儿缓存清理、全部清理

## GPU 加速（高通 Adreno 适配）

- **HARDWARE bitmap**：缩略图/标准预览/全尺寸全部解码进 GPU 显存，Compose 显示零上传（实测 Graphics PSS ~150MB）
- **hardware Canvas**：EXIF 方向旋转 + 缩放一次 GPU draw 完成（替代 CPU `createScaledBitmap`），实测 `Pipeline=Skia (Vulkan)`，GPU 帧时间 ~3ms
- 缩放/平移：Compose `graphicsLayer`，GPU RenderNode 原生加速
- JPEG 解码本身走 CPU（libjpeg-turbo NEON）——这是公开 SDK 路径下能拿到的全部硬件加速
- Adreno 最大纹理 16384px > 8256px 全尺寸，单纹理放得下
- 验证机：Lenovo Y700（SM8850 / Snapdragon 8 Elite Gen 5，Adreno，Android 16）

## 系统要求

- Android 8.0+（minSdk 26），target/compile SDK 35；平板横竖屏自适应
- 零运行时权限：图库在应用私有目录，外部文件走 SAF（授权一次持久化）
- NEF 需内含 JPEG 预览（Z8 已实测 870/870；其他机型待验证）

## 构建

```bash
# 依赖：JDK 17（/opt/homebrew/opt/openjdk@17）、Android SDK（local.properties 配 sdk.dir）
./build.sh installDebug        # 包装脚本：设 JAVA_HOME、检查 7892 代理（依赖下载走代理）
./build.sh testDebugUnitTest   # JVM 单测：直接解析 Mac 上 ~/Pictures/NEF Viewer 的 870 张真 NEF
```

`~/.gradle/gradle.properties` 已配置 `127.0.0.1:7892` 代理，Gradle 依赖下载需要代理在线。

## 真机调试工具

- `uitap.py <文本>`：uiautomator dump → 精确匹配节点 → 打印中心坐标，配合 `adb shell input tap` 做 UI 自动化
- 无线调试：`adb connect <pad-ip>:5555`（配对过一次后 TLS 直连）

## 数据位置

| 内容 | 位置 |
|------|------|
| 项目/照片/评分数据库（Room） | `/data/user/0/com.nefviewer.android/databases/nefviewer.db` |
| 缩略图缓存（640px） | `cache/thumbs/<项目ID>/`（内部 cacheDir） |
| 标准预览缓存（3200px） | `cache/previews/<项目ID>/` |
| 默认图库（拷贝模式） | `/sdcard/Android/data/com.nefviewer.android/files/library/<项目名-id>/` |
| 自定义图库 | 设置中自选的 SAF 文件夹下 `NEF Viewer/<项目名-id>/` |

## 技术要点

- **不解 RAW**：Z8 NEF 内嵌全尺寸 JPEG 预览；自写纯 Kotlin TIFF 解析器（`nef/NefParser.kt`），IFD 链 + SubIFD 递归找最大 JPEG，JVM 单测 870 张全过
- **竖拍归一化**：EXIF orientation 导入时读取，宽高入库即交换（orientation 5–8）
- **翻页轻路径**：翻页递增 `PreviewCache.generation` 作废旧全尺寸解码，快速连翻不被大解码堵住
- **手势分层**：未放大时单指滑动让给 Pager 翻页，双指捏合/已放大才接管变换手势
- 链接模式 SAF 权限申请**读写**（写用于 XMP sidecar 与批量删除源文件）

## v1 明确不做

剔除标记（Reject）、颜色标签、EXIF 信息面板、RAW 全解码、编辑功能、云同步、Play 商店分发。

## 许可证

[GPL-2.0](../LICENSE) © 2026 cunminF
