/** 移动端 @tauri-apps/api/path stub */

export async function join(...paths: string[]): Promise<string> {
  return paths.filter(Boolean).join('/')
}

export async function appDataDir(): Promise<string> {
  return '/data/data/io.github.clashverge.android/files'
}

export async function appLocalDataDir(): Promise<string> {
  return '/data/data/io.github.clashverge.android/files'
}

export async function appCacheDir(): Promise<string> {
  return '/data/data/io.github.clashverge.android/cache'
}

export async function resourceDir(): Promise<string> {
  return 'file:///android_asset'
}
