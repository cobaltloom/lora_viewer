# lora_viewer

LoRa通信でグライダーの位置・高度を送信する Trail Route View
(https://www.trailrouteview.com/) を、iPhone/iPad で見やすく表示するための
SwiftUI アプリです。

- 現在位置画面: 地図上にグライダーごとのアイコンと高度を表示し、一定間隔で自動更新
- メンバー一覧画面: 全機の高度・受信時刻・通信種別(GPS/セル)を一覧表示
- 行動軌跡画面: 期間を指定して過去の飛行軌跡を地図上に表示

## 必要環境

- Mac + Xcode 16 以降
- iOS 17.0 以降の実機 or シミュレータ

## 開き方

1. `LoraViewer.xcodeproj` を Xcode で開く
2. アプリを一度起動し、右上の歯車アイコンから「設定」を開く
3. 「シークレットキー (key)」を入力する

   Safari でサイト(`https://www.trailrouteview.com/user/jsal/gmap/view.php` など)
   を開き、`開発 > Web インスペクタを表示` の「ネットワーク」タブで
   `mapapi.php` へのリクエストを確認すると、URL に `key=...` というパラメータが
   含まれています。その値を設定画面に入力してください。
4. ベースURL(既定値: `https://www.trailrouteview.com/user/jsal/gmap/`)を
   自分のアカウントのURLに合わせて変更してください。

## 構成

```
LoraViewer/
  LoraViewerApp.swift          エントリポイント
  Models/                      JSONレスポンスのモデルとデコード処理
  Services/                    APIクライアントと設定(APISettings)
  Views/                       地図・一覧・設定・行動軌跡のSwiftUI画面
  Utilities/                   地図範囲計算などの補助
```

## 注意事項

このアプリは Trail Route View (株式会社マーブル) が非公式に提供している
PHPエンドポイント(`load_config.php` / `mapapi.php` / `query_position_log.php`)
を直接呼び出す非公式クライアントです。エンドポイントの仕様が変更されると
動作しなくなる可能性があります。
