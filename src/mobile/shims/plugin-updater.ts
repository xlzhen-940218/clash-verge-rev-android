/** 移动端 @tauri-apps/plugin-updater stub —— Android 禁用应用内自更新 */

export interface CheckOptions {
  headers?: Record<string, string>
  timeout?: number
  proxy?: string
}

export interface Update {
  version: string
  date?: string
  body?: string
  close(): Promise<void>
  downloadAndInstall(): Promise<void>
}

export async function check(_options?: CheckOptions): Promise<Update | null> {
  return null
}

export async function installUpdate(): Promise<void> {
  // no-op
}
