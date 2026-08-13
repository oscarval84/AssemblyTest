import AppBar from '@mui/material/AppBar'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Container from '@mui/material/Container'
import CssBaseline from '@mui/material/CssBaseline'
import Divider from '@mui/material/Divider'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import Toolbar from '@mui/material/Toolbar'
import Typography from '@mui/material/Typography'
import { ThemeProvider } from '@mui/material/styles'
import { useState } from 'react'
import { Link as RouterLink, Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom'
import type { Role } from '../api/client'
import { useLogout, useResetDemo, useSession } from '../api/queries'
import { Brand } from '../components/common'
import { homeFor } from '../features/auth/routing'
import { acmeTheme, type Surface } from '../theme/theme'

interface NavItem {
  label: string
  to: string
  /** Omitted means everyone on this surface. A link nobody may follow is a dead end. */
  roles?: Role[]
}

/**
 * One shell, two surfaces.
 *
 * The route groups are already separate and the design system is shared, which
 * is what makes the eventual split into two deployable applications a directory
 * move rather than a rewrite — the reason the single-app decision was safe to
 * take for v1.
 */
export function SurfaceLayout({
  surface,
  allow,
  nav,
}: {
  surface: Surface
  allow: Role[]
  nav: NavItem[]
}) {
  const session = useSession()
  const location = useLocation()

  if (session.isPending) {
    return (
      <Stack sx={{ alignItems: 'center', justifyContent: 'center', minHeight: '100dvh' }}>
        <CircularProgress size={28} />
      </Stack>
    )
  }

  if (!session.data) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  // Sent to their own surface rather than shown a refusal: a program manager
  // following a link to the portal wants their programs, not an error.
  if (!allow.includes(session.data.role)) {
    return <Navigate to={homeFor(session.data.role)} replace />
  }

  return (
    <ThemeProvider theme={acmeTheme(surface)}>
      <CssBaseline />
      <Box sx={{ minHeight: '100dvh', bgcolor: 'background.default', display: 'flex', flexDirection: 'column' }}>
        <AppBar position="sticky">
          <Toolbar sx={{ gap: 3 }}>
            <Brand surface={surface} />

            <Stack direction="row" spacing={0.5} sx={{ flexGrow: 1 }}>
              {nav
                .filter((item) => !item.roles || item.roles.includes(session.data!.role))
                .map((item) => (
                <Button
                  key={item.to}
                  component={RouterLink}
                  to={item.to}
                  size="small"
                  color={location.pathname === item.to ? 'primary' : 'inherit'}
                  sx={{ fontWeight: location.pathname === item.to ? 600 : 500 }}
                >
                  {item.label}
                </Button>
                ))}
            </Stack>

            <AccountMenu
              name={session.data.fullName}
              detail={session.data.supplierName ?? roleLabel(session.data.role)}
              isAdmin={session.data.role === 'ADMIN'}
            />
          </Toolbar>
        </AppBar>

        <Container maxWidth={surface === 'ops' ? 'xl' : 'lg'} sx={{ py: { xs: 3, md: 5 }, flexGrow: 1 }}>
          <Outlet />
        </Container>

        <Box component="footer" sx={{ borderTop: '1px solid', borderColor: 'divider', py: 2 }}>
          <Container maxWidth={surface === 'ops' ? 'xl' : 'lg'}>
            <Typography variant="caption" color="text.secondary">
              Acme Inc. · Every action on this system is recorded in an append-only audit log.
            </Typography>
          </Container>
        </Box>
      </Box>
    </ThemeProvider>
  )
}

function AccountMenu({ name, detail, isAdmin }: { name: string; detail: string; isAdmin: boolean }) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const logout = useLogout()
  const resetDemo = useResetDemo()
  const navigate = useNavigate()

  return (
    <>
      <Button color="inherit" onClick={(event) => setAnchor(event.currentTarget)} sx={{ textAlign: 'right' }}>
        <Box>
          <Typography variant="body2" sx={{ fontWeight: 600, lineHeight: 1.2 }}>
            {name}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {detail}
          </Typography>
        </Box>
      </Button>

      <Menu anchorEl={anchor} open={Boolean(anchor)} onClose={() => setAnchor(null)}>
        {isAdmin ? (
          <MenuItem
            onClick={async () => {
              setAnchor(null)
              await resetDemo.mutateAsync()
              navigate(0)
            }}
          >
            Reset the demo data
          </MenuItem>
        ) : null}
        {isAdmin ? <Divider /> : null}
        <MenuItem
          onClick={async () => {
            setAnchor(null)
            await logout.mutateAsync()
            navigate('/login', { replace: true })
          }}
        >
          Sign out
        </MenuItem>
      </Menu>
    </>
  )
}

function roleLabel(role: Role): string {
  switch (role) {
    case 'ADMIN':
      return 'Administrator'
    case 'OPS':
      return 'Supplier operations'
    case 'PROGRAM_MANAGER':
      return 'Program manager · read-only'
    default:
      return 'Supplier'
  }
}
