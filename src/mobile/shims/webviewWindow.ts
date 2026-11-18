/** 移动端 @tauri-apps/api/webviewWindow stub */

type UnlistenFn = () => void

class WebviewWindowStub {
  label = 'main'
  async show() {}
  async hide() {}
  async close() {}
  async setFocus() {}
  async setBackgroundColor(_color: string) {}
  async setIgnoreCursorEvents(_ignore: boolean) {}
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
  async setTheme(_theme: string | null) {}
  async isVisible() {
    return true
  }
  async isMaximized() {
    return false
  }
  async toggleMaximize() {}
}

export function getCurrentWebviewWindow() {
  return new WebviewWindowStub()
}

export function getWebviewWindow(_label: string) {
  return new WebviewWindowStub()
}
