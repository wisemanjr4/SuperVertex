# SuperVertex RCT 安全実装ロードマップ

> **作成日:** 2026-02-25
> **対象バージョン:** 1.19.4
> **現状:** RCT 無効（`regionized-chunk-ticking: false`）

---

## 現状の問題分析

### なぜ現在の RCT はデッドロックするのか

`tickChunk()` を別スレッドで実行すると以下の処理でメインスレッドへのコールバックが発生する：

| 処理 | 行番号 | 問題 |
|------|--------|------|
| 雷スポーン（馬・雷エンティティ） | 902, 911 | `AsyncCatcher.catchOp("entity add")` + Bukkit イベント |
| 氷・雪形成 | 931, 948 | `CraftEventFactory.handleBlockFormEvent()` → Bukkit イベント |
| `randomTick()` 内 | 996 | ブロック実装によって隣接チャンク書き込み・Bukkit イベント |

メインスレッドが `latch.await()` でブロック中にワーカーがこれらを呼ぶ → コールバック待ちで永久停止。

### 安全な処理（変更不要）

- ✅ チャンク内ブロックデータの**読み込み**（getSections 等）
- ✅ ランダムティック位置の**選択**（乱数生成）
- ✅ スレッドローカル BlockPos / RandomSource（既に実装済み）
- ✅ Union-Find リージョン分割（隣接チャンクは同一リージョン）

---

## ロードマップ全体像

```
Phase 0 ─ 完了 ─── 専用 RCT_POOL + CountDownLatch + デフォルト false
Phase 1 ─ 分解 ─── tickChunk を「安全部分のみ」tickChunkRandomOnly() として分離
Phase 2 ─ 検証 ─── randomTick の Bukkit イベント問題を確認・対処
Phase 3 ─ 高度化 ─ Deferred Actions パターンで残り問題を解決
Phase 4 ─ 有効化 ─ テスト・チューニング → デフォルト true へ
```

---

## Phase 0 ── 完了済み

**実施内容:**
- `RCT_POOL` を `TRACKER_POOL` と分離（Patch 0013）
- `parallelStream + .get()` → `CountDownLatch` 方式に変更
- `SuperVertexConfig.regionizedChunkTicking` デフォルト `false`
- `supervertex.yml` `regionized-chunk-ticking: false`

---

## Phase 1 ── tickChunk の安全な分解 ✅ 完了（Patch 0015）

**目標:** 並列実行できる処理だけを切り出した `tickChunkRandomOnly()` を実装する。

### 変更方針

```
tickChunk() の中身を 3 セクションに分類:

[Section A] 雷/天候イベント（行 881〜913）
  → ⚠️ AsyncCatcher + Bukkit イベント → Phase 1（メインスレッド逐次）に残す

[Section B] 氷・雪形成（行 919〜956）
  → ⚠️ BlockFormEvent → Phase 1（メインスレッド逐次）に残す

[Section C] ランダムブロックティック（行 969〜1001）
  → ✅ チャンク内完結・スレッドローカル対応済み → 並列化対象
```

### 実装するもの

#### 1. `ServerLevel.java` に `tickChunkRandomOnly()` を追加

```java
// SuperVertex - RCT Phase2: ランダムブロックティックのみ（スレッドセーフ）
// Section A（雷）と Section B（氷雪）は tickChunk() 側でメインスレッド実行
public void tickChunkRandomOnly(LevelChunk chunk, int randomTickSpeed) {
    if (randomTickSpeed <= 0) return;
    ChunkPos chunkPos = chunk.getPos();
    int j = chunkPos.getMinBlockX();
    int k = chunkPos.getMinBlockZ();
    final net.minecraft.util.RandomSource _tickRandom = this.getChunkTickRandom();
    LevelChunkSection[] sections = chunk.getSections();
    int minSection = io.papermc.paper.util.WorldUtil.getMinSection(this);
    for (int sectionIndex = 0; sectionIndex < sections.length; ++sectionIndex) {
        LevelChunkSection section = sections[sectionIndex];
        if (section == null || section.tickingList.size() == 0) continue;
        if (!section.isRandomlyTicking()) continue;
        int yPos = (sectionIndex + minSection) << 4;
        for (int a = 0; a < randomTickSpeed; ++a) {
            int tickingBlocks = section.tickingList.size();
            int index = _tickRandom.nextInt(16 * 16 * 16);
            if (index >= tickingBlocks) continue;
            long raw = section.tickingList.getRaw(index);
            int randomX = j + io.papermc.paper.util.maplist.IBlockDataList.getLocationX(raw);
            int randomY = yPos + io.papermc.paper.util.maplist.IBlockDataList.getLocationY(raw);
            int randomZ = k + io.papermc.paper.util.maplist.IBlockDataList.getLocationZ(raw);
            final net.minecraft.core.BlockPos.MutableBlockPos blockpos = this.getChunkTickPos();
            net.minecraft.world.level.block.state.BlockState iblockdata =
                io.papermc.paper.util.maplist.IBlockDataList.getBlockDataFromRaw(raw);
            iblockdata.randomTick(this, blockpos.set(randomX, randomY, randomZ), _tickRandom);
        }
    }
}
```

#### 2. `ServerChunkCache.java` Phase1 に Section A・B を追加

```java
// Phase 1 ループ内（既存の rctChunksToTick.add() の前に）:
if (rctChunksToTick != null) {
    // Section A + B をメインスレッドで先行実行
    this.level.tickChunkWeatherAndEnvironment(chunk1);
    // Section C（randomTick）は Phase 2 で並列実行
    rctChunksToTick.add(chunk1);
} else {
    this.level.tickChunk(chunk1, k);
}
```

#### 3. `ServerLevel.java` に `tickChunkWeatherAndEnvironment()` を追加

```java
// Section A + B のみ（雷/氷雪） ← メインスレッドで逐次実行
public void tickChunkWeatherAndEnvironment(LevelChunk chunk) {
    // 現行 tickChunk() の行 871〜956 をそのまま移動
    // randomTick ループ（行 969〜1001）は含めない
}
```

#### 4. `RegionizedChunkTicker` / `ServerChunkCache` の Phase 2 を変更

```java
// Phase 2 で呼ぶメソッドを tickChunk → tickChunkRandomOnly に変更
dev.supervertex.RegionizedChunkTicker.tickRegionsParallel(regions,
    chunk -> this.level.tickChunkRandomOnly(chunk, randomTickSpeed)); // ← 変更点
```

### 実施後の動作フロー

```
Phase 1（メインスレッド・逐次）:
  各チャンクについて:
    1. inhabitedTime 更新
    2. NaturalSpawner（モブスポーン）
    3. tickChunkWeatherAndEnvironment()  ← 雷・氷雪
    4. rctChunksToTick.add(chunk)

Phase 2（RCT_POOL・並列）:
  各リージョンについて（並列）:
    1. tickChunkRandomOnly()  ← ランダムブロックティックのみ
```

---

## Phase 2 ── randomTick の Bukkit イベント問題の調査と対処 ✅ 完了（Patch 0016）

**目標:** `randomTick()` 内で Bukkit イベントが発火するブロックを特定し対処する。

### 静的解析結果

`SuperVertex-Server/src/main/java/net/minecraft/world/level/block/` 以下を全走査し、
`randomTick` メソッド内で `CraftEventFactory` を呼ぶブロックを特定した（16 種）。

| ブロック | randomTick 内のイベント | 対処 |
|---------|------------------------|------|
| VineBlock | `BlockSpreadEvent` | ✅ Defer |
| TurtleEggBlock | `BlockGrowEvent`, `BlockFadeEvent` | ✅ Defer |
| SweetBerryBushBlock | `BlockGrowEvent` | ✅ Defer |
| StemBlock (Melon/Pumpkin) | `BlockGrowEvent` | ✅ Defer |
| SugarCaneBlock | `BlockGrowEvent` | ✅ Defer |
| SpreadingSnowyDirtBlock (Grass/Mycelium) | `BlockSpreadEvent`, `BlockFadeEvent` | ✅ Defer |
| SnowLayerBlock | `BlockFadeEvent` | ✅ Defer |
| LeavesBlock | `LeavesDecayEvent` | ✅ Defer |
| CropBlock (Wheat/Carrot 等) | `BlockGrowEvent` | ✅ Defer |
| CocoaBlock | `BlockGrowEvent` | ✅ Defer |
| ChorusFlowerBlock | `BlockSpreadEvent`, `BlockGrowEvent` | ✅ Defer |
| CactusBlock | `BlockGrowEvent` | ✅ Defer |
| BambooStalkBlock | `BlockSpreadEvent` | ✅ Defer |
| BambooSaplingBlock | `BlockSpreadEvent` | ✅ Defer |
| RedStoneOreBlock | `BlockFadeEvent` | ✅ Defer |
| GrowingPlantHeadBlock (Kelp 等) | `BlockSpreadEvent` | ✅ Defer |
| NetherWartBlock | `BlockGrowEvent` | ✅ Defer |
| BaseFireBlock (Fire) | `BlockBurnEvent`, `BlockIgniteEvent` | ✅ Defer |

### 実装方式: RCT_DEFERRED キュー

```
RCT ワーカー:
  tickChunkRandomOnly() で isBlockSafeForRCT(block) == false なら
  RCT_DEFERRED.get().add(() -> block.randomTick(level, pos, random))

メインスレッド (latch.await() 後):
  ALL_DEFERRED の全キューをドレイン → 全 Bukkit イベントを安全に発火
```

**利点:**
- 全 Bukkit イベント（BlockGrowEvent 等）がメインスレッドで正しく発火される
- unsafe ブロックの randomTick は 1 tick 遅延するが gameplay 上は問題なし
- 並列化効率: チャンク内のほとんどのブロックは safe (石・土・砂等) のため高い並列効果を維持

---

## Phase 3 ── Deferred Actions パターン（高度化）

**目標:** Phase 2 で残った問題（エンティティスポーン・Bukkit イベント等）をメインスレッドで安全に実行する。

**適用範囲:** Phase 1・2 を実施してもまだ問題が残る場合のみ。

### RCTDeferredActions クラス

```java
public final class RCTDeferredActions {
    // ワーカースレッドが「あとでメインスレッドで実行してほしい処理」を積むキュー
    public static final ThreadLocal<java.util.ArrayDeque<Runnable>> QUEUE =
        ThreadLocal.withInitial(java.util.ArrayDeque::new);

    // 全ワーカーのキューをまとめるグローバルリスト
    public static final java.util.concurrent.ConcurrentLinkedQueue<java.util.ArrayDeque<Runnable>>
        ALL_QUEUES = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public static void defer(Runnable action) {
        if (RegionizedChunkTicker.IN_RCT_CONTEXT.get()) {
            QUEUE.get().add(action);
        } else {
            action.run(); // メインスレッドならそのまま実行
        }
    }
}
```

### ServerLevel での使用例

```java
// addFreshEntity() の前に defer ラッパー追加（例）
if (RegionizedChunkTicker.IN_RCT_CONTEXT.get()) {
    final Entity captured = entity;
    RCTDeferredActions.defer(() -> this.addFreshEntity(captured, reason));
    return true;
}
```

### tickRegionsParallel での drain

```java
// latch.await() の後に追加
for (java.util.ArrayDeque<Runnable> queue : RCTDeferredActions.ALL_QUEUES) {
    Runnable action;
    while ((action = queue.poll()) != null) {
        action.run(); // メインスレッドで安全に実行
    }
}
RCTDeferredActions.ALL_QUEUES.clear();
```

---

## Phase 4 ── テスト・チューニング・有効化

**目標:** 安定性を確認し、本番サーバーで RCT を有効化する。

### テスト手順

```
1. テストサーバー（TestServer ディレクトリ）で有効化
   regionized-chunk-ticking: true

2. 以下の状況でウォッチドッグが発火しないか確認:
   - 12人同時接続
   - 雷雨の天候
   - 農場（作物）多数
   - 火災（炎伝播）
   - エンティティ多数

3. MSPT の測定（目標: ≤ 30ms）
   - RCT 有効時 vs 無効時の比較
   - プレイヤー分散配置時（リージョン複数生成）が効果大

4. rct-serial-threshold のチューニング
   - 現在: 4（4チャンク以下は逐次）
   - 推奨: ベンチマーク後に調整（2〜8 の範囲）
```

### チューニングガイド

| 設定 | 説明 | 推奨値 |
|------|------|--------|
| `rct-serial-threshold` | この値以下は逐次処理 | 4〜8（要計測） |
| `async-thread-pool-size` | TRACKER_POOL スレッド数（0=自動） | コア数 / 2 |
| RCT_POOL スレッド数 | コード内 `cores - 1` | コア数 - 1 |

### 有効化

安定性が確認できたら：
1. `SuperVertexConfig.java` の `regionizedChunkTicking` デフォルトを `true` に戻す
2. 本番 `supervertex.yml` で `regionized-chunk-ticking: true` に設定
3. HANDOFF.md の既知の問題欄から RCT 警告を削除

---

## 進捗管理

| Phase | 内容 | 状態 |
|-------|------|------|
| Phase 0 | 専用プール・CountDownLatch・デフォルト false | ✅ 完了 |
| Phase 1 | `tickChunkRandomOnly()` / `tickChunkWeatherAndEnvironment()` 実装 | ✅ 完了（Patch 0015） |
| Phase 2 | `randomTick` Bukkit イベント調査と対処（RCT_DEFERRED キュー） | ✅ 完了（Patch 0016） |
| Phase 3 | Deferred Actions パターン（Phase 2 で解決済み・不要） | ✅ 不要 |
| Phase 4 | テスト・チューニング・本番有効化 | 🔄 テスト中 |

---

## 補足: Union-Find の安全性保証（変更なし）

現在の `buildRegions()` は正しく機能している。
隣接チャンクは必ず同一リージョンに属するため：
- Worker A が チャンク(x, z) を処理中に チャンク(x+1, z) に書き込む場合、
  (x+1, z) は必ず同じリージョン内にある → Worker A のみが処理 → データ競合なし

この安全性保証は Phase 1 以降も維持される。

---

*SuperVertex RCT 実装ロードマップ - 随時更新*
