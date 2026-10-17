# oshiire

書きながら撮って、写真がそのままメモに入る Android のメモ帳アプリです。メモは Markdown ファイルとして保存するので、フォルダごと Obsidian の vault として開けます。

- Camera2 + Jetpack Compose + Hilt + Navigation3
- MVVM（UI / ViewModel / Repository）。データベースは使わず、.md と画像ファイルを直接読み書き

使い方: https://bunnmei.github.io/oshiire/ （ソースは [`docs/`](docs/)）

```
メモ一覧 ──タップ──▶ メモ編集 ──写真をタップ──▶ 写真に書き込み
   │                  └ フローティングカメラ（窓 / 最小化 / 全画面）
設定（テーマ: システム / ライト / ダーク）
```

## 機能

| 画面 | 内容 |
|---|---|
| メモ一覧 | 更新の新しい順。左にサムネイル（最初の写真、なければメモごとの色のプレースホルダー）、右にタイトル・冒頭文・日時。スワイプで削除（元に戻せる） |
| メモ編集 | タイトル + 文章と写真のブロック。撮った写真はカーソル位置で文章を分割して挿入し、直後にフォーカスを移す。0.5 秒ごとの自動保存。ギャラリーからの挿入、写真の移動・削除 |
| フローティングカメラ | ドラッグで移動、ピンチでズーム、角で大きさ変更、タップ AF/AE、長押しで AE/AF ロック、最小化、前後切替 |
| 全画面カメラ | フラッシュ（オフ/オート/オン/ライト）、タイマー、グリッド、HDR、露出補正、ズームプリセット、レンズ切替、マニュアルフォーカス・露出、ホワイトバランス、シーン |
| 写真に書き込み | 丸・矢印・四角・ペン・文字、6 色・3 段階の太さ、選択して移動・削除・色変更、元に戻す/やり直す。原本と書き込み内容を残すので描き直せる |
| 設定 | テーマ、保存場所、使い方・ソース・バージョン |

カメラの項目はその端末の `CameraCharacteristics` から作るので、対応している機能だけが表示されます。

## 保存形式

```
Android/data/space.webkombinat.oshiire/files/vault/
  2026-10-17_124831.md                 # 1 メモ = 1 ファイル（1 行目が # タイトル）
  attachments/IMG_20261017_124905.jpg  # 本文には ![](attachments/...) と書く
  attachments/IMG_...orig.jpg          # 書き込み前の原本
  attachments/IMG_...jpg.annot.json    # 書き込み（正規化座標）
  .trash/                              # 削除したメモ
```

## コードの構成

```
app/src/main/java/space/webkombinat/oshiire/
  model/       Note・ブロック・書き込みのデータ（純 Kotlin）
  markdown/    Markdown ⇔ メモの変換
  data/        NoteRepository（vault の読み書き）、SettingsRepository（DataStore）
  camera/      Camera2 制御（MobileWebCamera から流用して静止画撮影向けに改造）
  navigation/  Navigation3 のキーと画面遷移、ボトムナビ
  ui/          list / editor / camera / annotate / settings / theme
```

## テスト

```sh
./gradlew testDebugUnitTest          # 単体テスト（JVM）
./gradlew lintDebug testDebugUnitTest assembleDebug   # CI と同じ
```

Markdown 変換、vault の読み書き（並び順・空メモの破棄・ゴミ箱）、写真挿入時のブロック分割、自動保存、書き込みの JSON・当たり判定、テーマ設定をテストしています。

## 自動ビルド (GitHub Actions)

| ワークフロー | タイミング | 内容 |
|---|---|---|
| `CI` | push / PR | lint・単体テスト・デバッグ APK |
| `Release` | `v*` タグの push | 署名済み AAB・APK を GitHub Release に公開。Play の認証情報があれば Google Play にもアップロード |

```sh
# app/build.gradle.kts の versionCode を 1 増やしてコミット・push してから
git tag v1.1.0
git push origin v1.1.0
```

Release に必要な Secrets は [`release.yml`](.github/workflows/release.yml) の先頭に書いてあります。

## ライセンス

[0BSD](LICENSE)
