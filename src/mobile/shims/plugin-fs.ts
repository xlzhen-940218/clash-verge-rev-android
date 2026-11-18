/** 移动端 @tauri-apps/plugin-fs stub */

export async function exists(_path: string): Promise<boolean> {
  return false
}

export async function readTextFile(_path: string): Promise<string> {
  // TODO: 通过 AndroidBridge 读取应用私有目录文件
  return ''
}

export async function writeTextFile(
  _path: string,
  _contents: string,
): Promise<void> {
  // TODO: 通过 AndroidBridge 写入
}

export async function createDir(_path: string): Promise<void> {}
export async function removeDir(_path: string): Promise<void> {}
export async function removeFile(_path: string): Promise<void> {}
export async function readDir(_path: string): Promise<any[]> {
  return []
}
