# SuperVertex

**SuperVertex** は [PlazmaMC](https://github.com/PlazmaMC/PlazmaBukkit) をベースとした高性能 Minecraft 1.19.4 サーバーソフトウェアです。
低クロック・多コア CPU での大人数・重量級プラグイン環境を想定し、メインスレッドの負荷分散と GC 削減を中心とした独自最適化を積んでいます。

```
Vanilla → CraftBukkit → Spigot → Paper → Purpur → Plazma → SuperVertex
```

---

## 特徴

### Regionized Chunk Ticking (RCT)
プレイヤーが離れた位置にいる場合、それぞれのチャンク領域（リージョン）を複数スレッドで並列 tick します。

- **Union-Find** によるリージョン分割（隣接チャンクを同一リージョンにまとめることでスレッド間の競合を排除）
- **Block Safety Cache** — unsafe ブロックの判定を `instanceof × 22` → `配列アクセス × 1` に削減
- **Deferred キュー** — Bukkit イベントを発火するブロック（作物・火・葉等）はメインスレッドで安全に実行
- **Phase 1 / Phase 2 分離** — 雷・氷雪形成（Bukkit イベント含む）はメインスレッドで先行実行し、randomTick のみ並列化
- **リージョンキャッシュ** — XOR フィンガープリントでチャンクセットの変化を O(N) で検出し、プレイヤー静止中は Union-Find 再計算をスキップ
- **動的スケジューリング** — `AtomicInteger` カウンタでワーカーが空き次第次のリージョンを取得（小リージョン担当スレッドのアイドルを排除）。メインスレッドも余剰リージョンを横取りするワークスティーリングに参加
- 小リージョン・単一リージョン時は自動的にシリアル実行に切り替えてオーバーヘッドを排除

### Async Entity Tracker
エンティティ追跡パケットの生成（`sendChanges`）を専用 ForkJoinPool で並列実行します。
`updatePlayers`（AsyncCatcher 要件）はメインスレッドを維持しつつ、Netty スレッドセーフな部分のみを並列化。

### Dynamic Random Tick Speed
プレイヤーから遠いチャンクの `randomTickSpeed` を動的に削減します。
広い view-distance 環境でのメインスレッド負荷削減に有効です（デフォルト無効）。

- チャンク距離の計算結果はプレイヤー位置フィンガープリントでキャッシュ — プレイヤー静止中は全チャンク分の距離計算を完全スキップ
- RCT 使用時はワーカー起動前にメインスレッドが一括プリコンピュート（ワーカーはキャッシュ参照のみ）

### その他の最適化

| 最適化 | 内容 |
|---|---|
| Async Player Save | NBT スナップショットをメインスレッドで生成後、ディスク書き込みのみ非同期化 |
| Mob Spawn Throttle | スポーン計算を複数 tick に分散（`createState` + `spawnForChunk` を両方スキップ） |
| Chunk Tick Spread | チャンク tick を複数 tick に分散してラグスパイクを平滑化 |
| Skip Empty World Tick | プレイヤー不在ワールドの chunk tick を完全スキップ |
| Per-Tick Permission Cache | 同一 tick 内のパーミッション判定をキャッシュして LuckPerms 負荷を削減 |
| Entity Tracker Buffer Reuse | `processTrackQueue` の `ArrayList` を再利用バッファ化して GC 削減 |
| Entity Deep Sleep | 長時間非アクティブエンティティの `inactiveTick()` 自体を 4 tick に 1 回に削減。EAR/DAB がゴールセレクターを間引くのに対し、fire tick・移動・属性更新等のエントリコストも削減 |
| RCT Region Cache | XOR フィンガープリントでチャンクセット変化を検出。プレイヤー静止中は Union-Find 再計算を完全スキップ |
| RCT Dynamic Scheduling | AtomicInteger カウンタによる動的リージョン割り当て + メインスレッドによるワークスティーリング |
| Dynamic Speed Cache | `computeAdjustedRandomTickSpeed` 結果をプレイヤー位置ハッシュでキャッシュ。静止中は全チャンクの距離計算をスキップ |
| Attribute Map CME Fix | `dirtyAttributes` を `ConcurrentHashMap.KeySet` に変更し、非同期プラグイン起因の CME を防止 |
| broadcastChanges Null Guard | RCT 環境下での non-full chunk への NPE をガード |

---

## 設定 (supervertex.yml)

```yaml
performance:
  # 非同期スレッドプールのスレッド数 (0 = CPU コア数を自動検出)
  async-thread-pool-size: 0

  # エンティティ追跡の sendChanges を並列実行するか
  async-entity-tracker: true

  # モブスポーン処理を何 tick に 1 回実行するか (1 = 毎 tick)
  # 値を大きくするとスポーン遅延が増えるが、メインスレッド負荷が減る
  mob-spawn-throttle-ticks: 1

  # チャンク tick を何 tick に分散させるか (1 = バニラ動作)
  chunk-tick-spread-factor: 1

  # Regionized Chunk Ticking を有効にするか
  # 低クロック多コア CPU + 多人数サーバーで効果的
  regionized-chunk-ticking: false

  # RCT: Union-Find をスキップしてシリアル実行する最大チャンク数
  rct-serial-threshold: 4

  # RCT: ワーカーに割り当てる最小リージョンサイズ (チャンク数)
  rct-min-region-size: 4

  # プレイヤーデータ保存を非同期化するか
  async-player-save: true

  # プレイヤー不在ワールドのチャンク tick をスキップするか
  # ネザー・エンドが空の場合に大きな効果がある
  skip-empty-world-chunk-tick: true

  # パーミッション判定結果を 1 tick キャッシュするか
  permission-cache: true

  # プレイヤーから遠いチャンクの randomTickSpeed を削減するか
  dynamic-random-tick-speed: false

  # dynamic-random-tick-speed: フルスピードを維持するプレイヤーからの半径 (チャンク数)
  dynamic-random-tick-speed-near-chunks: 5

  # dynamic-random-tick-speed: 遠方チャンクに適用する速度の最小係数 (0.0〜1.0)
  dynamic-random-tick-speed-min-factor: 0.25

  # 長時間非アクティブなエンティティの inactiveTick() 自体をスロットルするか
  # Spigot EAR / Pufferfish DAB に追加で機能し、RPG サーバーで特に効果的
  entity-deep-sleep: true

  # Deep Sleep に入るまでの連続非アクティブ tick 数 (デフォルト: 100 = 5秒)
  entity-deep-sleep-min-inactive-ticks: 100

  # Deep Sleep 中のエンティティが inactiveTick() を呼ぶ間隔 (デフォルト: 4 tick に 1 回)
  entity-deep-sleep-tick-rate: 4
```

---

## ビルド方法

**必要環境:** JDK 17+, Git

```bash
# 1. リポジトリをクローン
git clone https://github.com/wisemanjr4/SuperVertex.git
cd SuperVertex

# 2. パッチを適用してソースを展開
./gradlew applyPatches

# 3. JAR をビルド (Paperclip 形式)
./gradlew createReobfPaperclipJar
# 出力先: build/libs/SuperVertex-paperclip-1.19.4-R0.1-SNAPSHOT-reobf.jar
```

### パッチの開発ワークフロー

```bash
# ソースを編集後、内側の git にコミット
cd SuperVertex-Server
git add <files>
git commit -m "SuperVertex: your change description"
cd ..

# パッチファイルに変換して外側の git にコミット
./gradlew rebuildServerPatches
git add patches/
git commit -m "Add patch XXXX: your change description"
git push origin main
```

---

## パッチ一覧

| No. | 内容 |
|---|---|
| 0001 | SuperVertex へのリブランド |
| 0002 | 設定システム (supervertex.yml) の追加 |
| 0003 | Async Entity Tracker |
| 0004 | Mob Spawn Throttle |
| 0005 | Chunk Tick Spread Factor |
| 0006 | ビルド設定修正 |
| 0007 | Regionized Chunk Ticking (RCT) 基盤 |
| 0008 | Async Player Save + プラグイン互換性修正 |
| 0009 | getBukkitVersion 修正 |
| 0010 | Skip Empty World Chunk Tick |
| 0011 | Per-Tick Permission Cache |
| 0012 | `/sv` コマンド追加 |
| 0013 | RCT デッドロック修正・専用スレッドプール分離 |
| 0014 | RCT デフォルト無効化 |
| 0015 | RCT Phase 1: 天候・氷雪をメインスレッドに分離 |
| 0016 | RCT Phase 2: unsafe ブロックの Deferred キュー実装 |
| 0017 | Permission Cache の CME 修正 |
| 0018 | バージョン文字列修正 (MythicMobs 互換) |
| 0019 | AttributeMap CME 修正 (ConcurrentHashMap.KeySet) |
| 0020 | broadcastChanges NPE 修正 (non-full chunk guard) |
| 0021 | RCT: 未対応 unsafe ブロック追加・Block Safety Cache 最適化 |
| 0022 | RCT: リージョン負荷分散・per-tick アロケーション削減 |
| 0023 | Dynamic Random Tick Speed・Entity Tracker バッファ再利用・Mob Spawn Throttle 完全化 |
| 0024 | Entity Deep Sleep（長時間非アクティブエンティティの `inactiveTick()` スロットル） |
| 0025 | RCT リージョンキャッシュ（XOR フィンガープリントで Union-Find 再計算をスキップ） |
| 0026 | RCT 動的スケジューリング（AtomicInteger カウンタ + メインスレッドワークスティーリング） |
| 0027 | Dynamic Random Tick Speed キャッシュ（プレイヤー静止中の距離計算を完全スキップ） |

---

## アップストリーム

| プロジェクト | リポジトリ |
|---|---|
| PlazmaMC | https://github.com/PlazmaMC/PlazmaBukkit |
| Purpur | https://github.com/PurpurMC/Purpur |
| Paper | https://github.com/PaperMC/Paper |
| Pufferfish | https://github.com/pufferfish-gg/Pufferfish |
| Spigot | https://hub.spigotmc.org/stash/projects/SPIGOT |

---

## ライセンス

[LICENSE](LICENSE) を参照してください。
アップストリームプロジェクトのライセンス条項に従います。
