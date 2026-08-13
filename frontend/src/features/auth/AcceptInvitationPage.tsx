import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { RequestFailed } from '../../api/client'
import { useAcceptInvitation, useInvitation } from '../../api/queries'
import { Brand } from '../../components/common'
import { homeFor } from './routing'

const MINIMUM_LENGTH = 12

/**
 * Where an invited supplier lands from their email.
 *
 * The invitation is read before anything is typed, so the page can name the
 * company and explain a dead link — an expired invitation says so and says what
 * to do, rather than presenting a form that fails on submit.
 */
export default function AcceptInvitationPage() {
  const { token = '' } = useParams()
  const navigate = useNavigate()
  const invitation = useInvitation(token)
  const accept = useAcceptInvitation(token)

  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')

  const mismatch = confirmation.length > 0 && password !== confirmation
  const tooShort = password.length > 0 && password.length < MINIMUM_LENGTH
  const failure = accept.error instanceof RequestFailed ? accept.error.message : null

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    const session = await accept.mutateAsync({ password })
    navigate(homeFor(session.role), { replace: true })
  }

  return (
    <Box sx={{ minHeight: '100dvh', bgcolor: 'background.default', display: 'grid', placeItems: 'center', p: 3 }}>
      <Box sx={{ width: '100%', maxWidth: 460 }}>
        <Brand surface="supplier" />

        {invitation.isPending ? (
          <Stack sx={{ alignItems: 'center', py: 8 }}>
            <CircularProgress size={28} />
          </Stack>
        ) : invitation.isError ? (
          <>
            <Typography variant="h1" sx={{ mt: 5 }}>
              This link is not valid
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 1.5 }}>
              {invitation.error instanceof RequestFailed
                ? invitation.error.message
                : 'Ask your Acme contact to send a new invitation.'}
            </Typography>
            <Button sx={{ mt: 3 }} variant="outlined" onClick={() => navigate('/login')}>
              Go to sign in
            </Button>
          </>
        ) : !invitation.data.usable ? (
          <>
            <Typography variant="h1" sx={{ mt: 5 }}>
              This invitation cannot be used
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 1.5 }}>
              {invitation.data.unusableReason}
            </Typography>
            <Button sx={{ mt: 3 }} variant="contained" onClick={() => navigate('/login')}>
              Go to sign in
            </Button>
          </>
        ) : (
          <>
            <Typography variant="h1" sx={{ mt: 5 }}>
              Welcome, {invitation.data.fullName.split(' ')[0]}
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 1.5, mb: 4 }}>
              You are setting up access for <strong>{invitation.data.organizationName}</strong> as{' '}
              {invitation.data.email}. Choose a password and we will take you straight to your
              checklist.
            </Typography>

            <form onSubmit={submit}>
              <Stack spacing={2.5}>
                {failure ? <Alert severity="error">{failure}</Alert> : null}

                <TextField
                  label="Choose a password"
                  type="password"
                  autoComplete="new-password"
                  required
                  fullWidth
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  error={tooShort}
                  helperText={
                    tooShort
                      ? `At least ${MINIMUM_LENGTH} characters.`
                      : `At least ${MINIMUM_LENGTH} characters. A short phrase you will remember is stronger than a short password with symbols in it.`
                  }
                />

                <TextField
                  label="Type it again"
                  type="password"
                  autoComplete="new-password"
                  required
                  fullWidth
                  value={confirmation}
                  onChange={(event) => setConfirmation(event.target.value)}
                  error={mismatch}
                  helperText={mismatch ? 'These two do not match.' : ' '}
                />

                <Button
                  type="submit"
                  variant="contained"
                  size="large"
                  disabled={accept.isPending || mismatch || tooShort || password.length === 0}
                >
                  Set my password and continue
                </Button>
              </Stack>
            </form>
          </>
        )}
      </Box>
    </Box>
  )
}
