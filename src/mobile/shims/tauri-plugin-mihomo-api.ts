/**
 * 移动端 tauri-plugin-mihomo-api shim。
 *
 * HTTP 接口通过 AndroidBridge.invoke 走原生层（OkHttp），
 * 避免 WebView 从 https://appassets.androidplatform.net 请求
 * http://127.0.0.1:9090 时的 CORS 限制。
 * WebSocket 接口使用浏览器原生 WebSocket。
 */

// eslint-disable-next-line no-restricted-imports -- shim 层负责把插件调用转给原生桥
import { invoke } from '@tauri-apps/api/core'

// ===== 类型定义（与 tauri-plugin-mihomo-api 保持一致） =====

export interface MihomoVersion {
  version: string
  premium?: boolean
}

export interface BaseConfig {
  port?: number
  'socks-port'?: number
  'mixed-port'?: number
  'redir-port'?: number
  'tproxy-port'?: number
  mode?: string
  'log-level'?: string
  'external-controller'?: string
  secret?: string
  ipv6?: boolean
  tun?: any
  dns?: any
  [key: string]: any
}

export interface Rule {
  type: string
  payload: string
  proxy: string
}

export interface RuleProvider {
  name: string
  type: string
  ruleCount: number
  behavior: string
  updatedAt?: string
}

export interface RuleProviders {
  providers: Record<string, RuleProvider>
}

export interface Rules {
  rules: Rule[]
}

export interface Proxy {
  name: string
  type: string
  now?: string
  all?: string[]
  history?: { time: string; delay: number }[]
  alive?: boolean
  extra?: Record<string, any>
  [key: string]: any
}

export interface Proxies {
  proxies: Record<string, Proxy>
}

export interface ProxyProvider {
  name: string
  type: string
  proxies: Proxy[]
  updatedAt?: string
  vehicleType: string
}

export interface ProxyProviders {
  providers: Record<string, ProxyProvider>
}

export interface Groups {
  groups: Record<string, Proxy>
}

export interface ProxyDelay {
  delay: number
}

export interface ConnectionItem {
  id: string
  metadata: any
  upload: number
  download: number
  start: string
  chains: string[]
  rule: string
  rulePayload: string
}

export interface Connections {
  downloadTotal: number
  uploadTotal: number
  connections: ConnectionItem[]
}

export interface Traffic {
  up: number
  down: number
  upTotal: number
  downTotal: number
}

export type LogLevel =
  | 'debug'
  | 'info'
  | 'warning'
  | 'error'
  | 'silent'
  | 'unknown'

export type MessageKind<T, D> = { type: T; data: D }
export type Message = MessageKind<'Text', string>

// ===== HTTP API =====

async function http<T = any>(
  method: string,
  path: string,
  body?: any,
): Promise<T> {
  // 通过原生层 OkHttp 发起请求，绕过 WebView CORS 限制
  const result = await invoke<string>('plugin:mihomo|http', {
    method,
    path,
    body: body ?? null,
  })
  return result ? (JSON.parse(result) as T) : (null as T)
}

// ===== HTTP API =====

export async function getVersion(): Promise<MihomoVersion> {
  const v = await http<MihomoVersion>('GET', '/version')
  // CMFA 编译期写入的版本号自带前导引号，展示前去掉
  if (typeof v.version === 'string') {
    v.version = v.version.replace(/^"+|"+$/g, '')
  }
  // mihomo 内核标识：CMFA 使用的是 mihomo (meta kernel)
  ;(v as any).meta = true
  return v
}

export async function flushFakeIp(): Promise<void> {
  await http('POST', '/cache/fakeip/flush')
}

export async function flushDNS(): Promise<void> {
  await http('POST', '/cache/dns/flush')
}

export async function getConnections(): Promise<Connections> {
  return http<Connections>('GET', '/connections')
}

export async function closeAllConnections(): Promise<void> {
  await http('DELETE', '/connections')
}

export async function closeConnection(connectionId: string): Promise<void> {
  await http('DELETE', `/connections/${connectionId}`)
}

export async function getGroups(): Promise<Groups> {
  return http<Groups>('GET', '/group')
}

export async function getGroupByName(name: string): Promise<Proxy> {
  return http<Proxy>('GET', `/group/${name}`)
}

export async function delayGroup(
  name: string,
  url: string,
  timeout: number,
): Promise<Record<string, number>> {
  return http(
    'GET',
    `/group/${name}/delay?url=${encodeURIComponent(url)}&timeout=${timeout}`,
  )
}

export async function getProxyProviders(): Promise<ProxyProviders> {
  return http<ProxyProviders>('GET', '/providers/proxies')
}

export async function getProxyProviderByName(
  name: string,
): Promise<ProxyProvider> {
  return http<ProxyProvider>('GET', `/providers/proxies/${name}`)
}

export async function updateProxyProvider(name: string): Promise<void> {
  await http('PUT', `/providers/proxies/${name}`)
}

export async function healthcheckProxyProvider(name: string): Promise<void> {
  await http('GET', `/providers/proxies/${name}/healthcheck`)
}

export async function healthcheckNodeInProvider(
  provider: string,
  node: string,
): Promise<void> {
  await http('GET', `/providers/proxies/${provider}/${node}/healthcheck`)
}

export async function getProxies(): Promise<Proxies> {
  return http<Proxies>('GET', '/proxies')
}

export async function getProxyByName(name: string): Promise<Proxy> {
  return http<Proxy>('GET', `/proxies/${name}`)
}

export async function selectNodeForGroup(
  groupName: string,
  nodeName: string,
): Promise<void> {
  await http('PUT', `/proxies/${groupName}`, { name: nodeName })
}

export async function unfixedProxy(groupName: string): Promise<void> {
  await http('PUT', `/proxies/${groupName}`, { name: 'DIRECT' })
}

export async function delayProxyByName(
  name: string,
  url: string,
  timeout: number,
): Promise<ProxyDelay> {
  return http<ProxyDelay>(
    'GET',
    `/proxies/${name}/delay?url=${encodeURIComponent(url)}&timeout=${timeout}`,
  )
}

export async function getRules(): Promise<Rules> {
  return http<Rules>('GET', '/rules')
}

export async function getRuleProviders(): Promise<RuleProviders> {
  return http<RuleProviders>('GET', '/providers/rules')
}

export async function updateRuleProvider(name: string): Promise<void> {
  await http('PUT', `/providers/rules/${name}`)
}

export async function getBaseConfig(): Promise<BaseConfig> {
  return http<BaseConfig>('GET', '/configs')
}

export async function reloadConfig(
  path: string,
  force: boolean,
): Promise<void> {
  await http('PUT', `/configs?force=${force}`, { path })
}

export async function patchBaseConfig(
  payload: Partial<BaseConfig>,
): Promise<void> {
  await http('PATCH', '/configs', payload)
}

export async function updateGeo(): Promise<void> {
  await http('POST', '/configs/geo')
}

export async function restart(): Promise<void> {
  // 重启内核由原生层处理，前端 no-op
}

export async function upgradeCore(
  _channel: string,
  _force: boolean,
): Promise<void> {
  // Android 禁用
}

export async function upgradeUi(): Promise<void> {}

export async function upgradeGeo(): Promise<void> {}

export async function clearAllWsConnections(): Promise<void> {}

// ===== WebSocket API（使用浏览器原生 WebSocket） =====

// 日志由内核 JNI 推送：mihomo 的 REST /logs 在 CMFA 内核下没有数据
const logPushListeners = new Set<(arg: Message) => void>()
;(window as any).__mihomoLogPush = (json: string) => {
  const msg: Message = { type: 'Text', data: json }
  logPushListeners.forEach((l) => l(msg))
}

export class MihomoWebSocket {
  id: string
  private ws: WebSocket
  private closed = false
  private readonly listeners = new Set<(arg: Message) => void>()
  private static instances = new Set<MihomoWebSocket>()

  constructor(
    id: string,
    ws: WebSocket,
    listeners: Set<(arg: Message) => void>,
  ) {
    this.id = id
    this.ws = ws
    this.listeners = listeners
  }

  private static open(path: string): Promise<MihomoWebSocket> {
    // WebView 从 file:// 加载，固定使用 ws:// 连接本地 mihomo
    const url = `ws://127.0.0.1:9090${path}`
    return new Promise((resolve, reject) => {
      try {
        const ws = new WebSocket(url)
        const listeners = new Set<(arg: Message) => void>()
        ws.onopen = () => {
          const instance = new MihomoWebSocket(path, ws, listeners)
          MihomoWebSocket.instances.add(instance)
          resolve(instance)
        }
        ws.onmessage = (event) => {
          const msg: Message = { type: 'Text', data: event.data }
          listeners.forEach((l) => l(msg))
        }
        ws.onerror = (e) => reject(e)
      } catch (e) {
        reject(e)
      }
    })
  }

  static async connect_traffic(): Promise<MihomoWebSocket> {
    return MihomoWebSocket.open('/traffic')
  }

  static async connect_memory(): Promise<MihomoWebSocket> {
    return MihomoWebSocket.open('/memory')
  }

  static async connect_connections(): Promise<MihomoWebSocket> {
    return MihomoWebSocket.open('/connections')
  }

  static async connect_connections_count(): Promise<MihomoWebSocket> {
    return MihomoWebSocket.open('/connections')
  }

  static async connect_logs(level: LogLevel): Promise<MihomoWebSocket> {
    await invoke('plugin:mihomo|stream_logs', { enabled: true, level }).catch(
      () => {},
    )
    const instance = new MihomoWebSocket(
      '/logs',
      null as unknown as WebSocket,
      logPushListeners,
    )
    MihomoWebSocket.instances.add(instance)
    return instance
  }

  addListener(cb: (arg: Message) => void): () => void {
    this.listeners.add(cb)
    return () => this.listeners.delete(cb)
  }

  async close(): Promise<void> {
    if (this.closed) return
    this.closed = true
    this.listeners.clear()
    MihomoWebSocket.instances.delete(this)
    if (this.id.startsWith('/logs')) {
      void invoke('plugin:mihomo|stream_logs', { enabled: false }).catch(
        () => {},
      )
    }
    try {
      this.ws.close()
    } catch {}
  }

  static async cleanupAll(): Promise<void> {
    await Promise.all(
      Array.from(MihomoWebSocket.instances).map((i) => i.close()),
    )
    MihomoWebSocket.instances.clear()
  }

  static async get_all_instances() {
    return Array.from(MihomoWebSocket.instances)
  }
}
