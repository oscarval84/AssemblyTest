import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Divider from '@mui/material/Divider'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { RequestFailed } from '../../api/client'
import { useDemoAccounts, useLogin, useRequestPasswordReset } from '../../api/queries'
import { Brand } from '../../components/common'
import { homeFor } from './routing'

/**
 * The sign-in screen, and the first thing anyone sees of Acme.
 *
 * The demo panel is only rendered when the backend says demo mode is on, and it
 * exists for a specific reason: an evaluator who cannot get through the door
 * cannot judge anything behind it.
 */
export default function LoginPage() {
  const navigate = useNavigate()
  const login = useLogin()
  const demo = useDemoAccounts()
  const resetRequest = useRequestPasswordReset()

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [mode, setMode] = useState<'sign-in' | 'reset'>('sign-in')

  const failure = login.error instanceof RequestFailed ? login.error.message : null

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (mode === 'reset') {
      await resetRequest.mutateAsync({ email })
      return
    }
    const session = await login.mutateAsync({ email, password })
    navigate(homeFor(session.role), { replace: true })
  }

  return (
    <Box
      sx={{
        minHeight: '100dvh',
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', md: '1.1fr 1fr' },
        bgcolor: 'background.default',
      }}
    >
      <Stack sx={{ justifyContent: 'center', px: { xs: 3, md: 8 }, py: 6 }}>
        <Box sx={{ maxWidth: 440, width: '100%', mx: 'auto' }}>
          <Brand surface="supplier" />

          <Typography variant="h1" sx={{ mt: 5 }}>
            {mode === 'sign-in' ? 'Sign in' : 'Reset your password'}
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1, mb: 4 }}>
            {mode === 'sign-in'
              ? 'Suppliers and Acme staff sign in here. Your view depends on your role.'
              : 'Enter the email address you use with Acme and we will send you a link.'}
          </Typography>

          <form onSubmit={submit}>
            <Stack spacing={2.5}>
              {failure ? <Alert severity="error">{failure}</Alert> : null}

              {resetRequest.isSuccess ? (
                <Alert severity="success">
                  If that address has an account, a reset link is on its way. The link is good for
                  one hour.
                </Alert>
              ) : null}

              <TextField
                label="Email address"
                type="email"
                autoComplete="username"
                required
                fullWidth
                value={email}
                onChange={(event) => setEmail(event.target.value)}
              />

              {mode === 'sign-in' ? (
                <TextField
                  label="Password"
                  type="password"
                  autoComplete="current-password"
                  required
                  fullWidth
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                />
              ) : null}

              <Button
                type="submit"
                variant="contained"
                size="large"
                disabled={login.isPending || resetRequest.isPending}
              >
                {mode === 'sign-in' ? 'Sign in' : 'Send the reset link'}
              </Button>

              <Link
                component="button"
                type="button"
                variant="body2"
                onClick={() => setMode(mode === 'sign-in' ? 'reset' : 'sign-in')}
                sx={{ alignSelf: 'flex-start' }}
              >
                {mode === 'sign-in' ? 'I have forgotten my password' : 'Back to sign in'}
              </Link>
            </Stack>
          </form>
        </Box>
      </Stack>

      <Box
        sx={{
          display: { xs: 'none', md: 'block' },
          bgcolor: 'primary.dark',
          color: 'common.white',
          px: 8,
          py: 10,
        }}
      >
        <Typography variant="h2" sx={{ color: 'inherit', maxWidth: '22ch' }}>
          Onboarding that both sides can see.
        </Typography>
        <Typography sx={{ mt: 2, opacity: 0.82, maxWidth: '44ch' }}>
          Every document, its status and the reason behind it — the same truth for Acme and for the
          supplier, with a record of who did what.
        </Typography>

        {demo.data ? (
          <Card
            sx={{
              mt: 6,
              bgcolor: 'rgba(255,255,255,0.06)',
              borderColor: 'rgba(255,255,255,0.22)',
              // The card sits on the dark panel, so it inherits nothing useful
              // from the theme's light surfaces.
              color: 'common.white',
            }}
          >
            <CardContent>
              <Typography variant="overline" sx={{ color: 'rgba(255,255,255,0.72)' }}>
                Demonstration accounts
              </Typography>
              <Typography variant="body2" sx={{ mb: 2, opacity: 0.82 }}>
                Password for all of them: <strong>{demo.data.password}</strong>
              </Typography>
              <Stack divider={<Divider sx={{ borderColor: 'rgba(255,255,255,0.14)' }} />} spacing={1.25}>
                {demo.data.accounts.map((account) => (
                  <Box
                    key={account.email}
                    component="button"
                    type="button"
                    onClick={() => {
                      setMode('sign-in')
                      setEmail(account.email)
                      setPassword(demo.data!.password)
                    }}
                    sx={{
                      appearance: 'none',
                      background: 'none',
                      border: 0,
                      p: 0,
                      textAlign: 'left',
                      color: 'inherit',
                      cursor: 'pointer',
                      '&:hover': { opacity: 0.85 },
                    }}
                  >
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>
                      {account.role} — {account.name}
                    </Typography>
                    <Typography variant="caption" sx={{ opacity: 0.75 }}>
                      {account.whatTheySee}
                    </Typography>
                  </Box>
                ))}
              </Stack>
            </CardContent>
          </Card>
        ) : null}
      </Box>
    </Box>
  )
}
