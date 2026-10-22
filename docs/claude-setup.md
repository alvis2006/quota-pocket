# Claude 額度檔案連接

目前的流程是 **Claude Code 狀態列 → 電腦額度檔 → 雲端硬碟 → 手機讀取**，不提供 Claude 手機直接登入，也不保證每分鐘取得新的額度。

## 電腦端

1. 電腦需能執行 Python，並有可提供 `rate_limits` 的 Claude Code 訂閱登入工作階段。
2. 將本專案保存在固定資料夾，確認 `tools/claude_statusline.py` 存在。
3. 在雲端同步資料夾中建立 `QuotaPocket` 資料夾。
4. 合併以下 `statusLine` 設定到自己的 `~/.claude/settings.json`，替換兩個示範路徑。不要直接覆蓋其他設定；若已使用別的狀態列工具，需自行整合。

```json
{
  "statusLine": {
    "type": "command",
    "command": "python \"C:/Projects/quota-pocket/tools/claude_statusline.py\" --plan \"Claude\" --output \"G:/My Drive/QuotaPocket/claude-quota.json\""
  }
}
```

以上路徑只是範例，雲端硬碟的名稱與位置需改成自己的實際位置。`--plan` 只是手機顯示的方案名稱，不影響帳號授權。

Claude Code 傳入有效額度後，腳本會寫出 JSON 並顯示狀態列文字。資料無效或寫入失敗時，保留原檔案。診斷結果存於本機 `~/.quota-pocket/statusline-last.txt`。

## 手機端

1. 設定 → Claude → 選擇檔案。
2. 從雲端硬碟選取上述 `claude-quota.json`，使用會持續同步的同一份檔案。
3. 到總覽查看 Claude 的資料時間；按「立即更新」可提出讀取要求。
4. 如只想讀取一次，可選「只匯入一次」。

App 保留檔案讀取權限，之後透過文件提供者重新讀取；只有新檔案的資料時間較新才更新快照。停止自動讀取後會保留最後一份資料。

## 已知的更新延遲

- 狀態列主要隨 Claude Code 事件觸發，不是獨立查詢額度的常駐服務；持續執行工作不代表每分鐘都提供新額度。
- 腳本只寫入電腦檔案，上傳由雲端硬碟程式處理。寫入成功不代表上傳完成。
- 手機文件提供者可能回傳快取；Android 背景工作也可能延後執行。
- App 目前對檔案重讀設有最少 60 秒間隔，手動更新亦可能受影響。
- 已收到「持續使用但手機小工具未持續更新」的回報，目前尚未定位所有延遲環節。

可比較：電腦診斷檔的最後成功時間、額度 JSON 的 `generatedAt`、雲端檔案內容，以及手機顯示的資料時間。這能區分未匯出、未同步與未讀取；單次同步成功不代表持續更新已解決。

不要透過單純改寫舊快照的時間來假裝取得新額度。現有匯出工具的 `generatedAt` 是收到狀態列資料後匯出的時間，不能視為 Claude 伺服器保證的量測時間。

參考：[Claude Code 狀態列官方說明](https://code.claude.com/docs/en/statusline)。
