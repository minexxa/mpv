# mpv 播放器（Android）

基于 **libmpv** 内核的安卓视频播放器，不依赖系统 MediaPlayer/ExoPlayer，格式解码全部由
mpv + FFmpeg 完成（MKV / MP4 / AVI / MOV / FLV / TS / RMVB / WebM / HEVC / AV1 / DTS /
H.265，以及 http(s) / rtmp / rtsp / HLS / DASH 网络流）。

- 内核：`dev.jdtech.mpv:libmpv:1.0.0`（mpv 0.41.0 + FFmpeg，MIT 封装，[jarnedemeulemeester/libmpv-android](https://github.com/jarnedemeulemeester/libmpv-android)）
- 界面：Kotlin + AndroidX/Material3，SurfaceView 输出
- minSdk 26（Android 8.0），compileSdk/targetSdk 36

## 功能

| 操作 | 说明 |
| --- | --- |
| 打开文件 | SAF 选择器，可选任意本地视频/音频，`content://` 直接交给 mpv 解码 |
| 打开链接 | http/https/rtmp/rtsp/srt 等网络地址 |
| 其它 App 跳转 | 已注册 `ACTION_VIEW`（video/* audio/* + 流媒体 scheme）与 `ACTION_SEND` 文本分享 |
| 单击画面 | 显示/隐藏控制条（3.5s 自动隐藏，同时进全屏） |
| 双击画面 | 左 1/3 后退 10s，右 1/3 前进 10s，中间播放/暂停 |
| 横向拖动 | 按屏幕宽度比例预览并跳转进度 |
| 纵向拖动 | 右半边音量（0–200%）、左半边亮度 |
| 控制条 | 进度条、-10s/+10s、播放暂停、倍速、字幕轨、音轨、画面铺满、解码方式切换 |
| 后台恢复 | surface 重建后自动接着原文件原进度继续 |

## 关键实现约束

libmpv-android 的 native 层决定了调用顺序（`libmpv/src/main/cpp/main.cpp`、`render.cpp`）：

1. `MPVLib.create(context)` → `mpv_create()`
2. `setOptionString(...)` 与 `attachSurface(surface)` —— `attachSurface` 内部是
   `mpv_set_option("wid", int64)`，**必须在 `init()` 之前**
3. `init()` → `mpv_initialize()` + 事件线程
4. `setPropertyXxx` / `command(...)` 在 `init()` 之后

因此 `MpvEngine.onSurfaceDestroyed()` 直接 `destroy()` 整个实例，`onSurfaceCreated()`
重新走一遍 1→3，再恢复 uri 与进度；这样避免依赖 init 之后再改 `wid` 的行为。

另外这个绑定没有 mpv 的 node API，`track-list` 取不到明细，所以字幕/音轨切换用
`command(["cycle","sid"])` 循环，而不是列表选择。

## 构建

```bash
# 需要 JDK 17+、Android SDK（platforms;android-36、build-tools;36.0.0）
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 真机（arm64，默认，APK ≈30MB）
gradle :app:assembleDebug

# 加上 32 位老机器
gradle :app:assembleDebug -PmpvAbis=arm64-v8a,armeabi-v7a

# 模拟器（x86_64）
gradle :app:assembleDebug -PmpvAbis=x86_64

# release（当前 release 也复用 debug 签名，方便直接侧载；正式发布请换成自己的 keystore）
gradle :app:assembleRelease
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 已实测的两套工具链

compileSdk 必须是 36（AAR 的 `aar-metadata.properties` 里 `minCompileSdk=36`），AGP 与 Gradle 的版本下限互相咬死，两套都跑通过 `assembleDebug`：

| 组合 | Gradle | AGP | 备注 |
| --- | --- | --- | --- |
| A（本工程默认） | 8.11.1 | 8.9.2 | AGP 原生支持 compileSdk 36 |
| B（不想升级 Gradle） | 8.10 | 8.7.3 | 靠 `gradle.properties` 里 `android.suppressUnsupportedCompileSdk=36` 压制 warning |

其它版本关系（实测报错）：AGP 8.8.x 要求 Gradle ≥ 8.10.2；8.9.x / 8.10.x 要求 ≥ 8.11.1；8.11.x / 8.12.x / 8.13.x 要求 ≥ 8.13。Kotlin 插件固定 2.2.10。

SDK license 提示：cmdline-tools 23.x 的 `sdkmanager --licenses` 会输出 `The --licenses option is no longer needed` 且不创建 `licenses/`；若构建时报未接受许可，手动写 `$ANDROID_HOME/licenses/android-sdk-license`（内容 `8933bad161af41ecb1ddaf0059629e0d`）。

## 依赖注意

- **不要**把 `kotlin-stdlib` 钉到 1.8.x。该 AAR 的 Gradle metadata 依赖 `kotlin-stdlib:2.2.10`，
  降级会导致 `kotlin-stdlib` 版本冲突或运行时 `NoSuchMethodError`。本工程用 Kotlin 插件 2.2.10 对齐。
- 不需要 NDK，也不需要 CMake：AAR 已经带全部预编译 `.so`。
- `services.gradle.org` 的发行版下载会 301/307 重定向到 GitHub，部分地区很慢；
  可以直接用 `https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip`。

## 许可

应用代码 MIT。打包进 APK 的原生库为 LGPL/GPL 组合（mpv、FFmpeg、libass、libplacebo、
dav1d 等），对外分发时请保留版权说明并提供再链接/源码要约；`dev.jdtech.mpv:libmpv`
自身是 MIT（继承 mpv-android，版权行含 Ilya Zhuravlev / sfan5）。
