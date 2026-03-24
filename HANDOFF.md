# SuperVertex 引き継ぎ資料

> **作成日:** 2026-02-25
> **対象バージョン:** 1.19.4
> **ベース:** Purpur → Plazma → SuperVertex（独自フォーク）

---

## 1. プロジェクト概要

SuperVertex は、**低クロック CPU 環境（2.3GHz Xeon 等）で 12 人規模のサーバーを安定稼働させる**ことを目的に開発した Minecraft サーバーソフトウェアです。

- ベースは **Purpur 1.19.4**（Plazma 経由）
- 独自の最適化パッチを 12 本追加
- **目標指標:** MSPT ≤ 30ms（快適）/ ≤ 40ms（許容）/ > 45ms（危険）
- プラグイン 100 本超・コマンド多用環境を想定

---

## 2. ディレクトリ構成

```
C:\Users\Mitsuki ASAMA\Music\
├── SuperVertex\                  ← ソースリポジトリ
│   ├── SuperVertex-Server\       ← サーバー本体ソース
│   │   ├── src\main\java\
│   │   │   ├── dev\supervertex\  ← SuperVertex 独自クラス
│   │   │   │   ├── SuperVertexConfig.java
│   │   │   │   ├── SuperVertexCommand.java
│   │   │   │   ├── SuperVertexThreadPool.java
│   │   │   │   └── RegionizedChunkTicker.java
│   │   │   └── net\minecraft\    ← パッチ済みバニラソース
│   │   └── build.gradle.kts
│   ├── patches\                  ← パッチファイル（0001〜0012）★重要
│   └── build\libs\               ← ビルド成果物 JAR
└── Honban\                       ← 本番サーバーディレクトリ
    ├── supervertex.jar           ← デプロイ済み JAR（61MB）
    ├── supervertex.yml           ← SuperVertex 設定
    ├── plugins\                  ← プラグイン群
    └── world\ 他各ワールド\
```

---

## 3. ビルド手順

### 環境要件
- Java 17 以上
- Git
- Windows PowerShell（デプロイ時のプロセス強制終了に使用）

### ビルドコマンド

```bash
# リポジトリルート (SuperVertex\) で実行

# 【通常ビルド】ソース編集後は必ずこの順で実行
./gradlew rebuildPatches    # ソース変更をパッチファイルに保存 ← 必須！
./gradlew createReobfPaperclipJar  # JAR 生成

# 【初回 or パッチ適用】
./gradlew applyPatches      # パッチからソース再生成
./gradlew createReobfPaperclipJar
```

> ⚠️ **重要:** ソースを直接編集した後に `applyPatches` を実行すると変更が消えます。
> 必ず **`rebuildPatches` → `createReobfPaperclipJar`** の順序を守ること。

### 成果物
```
SuperVertex\build\libs\SuperVertex-paperclip-1.19.4-R0.1-SNAPSHOT-reobf.jar
```

---

## 4. デプロイ手順

```powershell
# 1. サーバープロセスを停止（管理コンソールで /stop 推奨）
#    強制終了が必要な場合:
powershell -Command "Get-Process -Name java | Stop-Process -Force"

# 2. 旧 JAR を削除（ファイルロックエラー対策）
Remove-Item -Force "C:\Users\Mitsuki ASAMA\Music\Honban\supervertex.jar"

# 3. 新 JAR をコピー
Copy-Item "C:\Users\Mitsuki ASAMA\Music\SuperVertex\build\libs\SuperVertex-paperclip-1.19.4-R0.1-SNAPSHOT-reobf.jar" `
          "C:\Users\Mitsuki ASAMA\Music\Honban\supervertex.jar"

# 4. サーバー起動
cd "C:\Users\Mitsuki ASAMA\Music\Honban"
java -jar supervertex.jar nogui
```

> ⚠️ `別のプロセスがファイルにアクセスできません` エラーが出た場合は、PowerShell で java プロセスを強制終了してから削除してください。

---

## 5. パッチ一覧

| # | ファイル名（patches/） | 内容 |
|---|----------------------|------|
| 0001 | `0001-Rebrand-to-SuperVertex.patch` | Plazma → SuperVertex リブランド |
| 0002 | `0002-Add-SuperVertex-configuration-system...` | `supervertex.yml` 設定システム追加 |
| 0003 | `0003-Async-entity-tracker...` | エンティティトラッカーを ForkJoinPool で非同期化 |
| 0004 | `0004-Mob-spawn-throttling...` | モブスポーン間隔の分散（設定値 tick ごとに実行） |
| 0005 | `0005-Spread-chunk-ticking...` | チャンクランダムティックの分散処理 |
| 0006 | `0006-Fix-build.gradle.kts...` | supervertex-api 依存関係修正 |
| 0007 | `0007-Add-Regionized-Chunk-Ticking-RCT.patch` | **RCT 実装**（最大パッチ） |
| 0008 | `0008-SuperVertex-plugin-compat-fix-async-player-save.patch` | DH 互換修正 + プレイヤーデータ非同期保存 |
| 0009 | `0009-SuperVertex-fix-getBukkitVersion...` | `getBukkitVersion()` 修正（DH 初期化失敗の根本原因） |
| 0010 | `0010-SuperVertex-skip-empty-world-chunk-tick...` | 空ワールドのチャンクティックスキップ + スポーン最適化 |
| 0011 | `0011-SuperVertex-per-tick-permission-cache.patch` | 1tick あたりのパーミッションキャッシュ |
| 0012 | `0012-SuperVertex-add-sv-command...` | `/sv reload` コマンド追加 |
| 0013 | `0013-SuperVertex-Fix-RCT-deadlock...` | RCT デッドロック修正（専用 RCT_POOL + CountDownLatch） |
| 0014 | `0014-SuperVertex-Disable-RCT-by-default...` | RCT デフォルト無効化 |
| 0015 | `0015-SuperVertex-RCT-Phase-1...` | **RCT Phase 1**: tickChunk 分解 + 非 RSA 鍵 Malformed 修正 |
| 0016 | `0016-SuperVertex-RCT-Phase-2...` | **RCT Phase 2**: unsafe ブロック Deferred 実行（RCT 安全有効化） |

---

## 6. 設定ファイル（supervertex.yml）

```yaml
# C:\Users\Mitsuki ASAMA\Music\Honban\supervertex.yml
config-version: 1

performance:
  # 非同期スレッドプールサイズ (0=CPU コア数に自動設定)
  async-thread-pool-size: 0

  # エンティティトラッカーを非同期化 (ForkJoinPool)
  async-entity-tracker: true

  # モブスポーンを N tick に 1 回に制限 (1=毎tick, 2=半分, ...)
  mob-spawn-throttle-ticks: 1

  # チャンクティックの分散係数
  chunk-tick-spread-factor: 1

  # Regionized Chunk Ticking (RCT) - 並列チャンクティック
  regionized-chunk-ticking: true

  # RCT でシリアル処理に切り替えるチャンク数しきい値
  rct-serial-threshold: 4

  # プレイヤーデータを非同期で保存
  async-player-save: true

  # プレイヤーが 0 人のワールドのチャンクティックをスキップ
  skip-empty-world-chunk-tick: true

  # パーミッションチェック結果を 1tick キャッシュ
  permission-cache: true
```

設定の再読み込み: `/sv reload`（OP レベル 4 必要）

---

## 7. SuperVertex 独自クラス詳細

### `SuperVertexConfig.java`
設定管理の中心。リフレクションで `private static void xxxx()` メソッドを自動収集し、起動時に一括実行する Paper 準拠のパターン。

**追加方法（新設定項目）:**
```java
// フィールド定義
public static boolean myOption = false;

// private メソッドを追加（自動で呼ばれる）
private static void myOption() {
    myOption = getBoolean("performance.my-option", myOption);
}
```

### `SuperVertexThreadPool.java`
`ForkJoinPool TRACKER_POOL` — 非同期エンティティトラッカー・非同期プレイヤーセーブで共用するスレッドプール。スレッド名は `supervertex-tracker-{n}`。

### `RegionizedChunkTicker.java`
RCT の実装。Union-Find でチャンクを隣接グループ（リージョン）に分け、各リージョンを並列ティック。`rctSerialThreshold` 以下のチャンク数ではシリアル処理（Union-Find のオーバーヘッド回避）。

### `SuperVertexCommand.java`
`/sv` コマンドの Brigadier 登録クラス。現在は `/sv reload` のみ。

---

## 8. コマンド

| コマンド | 説明 | 権限 |
|----------|------|------|
| `/sv reload` | `supervertex.yml` を再読み込み | OP 4 / `supervertex.command` |

---

## 9. 既知の問題・注意事項

### ⚠️ Windows ファイルロック
`supervertex.jar` はサーバー起動中にロックされる。デプロイ時は必ず java プロセスを停止してから。

### ⚠️ applyPatches でソースが上書きされる
ソースを直接編集しても `applyPatches` を実行すると消える。
→ 編集後は必ず `rebuildPatches` を先に実行すること。

### DecentHolograms について
Patch 0008・0009 で対応済み。DH は `getBukkitVersion()` の戻り値でバージョン判定しており、`Versioning.java` の pom.properties パスを `dev.supervertex/supervertex-api` に修正済み。

### RCT (Patch 0007 + 0013〜0016) の安全性
- Union-Find で隣接チャンクは必ず同一リージョンに入るため、並列実行でも隣接チャンク間のデータ競合は発生しない。
- Phase 2 (Patch 0016) で 18 種の unsafe ブロック（CropBlock・FireBlock 等）の randomTick を
  RCT_DEFERRED キューに積み、latch.await() 後にメインスレッドでドレインする。
  全 Bukkit イベント（BlockGrowEvent・BlockSpreadEvent 等）は保持される。
- `regionized-chunk-ticking: true` で安全に運用可能。

### 非同期プレイヤーセーブ (Patch 0008)
- NBT スナップショット生成はメインスレッドで同期実行（安全）
- ディスク書き込みのみ非同期
- 同一 UUID のプレイヤーがログインする際、保存完了を 5 秒まで待機してから読み込む

---

## 10. パッチ追加手順（将来の開発者向け）

```
1. SuperVertex-Server\src\main\java\ 以下のソースを編集
2. ./gradlew rebuildPatches          ← パッチファイルに保存
3. git add patches\                  ← パッチファイルをステージ
4. git commit -m "SuperVertex: xxxx (Patch NNNN)"
5. ./gradlew createReobfPaperclipJar ← JAR ビルド
6. Honban にデプロイ
```

---

## 11. 本番サーバー情報

| 項目 | 内容 |
|------|------|
| JAR | `Honban\supervertex.jar`（61 MB） |
| 設定 | `Honban\supervertex.yml` |
| ワールド | world / world_nether / world_the_end / anarchy / dungeon / dungeon_open / sigen / witp |
| プラグイン数 | 100 本超 |
| 対象プレイヤー数 | 12 人同時接続 |
| サーバースペック | 2.3GHz Xeon |
| MC バージョン | 1.19.4 |

---

*このドキュメントは SuperVertex プロジェクトの引き継ぎ用資料です。*
