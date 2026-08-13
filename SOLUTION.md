# 听书阅读器 - 解决方案

## 已完成的工作

### 基础功能 (v1.0)
- ✅ TXT文件选择与TTS朗读
- ✅ 播放/停止控制
- ✅ 前台服务通知
- ✅ 已编译APK (5.5MB)

### 编译步骤
```bash
# 1. 设置Gradle wrapper (使用TJArtVolunteer的配置)
cp ~/TJArtVolunteer/gradle.properties ./gradle.properties
cp ~/TJArtVolunteer/gradlew ./gradlew
chmod +x gradlew

# 2. 创建gradle wrapper jar和properties
mkdir -p gradle/wrapper
cp ~/TJArtVolunteer/gradle/wrapper/gradle-wrapper.jar gradle/wrapper/
cp ~/TJArtVolunteer/gradle/wrapper/gradle-wrapper.properties gradle/wrapper/

# 3. 构建Debug APK
./gradlew assembleDebug
```

### 关键修复点

1. **AAPT2 Daemon问题**
   - 在gradle.properties中添加：
   ```properties
   android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
   org.gradle.daemon=false
   org.gradle.parallel=false
   ```

2. **依赖版本冲突**
   - 使用兼容版本：
     - Kotlin: 1.9.20
     - AGP: 8.1.0
     - Material: 1.11.0
     - ConstraintLayout: 2.1.4

3. **TextToSpeech API限制**
   - TTS不支持真正的pause/resume
   - 用stop()替代pause，重新开始播放
   - seekTo()需要自己实现

## 下一步改进

### Phase 1: PDF/EPUB支持
- 添加epublib依赖: `implementation("nl.siegmann.epublib:epublib-core:4.2")`
- 添加PdfiumAndroid: `implementation("com.github.barteksc:pdfiumandroid:2.1.0")`
- 实现阅读器Activity

### Phase 2: 历史记录
- 使用Room数据库
- 创建ReadingHistory实体和DAO
- 在MainActivity中显示历史列表

### Phase 3: 高级功能
- TTS速度调节
- 书签功能
- 字体大小调节
- 夜间模式

## APK位置
`~/audiobook-app/app/build/outputs/apk/debug/app-debug.apk`

## 文件结构
```
audiobook-app/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/audiobook/
│   │   │   ├── MainActivity.kt
│   │   │   └── TtsService.kt
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml
│   │   │   ├── values/strings.xml
│   │   │   └── values/themes.xml
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── gradle.properties
├── build.gradle.kts
└── settings.gradle.kts
```
