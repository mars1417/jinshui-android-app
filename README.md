# 金水新苑社区 Android App

金水新苑社区治理服务平台 WebView 壳 + 自动更新。

## 功能
- 服务预约 · 一键报修 · 邻音社区 · 通知推送 · 服务动态
- 启动检查更新（方案B：不阻塞进入）
- 下载进度条 + 真实网速
- 多入口 URL 自动回退

## 自动更新链路
```
git push → GitHub Actions 构建签名 → Release vN
→ apk-sync cron(30min) → 8601 static/apk
→ APK启动 → /api/apk/check → 弹窗更新
```
