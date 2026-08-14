import CircularProgress from '@mui/material/CircularProgress'
import Stack from '@mui/material/Stack'
import { ThemeProvider } from '@mui/material/styles'
import CssBaseline from '@mui/material/CssBaseline'
import { Suspense, lazy } from 'react'
import { Navigate, Outlet, Route, Routes } from 'react-router-dom'
import { useSession } from './api/queries'
import { SurfaceLayout } from './app/Shell'
import AcceptInvitationPage from './features/auth/AcceptInvitationPage'
import LoginPage from './features/auth/LoginPage'
import ResetPasswordPage from './features/auth/ResetPasswordPage'
import { homeFor } from './features/auth/routing'
import { acmeTheme } from './theme/theme'

/**
 * Two route groups over one build.
 *
 * The ops console is loaded lazily so a supplier's browser never downloads it —
 * the concrete cost of sharing one application, paid for at the route boundary
 * rather than argued about.
 */
const PortalHome = lazy(() => import('./features/supplier/PortalHome'))
const PortalProfile = lazy(() => import('./features/supplier/PortalProfile'))
const OpsSuppliers = lazy(() => import('./features/ops/SuppliersPage'))
const OpsSupplierDetail = lazy(() => import('./features/ops/SupplierDetailPage'))
const OpsReviewQueue = lazy(() => import('./features/ops/ReviewQueuePage'))
const OpsOutbox = lazy(() => import('./features/ops/OutboxPage'))
const OpsStaffAdmin = lazy(() => import('./features/ops/StaffAdminPage'))
const OpsExpirations = lazy(() => import('./features/ops/ExpirationsPage'))
const OpsIntegrations = lazy(() => import('./features/ops/IntegrationsPage'))
const OpsAuditExport = lazy(() => import('./features/ops/AuditExportPage'))

export default function App() {
  return (
    <Suspense fallback={<FullPageSpinner />}>
      <Routes>
        {/* Signed out, and still Acme's brand surface — these pages are the
            first thing an invited supplier ever sees of the company. */}
        <Route element={<PublicSurface />}>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/invitation/:token" element={<AcceptInvitationPage />} />
          <Route path="/reset-password/:token" element={<ResetPasswordPage />} />
        </Route>

        <Route
          element={
            <SurfaceLayout
              surface="supplier"
              allow={['SUPPLIER_USER']}
              nav={[
                { label: 'Onboarding', to: '/portal' },
                { label: 'Company profile', to: '/portal/profile' },
              ]}
            />
          }
        >
          <Route path="/portal" element={<PortalHome />} />
          {/* The address in our emails; kept as an alias so those links never rot. */}
          <Route path="/portal/documents" element={<PortalHome />} />
          <Route path="/portal/profile" element={<PortalProfile />} />
        </Route>

        <Route
          element={
            <SurfaceLayout
              surface="ops"
              allow={['ADMIN', 'OPS', 'PROGRAM_MANAGER']}
              nav={[
                { label: 'Pipeline', to: '/ops' },
                { label: 'Review queue', to: '/ops/review', roles: ['OPS', 'ADMIN'] },
                { label: 'Expiring', to: '/ops/expirations', roles: ['OPS', 'ADMIN'] },
                { label: 'Notifications', to: '/ops/outbox', roles: ['OPS', 'ADMIN'] },
                { label: 'VMS', to: '/ops/integrations', roles: ['OPS', 'ADMIN'] },
                // No role restriction: a program manager exports their own
                // programs, which is the read-only visibility they are for.
                { label: 'Audit export', to: '/ops/audit' },
                { label: 'Staff access', to: '/ops/admin/users', roles: ['ADMIN'] },
              ]}
            />
          }
        >
          <Route path="/ops" element={<OpsSuppliers />} />
          <Route path="/ops/review" element={<OpsReviewQueue />} />
          <Route path="/ops/expirations" element={<OpsExpirations />} />
          <Route path="/ops/outbox" element={<OpsOutbox />} />
          <Route path="/ops/integrations" element={<OpsIntegrations />} />
          <Route path="/ops/audit" element={<OpsAuditExport />} />
          <Route path="/ops/admin/users" element={<OpsStaffAdmin />} />
          <Route path="/ops/suppliers/:id" element={<OpsSupplierDetail />} />
        </Route>

        <Route path="*" element={<LandingRedirect />} />
      </Routes>
    </Suspense>
  )
}

function PublicSurface() {
  return (
    <ThemeProvider theme={acmeTheme('supplier')}>
      <CssBaseline />
      <Outlet />
    </ThemeProvider>
  )
}

/** Sends everyone to the surface they belong on, including from a stale URL. */
function LandingRedirect() {
  const session = useSession()
  if (session.isPending) return <FullPageSpinner />
  return <Navigate to={session.data ? homeFor(session.data.role) : '/login'} replace />
}

function FullPageSpinner() {
  return (
    <ThemeProvider theme={acmeTheme('supplier')}>
      <CssBaseline />
      <Stack sx={{ alignItems: 'center', justifyContent: 'center', minHeight: '100dvh' }}>
        <CircularProgress size={28} />
      </Stack>
    </ThemeProvider>
  )
}
