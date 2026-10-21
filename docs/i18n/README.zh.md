# Android 版 PeerNetVPN（PeerNet）

在 Android 设备之间加密共享互联网连接。需要 Android 10 或更高版本，
无需 root。应用界面支持八种语言。

**0.5.0 是实验性 debug 构建。**

## 安装与连接

1. 从本仓库的 [GitHub Releases](https://github.com/MaximFedorovtoo/PeerNetVPN/releases) 下载 0.5.0 debug APK。
2. 在两台 Android 设备上安装，并允许从所选来源安装应用。
3. 在两台设备上开启连接共享，等待上传速度测量完成。
4. 打开“地图”，选择地区和可用节点。发现目录已有默认配置，
   可在设置中修改。
5. 在接收设备上选择应用，并允许 Android VPN 连接。
   只有所选应用使用隧道。更改应用选择后，需要重新连接。

要使用其他节点的连接，自己的共享必须保持开启且可访问。
不要求已有客户端，也不要求最低上传流量。每台设备最多服务三台设备，
它们共用的速度上限为测得上传速度的三分之一。没有积分、钱包或购买功能。

## 限制与隐私

- 发现节点并不保证连接成功。某些 NAT/CGNAT 组合需要可访问的参与节点
  充当中继；手动 ICE/WebRTC 配对没有 TURN 服务器。
- 目录交换发现与配对数据，不转发 VPN 流量。手动邀请和配对仍可作为
  备用方式。邀请含有机密信息，请妥善保管。
- 加密保护设备之间的传输。出口节点可见目标地址和未加密的应用流量。
  PeerNet 不承诺匿名性。
- 每轮测速最多向 Selectel 或备用服务商 59telecom 发送 24 MiB 测试数据。
  测速会在开启共享、网络变化及每隔 15 分钟时进行，并断开当前会话。
  之后需要重新连接。共享互联网流量前必须成功完成测速。
- 国家查询使用 api.country.is。节点声明的国家只是近似信息，不能证明
  实际位置。公共模式会公开地址和密钥。
- Android 每个配置文件仅允许一个系统 VPN。共享会显示前台通知并保持
  CPU 唤醒。支持 TCP/UDP；不路由 ICMP、多播及对出口设备所有者私有网络
  的访问。DNS 通过出口节点处理。

## 在 Windows 本地构建

在仓库根目录运行：

```powershell
powershell -ExecutionPolicy Bypass -File tools/build.ps1 :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

脚本在 `.tools` 中准备 Java 17、Gradle 8.13 和 Android SDK 35，验证下载文件，
并接受 SDK 许可。APK 路径：`app/build/outputs/apk/debug/app-debug.apk`。
真实设备检查需要连接已启用 USB 调试的设备。
另见[主 README](../../README.md)、[协议](../protocol.md)和
[第三方声明](../../THIRD_PARTY_NOTICES.txt)。

## 语言

[English](README.en.md) · [Русский](README.ru.md) · [中文](README.zh.md) ·
[日本語](README.ja.md) · [Français](README.fr.md) · [Italiano](README.it.md) ·
[Español](README.es.md) · [Español (México)](README.es-MX.md)
