# NIKKE 推圖助手 v0.1

用無障礙服務（AccessibilityService）自動推《勝利女神：妮姬》主線章節。

- 截圖：`AccessibilityService.takeScreenshot()`（需 Android 11 以上）
- 點擊／滑動／雙指縮放：`dispatchGesture()`
- 文字辨識：ML Kit 中文 OCR（SKIP、點擊任意處、進入戰鬥、快速戰鬥、OPERATION FAILED）
- 地圖辨識：顏色＋形狀（青藍菱形驚嘆號、紅色橢圓光圈）
- 介面：Jetpack Compose Material 3（支援動態配色、深色模式）

## 用 GitHub 編譯 APK

1. 在 GitHub 建立新的 repository，把這個資料夾全部上傳。
2. 推送後 Actions 會自動執行「Build APK」（也可以在 Actions 頁手動按 Run workflow）。
3. 完成後到該次執行頁面最下方 Artifacts，下載 `NikkeAuto-debug-apk`，解壓後安裝 APK。

## 使用方式

1. 開啟 App → 「前往設定」→ 無障礙 → 開啟「NIKKE 推圖助手」。
2. 允許通知（戰鬥失敗時通知用）。
3. 進遊戲，畫面左側會出現可拖曳的懸浮按鈕，到章節大地圖後點「▶ 開始」。
4. 懸浮按鈕下方會顯示目前判斷的畫面和動作，方便除錯。

## 判斷流程

| 畫面 | 判斷依據 | 動作 |
|---|---|---|
| 戰鬥失敗 | OCR：FAILED／重新挑戰 | 停止腳本＋通知（含關卡編號） |
| 劇情 | OCR：右上角 SKIP | 點 SKIP |
| 結算／解鎖 | OCR：點擊任意處／點擊繼續 | 點畫面下方中央 |
| 出擊準備 | OCR：進入戰鬥 | 快速戰鬥是橘色就點它，否則點進入戰鬥 |
| 大地圖 | OCR：CURRENT AREA／野外 | 見下方 |
| 其他 | — | 視為戰鬥中，等待 |

大地圖：

1. 找青藍色驚嘆號（主線）。下方有紅圈怪 → 點怪；沒有 → 點驚嘆號跳視角。
2. 隊伍到目標的路線上有紅圈怪 → 先打它。
3. 連續 3 次點同一個目標都沒動 → 視為被擋，先打最近的紅圈怪。
4. 橘色驚嘆號（支線）一律忽略。
5. 都找不到 → 縮小視角一次，再依序上下左右滑動探路。

## 程式結構

```
app/src/main/java/com/nikkeauto/
  MainActivity.kt   設定頁（Material 3）
  AutoService.kt    無障礙服務：截圖、手勢、懸浮窗、通知
  Bot.kt            狀態機與決策邏輯
  Screen.kt         OCR 與畫面分類
  Vision.kt         地圖顏色辨識（驚嘆號、紅圈、擋路判斷）
  Prefs.kt          開關與參數
```

## 已知限制（第一版）

- 顏色門檻是用 1080×2412 的截圖校正的，不同章節的光線（雪地、夜景）可能要微調 `Vision.kt` 的 `classify()`。
- 「我方隊伍位置」暫時假設在畫面正中央。
- 戰鬥中依賴遊戲內建的自動戰鬥。
- 懸浮按鈕所在位置的點擊可能會被按鈕擋住，必要時把它拖到角落。

⚠️ 自動化腳本可能違反遊戲服務條款，有封號風險，建議先用小號測試。
