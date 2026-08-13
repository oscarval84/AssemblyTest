import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { RequestFailed } from '../../api/client'
import { useCompletePasswordReset } from '../../api/queries'
import { Brand } from '../../components/common'

const MINIMUM_LENGTH = 12

export default function ResetPasswordPage() {
  const { token = '' } = useParams()
  const navigate = useNavigate()
  const reset = useCompletePasswordReset(token)
  const [password, setPassword] = useState('')

  const failure = reset.error instanceof RequestFailed ? reset.error.message : null

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    await reset.mutateAsync({ password })
  }

  return (
    <Box sx={{ minHeight: '100dvh', bgcolor: 'background.default', display: 'grid', placeItems: 'center', p: 3 }}>
      <Box sx={{ width: '100%', maxWidth: 440 }}>
        <Brand surface="supplier" />
        <Typography variant="h1" sx={{ mt: 5 }}>
          Choose a new password
        </Typography>

        {reset.isSuccess ? (
          <>
            <Alert severity="success" sx={{ mt: 3 }}>
              Your password is set. Setting it signed you out everywhere else, which is deliberate —
              any other session on your account has ended.
            </Alert>
            <Button sx={{ mt: 3 }} variant="contained" onClick={() => navigate('/login')}>
              Sign in
            </Button>
          </>
        ) : (
          <form onSubmit={submit}>
            <Stack spacing={2.5} sx={{ mt: 3 }}>
              {failure ? <Alert severity="error">{failure}</Alert> : null}
              <TextField
                label="New password"
                type="password"
                autoComplete="new-password"
                required
                fullWidth
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                helperText={`At least ${MINIMUM_LENGTH} characters.`}
              />
              <Button type="submit" variant="contained" size="large" disabled={reset.isPending}>
                Save my new password
              </Button>
            </Stack>
          </form>
        )}
      </Box>
    </Box>
  )
}
