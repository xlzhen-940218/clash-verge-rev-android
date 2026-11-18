/** 移动端 @tauri-apps/plugin-clipboard-manager stub —— 对接 Android ClipboardManager */

declare global {
  interface Window {
    AndroidBridge: {
      readClipboard(): string
      writeClipboard(text: string): void
    }
  }
}

export async function writeText(text: string): Promise<void> {
  window.AndroidBridge.writeClipboard(text)
}

export async function readText(): Promise<string> {
  return window.AndroidBridge.readClipboard()
}
