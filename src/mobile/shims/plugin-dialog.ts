/** 移动端 @tauri-apps/plugin-dialog stub —— Android 文件选择器需原生实现 */

export interface OpenDialogOptions {
  multiple?: boolean
  filters?: any[]
  defaultPath?: string
}

export interface SaveDialogOptions {
  defaultPath?: string
}

export async function open(
  _options?: OpenDialogOptions,
): Promise<string | string[] | null> {
  // TODO: 通过 AndroidBridge 调用系统文件选择器 (SAF)
  return null
}

export async function save(
  _options?: SaveDialogOptions,
): Promise<string | null> {
  return null
}

export async function ask(_message: string, _options?: any): Promise<boolean> {
  return false
}

export async function message(_message: string, _options?: any): Promise<void> {
  // no-op on mobile
}
