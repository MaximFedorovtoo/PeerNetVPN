# Android 用 PeerNetVPN（PeerNet）

Android 端末間でインターネット接続を暗号化して共有するアプリです。
Android 10 以降に対応し、root は不要です。画面表示は 8 言語に対応しています。

**0.5.0 は実験的な debug ビルドです。**

## インストールと接続

1. このリポジトリの [GitHub Releases](https://github.com/MaximFedorovtoo/PeerNetVPN/releases) から 0.5.0 debug APK を取得します。
2. 両方の Android 端末で、選択した提供元からのインストールを許可して導入します。
3. 両端末で接続共有を開始し、アップロード速度の測定が終わるまで待ちます。
4. 「マップ」で地域と利用可能なノードを選択します。探索ディレクトリには
   初期設定があり、設定画面で変更できます。
5. 受信側の端末でアプリを選び、Android の VPN 接続を許可します。
   選択したアプリだけがトンネルを使います。選択を変更したら再接続してください。

他のノードの接続を使うには、自分の共有を有効かつ到達可能な状態に保ちます。
接続中のクライアントや最低アップロード量は必要ありません。各端末は最大 3 台に
提供でき、合計速度の上限は測定したアップロード速度の 3 分の 1 です。
クレジット、ウォレット、購入機能はありません。

## 制限とプライバシー

- ノードの発見は接続を保証しません。NAT/CGNAT の組み合わせによっては、
  到達可能な参加端末の中継が必要です。手動 ICE/WebRTC ペアリングに TURN はありません。
- ディレクトリは探索・ペアリング情報を交換し、VPN 通信は中継しません。
  手動招待とペアリングも予備の手段として利用できます。招待情報は秘密にしてください。
- 暗号化は端末間の伝送を保護します。出口ノードには接続先と暗号化されていない
  アプリ通信が見えます。PeerNet は匿名性を保証しません。
- 速度測定は 1 回につき最大 24 MiB の試験データを Selectel、または予備の
  59telecom に送ります。共有開始時、ネットワーク変更時、15 分ごとに実行され、
  セッションが切断されます。その後は再接続が必要です。
  インターネット通信を共有するには、測定の成功が必要です。
- 国の判定には api.country.is を利用します。ノードが申告する国は概算で、
  実際の所在地の証明にはなりません。公開モードではアドレスと鍵が公開されます。
- Android で使えるシステム VPN はプロファイルごとに 1 つです。共有中は前景通知を
  表示し、CPU を起動状態に保ちます。TCP/UDP に対応しますが、ICMP、マルチキャスト、
  出口所有者のプライベートネットワークへの接続は転送しません。DNS は出口を経由します。

## Windows でのローカルビルド

リポジトリのルートで実行します。

```powershell
powershell -ExecutionPolicy Bypass -File tools/build.ps1 :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

スクリプトは `.tools` に Java 17、Gradle 8.13、Android SDK 35 を用意し、
ダウンロードを検証して SDK ライセンスに同意します。
APK: `app/build/outputs/apk/debug/app-debug.apk`。
実機での確認には USB デバッグを有効にした接続端末が必要です。
[メイン README](../../README.md)、[プロトコル](../protocol.md)、
[第三者コンポーネントの通知](../../THIRD_PARTY_NOTICES.txt)も参照してください。

## 言語

[English](README.en.md) · [Русский](README.ru.md) · [中文](README.zh.md) ·
[日本語](README.ja.md) · [Français](README.fr.md) · [Italiano](README.it.md) ·
[Español](README.es.md) · [Español (México)](README.es-MX.md)
