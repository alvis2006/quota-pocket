# 手機獨立同步實作與驗證範圍

0.2.0 新增 Codex 裝置授權相容流程。手機自行完成登入、保存並續期授權，再讀取額度；不依賴桌面電腦、LAN、公開中繼或自訂伺服器。

## 使用者流程

App 產生代碼 → 系統瀏覽器開啟 `https://auth.openai.com/codex/device` → 使用者登入、輸入代碼並確認 → 回到 App 按完成授權 → 手機直接讀取額度 → 使用者選擇是否開啟每分鐘更新。

這是非官方個人工具的實驗性相容功能，使用 Codex 開源程式中的公開 client ID，而非已為 Quota Pocket 註冊的 OAuth client。畫面明確說明官方同意頁可能顯示 Codex。沒有模擬密碼輸入、擷取瀏覽器 Cookie、讀取電腦憑證或傳送模型請求。

Claude 訂閱的手機直接授權尚未接通；目前由電腦的 Claude Code 匯出額度檔，再供手機透過雲端硬碟讀取。同步有延遲限制，詳見 [Claude 檔案連接](claude-setup.md)。

## 固定網路邊界

帳號連線只允許以下完整 URL，禁止 HTTP 和重新導向：

- `https://auth.openai.com/api/accounts/deviceauth/usercode`
- `https://auth.openai.com/api/accounts/deviceauth/token`
- `https://auth.openai.com/oauth/token`
- `https://chatgpt.com/backend-api/wham/usage`

裝置代碼流程、Bearer token、refresh token 均加密後放入 App 私有儲存。禁止系統備份。登入頁防止螢幕擷取，剪貼簿代碼標記為敏感內容。不記錄或顯示 token／伺服器錯誤回應內容。自訂網址模式不共用帳號 token，切換模式會清除先前憑證。

access token 的 JWT 解碼只讀取帳號路由與過期時間提示；不把未驗證 claims 當成授權。token 只接受固定 TLS 端點的回應，不接受使用者匯入，真正存取權由 OpenAI 驗證。refresh 會保存輪替的新 token，拒絕帳號意外變更；手動清除／切換來源後，舊請求不會復原已清除的資料。

## 更新排程

- 手動更新：透過工作排程觸發新讀取。
- App 顯示中：約每 60 秒提出更新。
- 一般背景：約 15 分鐘週期工作，允許系統延後，重開機後由 JobScheduler 保留排程。
- 每分鐘模式：使用者在 App 主動啟動 `dataSync` 前景服務。每次請求完成後等待 60 秒，顯示停止通知；採 `START_NOT_STICKY`，不在重開機後擅自重啟。
- 每分鐘模式主動於約 5 小時 55 分鐘停止；同時處理 Android 15 `onTimeout` 和啟動遭拒。停止後保留一般背景更新。
- 全 App 共用單一請求互斥，防止工作排程、畫面與前景服務重複續期。
- 收到 429 尊重秒數格式的 Retry-After（60–3600 秒）；其他格式至少等 60 秒。不在同一請求裡不停重試。
- 更新失敗保留上次成功的資料；Codex 直連快照超過 3 分鐘會標示待更新，數字旁顯示資料時間。

不是秒級推送；來源額度本身的計算延遲、Android 休眠、省電、系統時限和網路都影響實際更新時間。

## 已驗證與尚待驗證

- APK 編譯、Android 資源／DEX 打包、v2／v3 簽章驗證通過。
- 裝置授權初始化曾實際回傳 HTTP 200、device_auth_id、user_code 和 5 秒輪詢間隔。測試代碼未授權、未顯示給使用者或寫入檔案。
- 測試包含：已用轉剩餘、0%／100%、不同時段、缺失重置時間、無額度欄位、異常數據、token 輪替、缺少續期授權、過期 token、Header 注入與續期帳號改變。
- 未在 Android 裝置或模擬器跑完整授權、續期、Doze／斷網、通知停止、Android 15 時限、字體縮放等測試。需要真機驗收，不能以編譯通過代替。

## 來源

2026-10-20 核對的官方資料與程式：

- [Codex 帳號驗證／裝置代碼登入](https://learn.chatgpt.com/docs/auth)
- [OpenAI Codex 裝置代碼原始碼](https://github.com/openai/codex/blob/main/codex-rs/login/src/device_code_auth.rs)
- [OpenAI Codex 用量請求原始碼](https://github.com/openai/codex/blob/main/codex-rs/backend-client/src/client/rate_limit_resets.rs)
- [OpenAI Codex 登入與續期原始碼](https://github.com/openai/codex/blob/main/codex-rs/login/src/auth/manager.rs)
- [Android 前景服務時限](https://developer.android.com/develop/background-work/services/fgs/timeout)
- [Android 工作排程週期](https://developer.android.com/reference/android/app/job/JobInfo.Builder#setPeriodic(long))
