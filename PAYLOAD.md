# 需要自备的 payload 文件（放进 assets/payload/）

本仓库只开源安装器源码，以下 4 个文件是第三方组件，不随仓库分发，请自行准备后放入 assets/payload/ 再构建：

- `gsf.apk` — sha256: 86891b174301f06a1c84187b545a0a2a57044c6b768f3e84e865908743349692
- `gms.apk` — sha256: 896de0917313504cd8406b725b4cb420bfea26e1a50f7dd37f9175c0bfbab2ac
- `vending.apk` — sha256: 1ee7de9512c27a310b7d69eb6eade9d7a3bfeff4dd0fc59f5b0e18fd1ed481cb
- `backup.zip` — sha256: 5392a9d49046d6fa2018c8c30657c51ee9e225ec0095a06f4d285620c3aa4693

- gsf.apk：microG Services Framework Proxy（com.google.android.gsf v0.1.0）
- gms.apk：microG 服务华为版（com.google.android.gms 0.3.15.250932-hw），microG 官方开源项目（Apache-2.0）的华为适配版
- vending.apk：Google Play 商店（com.android.vending 51.7.21-31，需要安卓 12 底子）
- backup.zip：华为备份恢复数据，内含谷歌服务助手（com.lzplay.helper），用于第 3-4 步的备份恢复激活流程；恢复密码 eloygomeztv2020
