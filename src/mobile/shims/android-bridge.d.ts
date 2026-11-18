/**
 * AndroidBridge 由 MainActivity 通过 addJavascriptInterface 注入。
 * 各 shim 共用此声明，避免多处 declare global 互相覆盖。
 */
interface AndroidBridge {
  invoke(cmd: string, args: string, callbackId: string): void
  allocateCallbackId(once: boolean): number
  windowAction?(action: 'minimize' | 'pip' | 'close'): void
  openUrl(url: string): void
  readClipboard(): string
  writeClipboard(text: string): void
}

interface Window {
  AndroidBridge: AndroidBridge
}
