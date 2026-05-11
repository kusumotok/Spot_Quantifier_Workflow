# Spot Quantifier Workflow

Fiji plugin that connects segmentation, ROI editing, and measurement in a single workflow.

## Overview

**Plugins > Spot Quantifier Workflow** を起動すると、seed 作成、seed ROI 編集、area/result ROI 作成、measurement までをひとつのウィンドウで実行できる。

1. **Seed** — seed threshold と size filter で seed ROI を作成
2. **Seed Edit** — ROI Explorer を内蔵したタブで seed ROI を確認・編集
3. **Area / Result** — 編集済み seed ROI を入力にして area threshold / watershed で result ROI を作成
4. **Measurement** — result ROI を対象に 3D 計測し、CSV 出力

## Dependencies

以下の JAR が Fiji の `plugins/` に必要。

| JAR | バージョン | 入手先 |
|---|---|---|
| `ROI_Explorer_Fiji.jar` | 0.1.0-SNAPSHOT | 下記「ROI Explorer のビルド」参照 |
| `MorphoLibJ_.jar` | 1.6.2 | Fiji > Help > Update > Manage update sites > IJPB-plugins |

セグメンテーションコアはこのプロジェクトに同梱されており、別途配布不要。
ImageJ 本体は 1.54p 以降を前提とする。

## Build

### 事前準備: ROI Explorer をローカル Maven リポジトリにインストール

```bash
git clone https://github.com/kusumotok/ROI_Explorer.git
cd ROI_Explorer
mvn install -DskipTests
```

### SpotQuantifierWorkflow のビルド

```bash
cd ../Spot_Quantifier_Workflow
mvn package -DskipTests
```

### Fiji へのインストール

```bash
# 両 JAR を Fiji の plugins/ にコピー
cp target/Spot_Quantifier_Workflow.jar  <Fiji.app>/plugins/
cp ~/.m2/repository/io/github/kusumotok/roi-explorer-fiji/0.1.0-SNAPSHOT/roi-explorer-fiji-0.1.0-SNAPSHOT.jar \
   <Fiji.app>/plugins/ROI_Explorer_Fiji.jar
```

> Windows の場合、Maven ローカルリポジトリのデフォルトパスは
> `%USERPROFILE%\.m2\repository\io\github\kusumotok\roi-explorer-fiji\0.1.0-SNAPSHOT\`

## Project Folder Structure

Seed ROI 作成時に project folder が作成され、以降の seed/result/measurement は同じ project folder に保存される。

```
<project folder>/
  seed_rois/
    obj-001.zip   # seed object 単位の ROI (ZIP-fast / ZIP-compressed / Folder を選択可)
    obj-002.zip
    ...
  result_rois/
    obj-001.zip   # result object 単位の ROI。seed と同じ object 名を維持
    obj-002.zip
    ...
  parameters.txt  # segmentation パラメータ (key=value)
  measurement.csv # 計測結果
```

`Load Project...` で既存 project folder を読み込むと、`seed_rois/` と `result_rois/` を再利用できる。
`Show in Explorer` は現在の project folder を開く。

## Workflow

### Seed

Seed タブでは bound image の channel を選び、histogram と seed threshold で seed 候補を作る。
`Apply Preview` は threshold 変更時に必要で、raw seed component と size 情報をキャッシュする。

Size filter は Apply 後の cache に対して再分類するため、Z projection の再計算なしで preview を更新できる。
`Hide preview noise <` は preview 表示だけのノイズ抑制で、出力 ROI には影響しない。

Manual edit では `Manual Include` / `Manual Exclude` で seed を手動採用・除外できる。
manual edit 後は threshold と size filter がロックされ、`Clear Manual Picks` で解除する。
`Make / Update Seed ROI` は現在の preview/cache に基づいて `seed_rois/` を保存する。

### Seed Edit

Seed Edit タブには ROI Explorer が埋め込まれており、`seed_rois/` を編集できる。
このタブでは original 3D image 側は ROI Explorer の overlay を使い、Z-proj 側には seed preview を維持する。

### Area / Result

Area / Result タブは Seed Edit で編集された `seed_rois/` を seed label image として読み込み、area threshold と 3D options で result ROI を作成する。
`Apply Preview` は area preview を表示し、`Make Result ROI` は `result_rois/` に保存する。

### Measurement

Measurement タブは `result_rois/` を対象に計測する。
`result_rois/` がない状態で `Measure` した場合は、result ROI を作成してから測定するかを確認する。

## Key Classes

| クラス | 役割 |
|---|---|
| `WorkflowWindow` | メインウィンドウ（`Seed`, `Seed Edit`, `Area / Result`, `Measurement`）|
| `WorkflowController` | state machine (IDLE / SEGMENTING / SAVING_ROI / MEASURING / READY) |
| `WorkflowSession` | 現在の project folder・bound image・seed/result ROI root |
| `SegmentationTab` | Seed / Area の UI（ヒストグラム・スライダー・プレビュー・manual seed edit）|
| `SegmentationController` | `SeededQuantifier3D` 実行・seed/result ROI 保存 |
| `MeasurementController` | `RoiExplorerFacade.measureCurrentRoot()` 呼び出し |
| `MeasurementTab` | 計測列選択・CSV 出力設定 |
| `save/` | `ResultFolderService` / `RoiSaveService` / `ParameterFileWriter` / `ParameterFileReader` |
| `core/` | セグメンテーションコア（同梱）: `alg/` `model/` `roi/` `ui/` `util/` |

## Design Notes

**ROI Explorer の埋め込み**
`ROI_Explorer_Fiji.jar` の `RoiExplorerPanel`（JPanel）を Seed Edit タブに直接埋め込む。
`provided` scope で依存しているため、ビルド時は `mvn install` が必要。

**プレビュー**
seed/result の候補を disk に書かずに overlay で確認できる。
Seed threshold 変更時は `Apply Preview` が必要。
Seed size filter と preview noise filter は cache 上の再分類なので、Z projection の再計算を避けられる。
タブ移動後も cache があれば該当 preview を再表示する。

**保存先（Save to）**
デフォルトは bound image と同じディレクトリに自動追従。
Seed タブの Browse で固定可能。
Project folder name は project folder 作成時に使われる。

**計測列の選択**
Measurement タブのチェックボックスで列を選択すると、CSV 出力だけでなく計算自体もスキップされる（無効な列は voxel loop 内の処理を省略）。
