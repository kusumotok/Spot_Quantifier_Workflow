# Spot Quantifier Workflow

Fiji plugin that connects segmentation, ROI editing, and measurement in a single workflow.

## Overview

**Plugins > Spot Quantifier > Spot Quantifier Workflow** を起動すると、以下の3ステップをひとつのウィンドウで実行できる。

1. **Segmentation** — 3D seeded watershed で spot を検出し、ROI として保存
2. **ROI Edit** — ROI Explorer を内蔵したタブで ROI の確認・編集・3D watershed 再分割
3. **Measurement** — XYZ Object プロファイルで 3D 計測、CSV 出力

## Dependencies

以下の JAR が Fiji の `plugins/` に必要。

| JAR | 用途 |
|---|---|
| `ROI_Explorer_Fiji.jar` | ROI 管理・編集・計測エンジン（埋め込み） |
| `MorphoLibJ_.jar` | 3D watershed バックエンド（Fiji update site から入手） |

セグメンテーションコアはこのプロジェクトに同梱されており、別途配布不要。

## Build

```bash
# 1. ROI Explorer を local Maven repo にインストール
cd ../ROI_Explorer
mvn install -DskipTests

# 2. ビルド
cd ../SpotQuantifierWorkflow
mvn package -DskipTests

# 3. Fiji にインストール
cp target/Spot_Quantifier_Workflow.jar <Fiji.app>/plugins/
```

## Result Folder Structure

Make ROI 実行時に以下の構造で保存される。

```
<result folder>/
  rois/
    obj-001.zip   # object 単位の ROI (ZIP-fast / Folder も選択可)
    obj-002.zip
    ...
  parameters.txt  # segmentation パラメータ (key=value)
  measurement.csv # 計測結果
```

## Key Classes

| クラス | 役割 |
|---|---|
| `WorkflowWindow` | メインウィンドウ（3タブ）|
| `WorkflowController` | state machine (IDLE / SEGMENTING / SAVING_ROI / MEASURING / READY) |
| `WorkflowSession` | 現在の result folder・bound image・ROI root |
| `SegmentationTab` | セグメンテーション UI（ヒストグラム・スライダー・プレビュー）|
| `SegmentationController` | `SeededQuantifier3D` 実行・ROI 保存 |
| `RoiEditTabController` | `RoiExplorerPanel` への ROI root 受け渡し |
| `MeasurementController` | `RoiExplorerFacade.measureCurrentRoot()` 呼び出し |
| `MeasurementTab` | 計測列選択・CSV 出力設定 |
| `save/` | `ResultFolderService` / `RoiSaveService` / `ParameterFileWriter` / `ParameterFileReader` |
| `core/` | セグメンテーションコア（同梱）: `alg/` `model/` `roi/` `ui/` `util/` |

## Design Notes

**ROI Explorer の埋め込み**
`ROI_Explorer_Fiji.jar` の `RoiExplorerPanel`（JPanel）を ROI Edit タブに直接埋め込む。
`provided` scope で依存しているため、ビルド時は `mvn install` が必要。

**プレビュー**
セグメンテーション結果を disk に書かずに overlay で確認できる。
キャッシュ機構により、パラメータ変更がない限り再計算不要。
Z スライス移動で自動更新。

**保存先（Save to）**
デフォルトは bound image と同じディレクトリに自動追従。
Segmentation タブの Browse で固定可能。

**計測列の選択**
Measurement タブのチェックボックスで列を選択すると、CSV 出力だけでなく計算自体もスキップされる（無効な列は voxel loop 内の処理を省略）。
