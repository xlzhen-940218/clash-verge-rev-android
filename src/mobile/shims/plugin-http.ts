/**
 * 移动端 @tauri-apps/plugin-http shim。
 *
 * WebView 直接 fetch 外部地址会被 CORS 拦截，这里统一走原生 OkHttp，
 * 再包装成标准 Response，保持与桌面端插件一致的调用方式。
 */

// eslint-disable-next-line no-restricted-imports -- shim 层负责把插件调用转给原生桥
import { invoke } from '@tauri-apps/api/core'

interface NativeHttpResponse {
  status: number
  ok: boolean
  body: string
}

export async function fetch(url: string, options?: any): Promise<Response> {
  const headers: Record<string, string> = {}
  const rawHeaders = options?.headers
  if (rawHeaders) {
    if (
      typeof rawHeaders.forEach === 'function' &&
      !Array.isArray(rawHeaders)
    ) {
      rawHeaders.forEach((value: string, key: string) => {
        headers[key] = String(value)
      })
    } else {
      Object.entries(rawHeaders).forEach(([key, value]) => {
        if (value != null) headers[key] = String(value)
      })
    }
  }

  const result = await invoke<NativeHttpResponse>('http_fetch', {
    url,
    method: options?.method || 'GET',
    headers,
    body:
      typeof options?.body === 'string'
        ? options.body
        : options?.body
          ? JSON.stringify(options.body)
          : null,
  })

  return new Response(result?.body ?? '', {
    status: result?.status ?? 500,
    statusText: result?.ok ? 'OK' : 'Error',
  })
}
