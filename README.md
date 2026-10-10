# ClovaSetup

サービスが終了した日本版 CLOVA（主に CLOVA Desk）を、Android 端末から Bluetooth で初期設定するためのアプリです。
[iemike3/ClovaSendScheme](https://github.com/iemike3/ClovaSendScheme) をフォークして機能を追加しています。

## できること

| ボタン | 送るコマンド | 内容 |
| --- | --- | --- |
| CLOVA を探して接続 | — | ペアリング済みの CLOVA があればそれに、なければ検索して `CLOVA-` / `WAVE-` で始まる端末に接続 |
| デバイス確認 | `APCDRQ` | CLOVA でベルが鳴る |
| FW 情報 | `APGFRQ` | ファームウェアのバージョン、シリアル、トークンの有無 |
| プロトコル | `APPVRQ` | プロトコルバージョン |
| Wi-Fi 設定 | `APGNRQ` → `APSNRQ` | CLOVA が見つけた Wi-Fi から選び、パスワードを送る |
| 認証 (Set Auth) | `APSPRQ` | ダミーの認証コードを送る。CLOVA は接続先の認証サーバーの `/token` でトークンに交換する |
| サーバー変更 | `APSCRQ` | 接続先を自前の認証サーバー / NAVER / LINE に切り替える |
| スキーム送信 | `APSCRQ` | 任意の URL スキームを送る（CLOVA 側で `ACTION_VIEW` で開かれる） |

CLOVA からの応答（`AM....`）は画面下部のログに表示されます。Wi-Fi のパスワードはログに表示しません。

## 自前の認証サーバーでセットアップする手順

LINE の CLOVA サーバーは停止しているため、トークンを発行する認証サーバーを自分で用意します。
CLOVA は `GET {auth}/token?grant_type=authorization_code&code=...` を送り、200 で返った
`{"access_token", "token_type", "expires_in", "refresh_token"}` をトークンとして保存します。

1. 「サーバー変更」→「自前の認証サーバー」で URL（例: `https://192.168.0.162:8444/`）を送る
   - `clovasetup://server_setting/10?cic=...&auth=...` を送るので、セットアップウィザードと cdk service の両方に保存されます
   - CLOVA に選択肢が出たら **cdk service** を選ぶ（CLOVA が再起動します）
2. 再起動後に接続し直し、「Wi-Fi 設定」で認証サーバーに届くネットワークにつなぐ
3. 「認証 (Set Auth)」を押す

## 変更点（フォーク元から）

- 応答を受信して表示するようにした
- 接続前にボタンを押すと落ちる問題を修正
- デバイス確認が `APCD` の 4 バイトしか送っていなかったのを `APCDRQ` + 長さ 0 に修正
- 接続前に検索を止めるようにした（接続が安定する）
- Wi-Fi 設定、認証、FW 情報、プロトコル、サーバー変更のボタンを追加
- commons-codec をやめて `android.util.Base64` を使うようにした

## ビルド

```sh
./gradlew assembleDebug
```

`app/build/outputs/apk/debug/app-debug.apk` ができます（minSdk 28）。
