/**
 * 移动端 Tauri core API stub。
 *
 * 将 @tauri-apps/api/core 的 invoke/Channel 重定向到 AndroidBridge。
 * 由 vite.android.config.mts 的 alias 注入。
 */

declare global {
  interface Window {
    AndroidBridge: {
      invoke(
        cmd: string,
        args: string,
        callback: (result: string) => void,
      ): void
      allocateCallbackId(once: boolean): number
    }
  }
}

let cbSeq = 0
const callbacks = new Map<number, (response: string) => void>()

// 供原生层回调
;(window as any).__tauriCallback = (id: number, response: string) => {
  const cb = callbacks.get(id)
  if (cb) {
    cb(response)
    callbacks.delete(id)
  }
}

export function invoke<T = any>(
  cmd: string,
  args?: Record<string, any>,
): Promise<T> {
  return new Promise((resolve, reject) => {
    const id = ++cbSeq
    callbacks.set(id, (resultJson) => {
      try {
        const result = resultJson ? JSON.parse(resultJson) : null
        if (result && result.__error) {
          reject(new Error(result.detail || 'invoke error'))
        } else {
          resolve(result as T)
        }
      } catch (e) {
        reject(e)
      }
    })
    const payload = args ? JSON.stringify(args) : '{}'
    window.AndroidBridge.invoke(cmd, payload, String(id))
  })
}

/**
 * Tauri Channel 的移动端实现。
 * 原生层通过 AndroidBridge.callback(id, data) 推送数据。
 */
export class Channel<T = any> {
  private id: number
  private listeners = new Set<(data: T) => void>()

  constructor() {
    this.id = window.AndroidBridge.allocateCallbackId(false)
    callbacks.set(this.id, (response) => {
      try {
        const data = JSON.parse(response) as T
        this.listeners.forEach((l) => l(data))
      } catch {
        /* ignore */
      }
    })
  }

  onmessage = (_data: T) => {
    /* 由原生层通过 __tauriCallback 触发 */
  }

  toJSON() {
    return { __tauriChannel: this.id }
  }
}

export function convertFileSrc(filePath: string): string {
  // Android WebView 中通过 file:// 或 content:// 访问
  if (filePath.startsWith('http')) return filePath
  return `file://${filePath}`
}

export function transformCallback(
  cb: (response: any) => void,
  once = false,
): number {
  const id = ++cbSeq
  callbacks.set(id, (response) => {
    try {
      cb(JSON.parse(response))
    } catch {
      cb(response)
    }
    if (once) callbacks.delete(id)
  })
  return id
}
