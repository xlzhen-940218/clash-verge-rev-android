import {
  createBrowserRouter,
  createHashRouter,
  RouteObject,
} from 'react-router'

import Layout from './_layout'
import { navItems } from './_navigation'

const routes: RouteObject[] = [
  {
    path: '/',
    Component: Layout,
    children: navItems.map(
      (item) =>
        ({
          path: item.path,
          Component: item.Component,
        }) as RouteObject,
    ),
  },
]

// Android WebView loads from a file path, so browser routing cannot match '/'.
// Hash routing works regardless of the URL pathname.
export const router =
  OS_PLATFORM === 'android'
    ? createHashRouter(routes)
    : createBrowserRouter(routes)
