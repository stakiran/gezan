# gezan 開発手順

開発者向けのドキュメントです。遊び方やインストール方法は [README.md](README.md) を参照してください。

## 必要なもの

- JDK 21
- Git

Gradle は同梱の wrapper（`gradlew` / `gradlew.bat`）を使うので別途インストール不要です。初回は Minecraft 本体や Fabric API のダウンロードが走るため時間がかかります。

## セットアップ

```
git clone https://github.com/stakiran/gezan.git
cd gezan
./gradlew genSources
```

`genSources` は Yarn マッピングで難読化解除した Minecraft のソースを生成します。IDE で Minecraft のクラスにジャンプできるようになるので、最初に一度実行しておくと楽です。Windows では `./gradlew` を `gradlew.bat` に読み替えてください。

## ビルド

```
./gradlew build
```

`build/libs/gezan-<version>.jar` ができます。これを `.minecraft/mods/` に入れれば動作確認できます。`-sources.jar` は配布不要です。

## 開発用クライアントで動かす

```
./gradlew runClient
```

MOD を組み込んだ状態で Minecraft クライアントが起動します。ゲームデータ（セーブ、設定、スクリーンショット）はリポジトリ直下の `run/` に置かれます。`run/` は gitignore 済みです。

サーバーで試す場合は `./gradlew runServer` です。

## VS Code で動かす

```
./gradlew vscode
```

`.vscode/launch.json` が生成され、「Minecraft Client」「Minecraft Server」の起動構成からデバッガ付きで実行できます。Java 拡張機能（Extension Pack for Java）が必要です。

## ソース構成

```
src/main/java/com/stakiran/gezan/
  GezanMod.java        エントリポイント。コマンドとイベントの登録
  GezanCommand.java    /gezan コマンド
  GameManager.java     ゲームの状態管理。セットアップ、勝利・死亡判定、毎tickの処理
  TerrainScanner.java  ボーダー内の地形走査。最高点の探索と規模の計算
  KitManager.java      初期アイテムの配布
  HudManager.java      ボスバーとタイトルの表示
src/main/resources/
  fabric.mod.json      MOD のメタデータと依存関係
memo/
  CONTEXT1.md          初期構想メモ
```

ゲームのロジックはすべてサーバーサイドです。Mixin は使っていません。

## よくいじる場所

- 地上の高さ (`GROUND_Y`)、勝利に必要な秒数 (`WIN_SECONDS`)、ボーダーの大きさ (`BORDER_SIZE`) は `GameManager.java` の先頭にある定数です
- 初期アイテムは `KitManager.java`
- MOD のバージョンは `gradle.properties` の `mod_version`

## Minecraft のバージョンを上げる

`gradle.properties` の `minecraft_version`、`yarn_mappings`、`loader_version`、`fabric_version`、`loom_version` を書き換えます。各バージョンの組み合わせは https://fabricmc.net/develop/ で確認してください。合わせて `src/main/resources/fabric.mod.json` の `depends.minecraft` も更新します。

## 設計メモ

`memo/CONTEXT1.md` は初期構想です。実装を進める中で次の点が変わっています。

- 最高点と地上の高低差が 128 以下のときにリトライする仕様は廃止。地形なりの高低差でそのまま始めます
- 焼き牛肉は 1 スタックから 8 個に削減
- ベッドが壊れたときは全員が死亡し、ベッドは自動で復元
