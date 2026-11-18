/** 移动端 @tauri-apps/plugin-opener stub —— 对接 Android Intent ACTION_VIEW */

export async function openUrl(url: string): Promise<void> {
  window.AndroidBridge.openUrl(url)
}

export async function openPath(_path: string): Promise<void> {
  // Android 无桌面文件管理器概念
}
