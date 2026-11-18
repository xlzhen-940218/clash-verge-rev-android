/** 移动端 @tauri-apps/plugin-clipboard-manager stub —— 对接 Android ClipboardManager */

export async function writeText(text: string): Promise<void> {
  window.AndroidBridge.writeClipboard(text)
}

export async function readText(): Promise<string> {
  return window.AndroidBridge.readClipboard()
}
