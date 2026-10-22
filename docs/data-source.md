# 額度資料格式 v1

App 的「來源網址」應是由你控制的 HTTPS JSON 端點，回傳 HTTP 200。不是 Claude 或 OpenAI 的一般模型 API 網址。連線密碼選填，送出格式為 `Authorization: Bearer <token>`。請使用此額度來源自己發出的密碼，不要使用帳號 cookie、登入憑證或模型 API key。

```json
{
  "schemaVersion": 1,
  "demo": false,
  "generatedAt": "2026-10-20T06:00:00Z",
  "providers": {
    "claude": {
      "plan": "Team",
      "windows": [
        { "label": "5 小時", "remainingPercent": 0, "resetsAt": "2026-10-20T06:29:00Z" },
        { "label": "每週", "remainingPercent": 63, "resetsAt": "2026-10-26T12:00:00Z" }
      ]
    },
    "codex": {
      "plan": "Pro",
      "windows": [
        { "label": "每週", "remainingPercent": 41, "resetsAt": "2026-10-26T22:00:00Z" }
      ]
    }
  }
}
```

以上只是格式範例，並非真實帳號數據。測試匯入時請設定 `demo: true`；App 自帶的示範按鈕會自動產生相對於現在的重置時間。

- `generatedAt` 是來源**實際取得額度的時間**，不是轉發或手機下載時間。來源使用快取時需保留原始時間，避免把舊資料標成最新。
- 所有時間採用含時區的 ISO 8601，例如 `2026-10-20T06:00:00Z`。
- 缺少資料的服務可以省略或填 `null`。至少要有一個服務。
- 每個服務必須有 `plan`（1–32 字元）及 1–2 個 `windows`；每個時段有 `label`（1–20 字元）。
- `remainingPercent` 必須是 0–100 的數字。它表示**剩餘**；來源若提供已使用百分比，應先計算 `100 - usedPercent`。0 是有效數字，不能當作缺少資料。
- `resetsAt` 可省略或為 `null`，表示未知；過期只會顯示待更新，不推算新額度。
- 完整回應最多 64 KiB。錯誤格式、HTTP 錯誤、網路失敗都保留上次快照，並顯示更新錯誤。
- HTTPS 憑證由 Android 驗證。不允許明文 HTTP，不跟隨重新導向，避免把連線密碼傳到其他來源。
- 本機匯入使用相同格式，不會持續自動更新。切換資料來源會清除上一個來源的快照與連線設定。

## 設計決策

保留參考圖的深色雙欄、橘色／薄荷綠進度條與寬版更新按鈕。採用 Android 系統字體、可縮放文字與至少 48dp 的主要觸控區域。為了可讀性與資料來源提示，第一版建議 4 × 4 格，而非縮小所有文字。尚未取得官方品牌圖形素材，因此使用品牌文字名稱。

以 `AppWidgetProvider` + `RemoteViews` 提供真正的 Android 桌面小工具；由 `JobScheduler` 安排約 15 分鐘背景讀取，避免在廣播接收器主執行緒阻塞。0.2.0 另提供使用者主動啟動、帶停止通知的每分鐘前景同步，未使用精確鬧鐘。手機 Codex 直連走固定的官方帳號端點，與本頁自訂來源互斥，登入憑證不會傳給自訂網址。
