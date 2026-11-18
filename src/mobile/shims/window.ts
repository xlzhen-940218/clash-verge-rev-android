/** 移动端 @tauri-apps/api/window stub —— Android 无桌面窗口概念 */

type UnlistenFn = () => void

type AndroidWindowBridge = {
  windowAction?: (action: 'minimize' | 'pip' | 'close') => void
}

/** 把标题栏按钮转交给原生：最小化回桌面 / 画中画 / 退出（VPN 保持运行） */
function callWindowAction(action: 'minimize' | 'pip' | 'close') {
  const bridge = (globalThis as { AndroidBridge?: AndroidWindowBridge })
    .AndroidBridge
  bridge?.windowAction?.(action)
}

class WindowStub {
  label = 'main'
  async show() {}
  async hide() {}
  async close() {
    callWindowAction('close')
  }
  async minimize() {
    callWindowAction('minimize')
  }
  async maximize() {
    callWindowAction('pip')
  }
  async unmaximize() {}
  async setFocus() {}
  async setTitle(_title: string) {}
  async setDecorations(_decorations: boolean) {}
  async setResizable(_resizable: boolean) {}
  async setSize(_size: any) {}
  async setPosition(_position: any) {}
  async center() {}
  async isMaximized() {
    return false
  }
  async isDecorated() {
    return false
  }
  async setDecorations(_decorations: boolean) {}
  async onResized(
    _handler: (event: { payload: { width: number; height: number } }) => void,
  ): Promise<UnlistenFn> {
    return () => {}
  }
  async onThemeChanged(): Promise<UnlistenFn> {
    return () => {}
  }
  async onFocusChanged(): Promise<UnlistenFn> {
    return () => {}
  }
  async onCloseRequested(): Promise<UnlistenFn> {
    return () => {}
  }
  async onDragDropEvent(_handler: (event: any) => void): Promise<UnlistenFn> {
    return () => {}
  }
  async isVisible() {
    return true
  }
  async setMinimizable(_minimizable: boolean) {}
  async theme() {
    return 'dark'
  }
}

export function getCurrentWindow() {
  return new WindowStub()
}

export function getWindow(_label: string) {
  return new WindowStub()
}

export enum Theme {
  Light = 'light',
  Dark = 'dark',
}
