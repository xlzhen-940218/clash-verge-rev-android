import { BottomNavigation, BottomNavigationAction } from '@mui/material'
import { useTranslation } from 'react-i18next'
import { useLocation, useNavigate } from 'react-router'

import { navItems } from '@/pages/_navigation'

/**
 * 移动端底部导航栏：将侧边栏的主要导航项转换为底部 Tab。
 */
export const LayoutMobileNav = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const location = useLocation()

  const items = navItems
  const currentIndex = Math.max(
    0,
    items.findIndex((item) => location.pathname === item.path),
  )

  return (
    <BottomNavigation
      value={currentIndex}
      onChange={(_, newValue) => {
        navigate(items[newValue].path)
      }}
      showLabels
      sx={(theme) => ({
        position: 'fixed',
        bottom: 0,
        left: 0,
        right: 0,
        zIndex: 1000,
        borderTop: `1px solid ${theme.palette.divider}`,
        bgcolor: theme.palette.background.paper,
        height: 56,
        '& .MuiBottomNavigationAction-root': {
          minWidth: 0,
          px: 0,
        },
        '& .MuiBottomNavigationAction-label': {
          fontSize: 10,
          whiteSpace: 'nowrap',
        },
      })}
    >
      {items.map((item) => (
        <BottomNavigationAction
          key={item.path}
          label={t(item.label)}
          icon={item.icon[0]}
          sx={{ '& .MuiSvgIcon-root': { fontSize: 20 } }}
        />
      ))}
    </BottomNavigation>
  )
}
