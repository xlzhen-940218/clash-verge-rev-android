/** 移动端 @tauri-apps/api/event stub —— 事件由原生层通过 Channel 推送 */

export type UnlistenFn = () => void

const eventListeners = new Map<string, Set<(payload: any) => void>>()

/** 前端调用 listen 订阅事件；Android 端事件通过 Channel 机制推送 */
export async function listen<T = any>(
  event: string,
  handler: (payload: { event: string; payload: T }) => void,
): Promise<UnlistenFn> {
  if (!eventListeners.has(event)) eventListeners.set(event, new Set())
  eventListeners.get(event)!.add(handler as any)
  return () => {
    eventListeners.get(event)?.delete(handler as any)
  }
}

export async function emit(_event: string, _payload?: any): Promise<void> {
  // Android 端 emit 暂时为空操作；如需双向通信可扩展 AndroidBridge
}
/** 供原生层触发事件（通过 evaluateJavascript 调用） */
;(window as any).__tauriEmitEvent = (event: string, payload: any) => {
  eventListeners.get(event)?.forEach((handler) => handler({ event, payload }))
}
