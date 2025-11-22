# AutoBot - Android Automation Control Platform

Android 自动化控制平台，提供通过 HTTP API 控制 Android 设备的功能。

## 功能特性

- ✅ 无线 ADB 配对和连接
- ✅ Shell Server（运行在 Shell 权限下）
- ✅ UI 自动化（获取 UI 树、截图、点击、滑动、按键）
- ✅ HTTP API 接口（端口 19090）
- ✅ 支持多设备同时连接
- ✅ 毫秒级 UI 树获取性能

## API 端点

### 健康检查
```
GET http://<device-ip>:19090/api/hello
```

### 获取 UI 树（XML 格式）
```
GET http://<device-ip>:19090/api/screenXml?isWait=0
```

### 获取 UI 树（JSON 格式）
```
GET http://<device-ip>:19090/api/screenJson?isWait=0
```

### 截图
```
GET http://<device-ip>:19090/api/screenshot
```

### 点击
```
POST http://<device-ip>:19090/api/click
Body: {"x": 100, "y": 200}
```

### 滑动
```
POST http://<device-ip>:19090/api/swipe
Body: {"x1": 100, "y1": 200, "x2": 300, "y2": 400, "duration": 300}
```

### 按键
```
POST http://<device-ip>:19090/api/pressKey
Body: {"keyCode": 4}  // 4=BACK, 3=HOME, 82=MENU
```

## 技术架构

### 主应用（App）
- **语言**: Kotlin
- **框架**: Android SDK
- **功能**: 
  - 无线 ADB 配对（mDNS 发现 + TLS 连接）
  - Shell Server 部署和管理
  - 权限请求（悬浮窗、位置等）

### Shell Server
- **格式**: DEX JAR
- **运行方式**: `app_process` 以 Shell 权限运行
- **HTTP 框架**: NanoHTTPD
- **核心功能**: 
  - UiAutomation 服务（反射调用）
  - UI 树遍历（优化的迭代算法）
  - 输入事件注入

## 构建说明

### 环境要求
- Android Studio 2023.3.1+
- JDK 17
- Android SDK 34+
- Gradle 8.x

### 编译步骤

1. **编译 Shell Server DEX JAR**
```bash
cd androidAPP
.\gradlew.bat :shell-server:assembleDexJar
```

2. **复制 DEX JAR 到 App Assets**
```bash
Copy-Item shell-server\build\outputs\shell-server.jar app\src\main\assets\shell-server\
```

3. **编译主应用**
```bash
.\gradlew.bat :app:assembleDebug
```

4. **安装到设备**
```bash
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 使用方法

### 1. 启用无线调试
1. 进入手机 **设置 > 开发者选项 > 无线调试**
2. 启用无线调试

### 2. 配对设备
1. 打开 AutoBot 应用
2. 授予必要权限（悬浮窗、位置）
3. 点击 **"Start Pairing"**
4. 按照提示进入开发者选项，点击 **"使用配对码配对设备"**
5. 输入应用显示的配对码

### 3. 启动 Shell Server
1. 配对成功后，点击 **"Start Shell Server"**
2. 等待 5-10 秒，Shell Server 启动完成
3. 在浏览器中访问 `http://<手机IP>:19090/api/hello` 测试连接

### 4. 使用 API
通过 HTTP 请求调用各种自动化 API，实现远程控制。

## 性能优化

### UI 树获取性能
- ✅ **根节点缓存机制**：通过 `AccessibilityEvent` 监听器实时缓存根节点
  - 首次请求：0.5-2 秒（需要初始化 UiAutomation）
  - **后续请求：0.1-0.5 秒**（使用缓存，极快！⚡）
- ✅ 使用 `XmlSerializer` 替代字符串拼接，提升序列化性能
- ✅ 立即释放子节点（`child.recycle()`），减少内存占用
- ✅ 限制遍历深度（200 层），防止栈溢出
- ✅ `isWait=0` 时跳过 `waitForIdle`，快速响应

### Shell Server 稳定性
- ✅ 使用 `nohup` 命令后台运行，确保进程不会随 ADB 断开而终止
- ✅ 支持停止和重启功能，无需重启应用
- ✅ 自动健康检查和错误恢复

## 故障排查

### Shell Server 启动失败
```bash
# 查看日志
adb shell cat /sdcard/shell-server.log

# 检查进程
adb shell ps -ef | grep shell-server

# 检查端口
adb shell netstat -an | grep 19090
```

### ADB 连接问题
```bash
# 重新配对
adb devices

# 清理 ADB 密钥
adb shell pm clear com.autobot
```

## 许可证

MIT License

## 致谢

- [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) - 轻量级 HTTP 服务器
- Android UiAutomation Framework
