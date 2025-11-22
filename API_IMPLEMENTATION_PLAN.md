# API 实现计划

## 📊 当前状态

### ✅ 已实现的 API（7 个）
1. `/api/hello` - 健康检查
2. `/api/screenXml` - 获取 UI 树（XML）
3. `/api/screenJson` - 获取 UI 树（JSON）
4. `/api/screenshot` - 截图（Base64）
5. `/api/click` - 点击
6. `/api/swipe` - 滑动
7. `/api/pressKey` - 按键

### ⏳ 待实现的 API（按优先级分组）

---

## 🎯 第一优先级：基础信息和常用操作（15 个）

### 1. 设备和系统信息
- [ ] `/api/getDeviceId` - 获取设备 ID
- [ ] `/api/getIp` - 获取设备 IP 地址
- [ ] `/api/version` - 获取版本号
- [ ] `/api/screenInfo` - 获取屏幕信息（宽度、高度、方向）
- [ ] `/api/screenRotation` - 获取屏幕方向
- [ ] `/api/getSystemInfo` - 获取系统信息（完整）

### 2. 截图优化
- [ ] `/api/screenShot` - 截图（返回图片）
- [ ] `/api/screenShotBase64` - 截图（Base64，带前缀）

### 3. 高级输入操作
- [ ] `/api/longClick` - 长按点击
- [ ] `/api/press` - 长按点击（指定时长）
- [ ] `/api/inputText` - 输入字符串（支持中文）
- [ ] `/api/clearText` - 清除输入框文本

### 4. 剪切板操作
- [ ] `/api/setClipText` - 设置剪切板
- [ ] `/api/getClipText` - 获取剪切板

### 5. 应用管理
- [ ] `/api/getTopActivity` - 获取顶层 Activity

---

## 🎯 第二优先级：高级手势和应用管理（12 个）

### 6. 高级手势
- [ ] `/api/gesture` - 单指手势
- [ ] `/api/gestures` - 多指手势

### 7. 应用管理（完整）
- [ ] `/api/getStartActivity` - 根据包名获取启动类
- [ ] `/api/startPackage` - 启动应用
- [ ] `/api/stopPackage` - 停止应用
- [ ] `/api/clearPackage` - 清除应用数据
- [ ] `/api/getAllPackage` - 获取所有应用列表
- [ ] `/api/getPackageInfo` - 获取应用详细信息

### 8. 屏幕控制
- [ ] `/api/turnScreenOff` - 熄屏
- [ ] `/api/turnScreenOn` - 亮屏

### 9. Shell 命令
- [ ] `/api/execCmd` - 执行 Shell 命令

### 10. 设备名称
- [ ] `/api/setDisplayName` - 设置设备名称
- [ ] `/api/getDisplayName` - 获取设备名称

---

## 🎯 第三优先级：文件和通讯功能（13 个）

### 11. 文件操作
- [ ] `/api/listFile` - 列出文件夹
- [ ] `/api/upload` - 上传文件
- [ ] `/api/download` - 下载文件
- [ ] `/api/delFile` - 删除文件

### 12. 联系人管理
- [ ] `/api/getAllContact` - 获取所有联系人
- [ ] `/api/insertContact` - 插入联系人
- [ ] `/api/deleteContact` - 删除联系人

### 13. 短信管理
- [ ] `/api/getAllSms` - 获取所有短信

### 14. 电话和短信
- [ ] `/api/callPhone` - 拨打电话
- [ ] `/api/endCall` - 挂断电话
- [ ] `/api/sendSms` - 发送短信

### 15. 字符输入（ASCII）
- [ ] `/api/inputChar` - 输入字符（ASCII）

### 16. 录屏
- [ ] `/api/startRecoreScreen` - 开始录屏
- [ ] `/api/stopRecoreScreen` - 结束录屏

---

## 🎯 第四优先级：扩展功能（7 个）

### 17. 音乐播放
- [ ] `/api/playMusic` - 播放音乐（网络）
- [ ] `/api/stopMusic` - 停止音乐

### 18. 脚本执行（AutoX.js）
- [ ] `/api/execScript` - 执行 AutoX.js 脚本
- [ ] `/api/stopAllScript` - 停止所有脚本

### 19. 安全模式
- [ ] `/api/turnSafeModeOn` - 开启安全模式
- [ ] `/api/turnSafeModeOff` - 关闭安全模式
- [ ] `/api/isSafeMode` - 查询安全模式状态

### 20. 服务控制
- [ ] `/api/exit` - 退出服务

---

## 📋 实现统计

- **已实现**: 7 个 API
- **待实现**: 50+ 个 API
- **总计**: 57+ 个 API

---

## 🚀 实现策略

### 阶段 1：基础增强（第一优先级，2-3 天）
实现设备信息、高级输入、剪切板等常用 API，达到 **22 个 API**。

### 阶段 2：应用管理和手势（第二优先级，2-3 天）
实现应用管理、高级手势、Shell 命令等，达到 **34 个 API**。

### 阶段 3：文件和通讯（第三优先级，3-4 天）
实现文件操作、联系人、短信、电话等功能，达到 **47 个 API**。

### 阶段 4：扩展功能（第四优先级，1-2 天）
实现音乐、录屏、脚本执行等扩展功能，达到 **54+ 个 API**。

### 阶段 5：Web 前端（3-5 天）
开发投屏控制的 Web 界面。

---

## 📝 技术要点

### Shell Server 需要的权限
- `android.permission.READ_CONTACTS` - 读取联系人
- `android.permission.WRITE_CONTACTS` - 写入联系人
- `android.permission.READ_SMS` - 读取短信
- `android.permission.CALL_PHONE` - 拨打电话
- `android.permission.READ_PHONE_STATE` - 读取电话状态
- `android.permission.SEND_SMS` - 发送短信
- `android.permission.READ_EXTERNAL_STORAGE` - 读取存储
- `android.permission.WRITE_EXTERNAL_STORAGE` - 写入存储

### 需要的 Android API
- `ContactsContract` - 联系人管理
- `Telephony` - 短信和电话
- `PackageManager` - 应用管理
- `ActivityManager` - Activity 管理
- `ClipboardManager` - 剪切板
- `MediaRecorder` - 录屏
- `File I/O` - 文件操作

---

## ⚡ 快速开始

让我们从**第一优先级**开始，首先实现：
1. 设备和系统信息 API
2. 高级输入操作
3. 剪切板操作

这些是最常用且相对简单的 API。

