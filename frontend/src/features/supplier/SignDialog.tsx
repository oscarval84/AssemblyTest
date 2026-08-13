import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import CircularProgress from '@mui/material/CircularProgress'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useState } from 'react'
import { RequestFailed, type ChecklistEntry } from '../../api/client'
import { useAgreement, useSignAgreement } from '../../api/queries'

/**
 * The signature ceremony.
 *
 * The full text is shown, scrollable, before the name field is reachable — a
 * signature on a document nobody was shown is worth nothing in the audit this
 * product exists to survive. What is stored is not a click: signing renders a
 * new PDF carrying this text, the typed name, the timestamp and the hash of the
 * template version, and that file is what an auditor is handed.
 */
export default function SignDialog({
  supplierId,
  entry,
  signerName,
  open,
  onClose,
}: {
  supplierId: string
  entry: ChecklistEntry
  signerName: string
  open: boolean
  onClose: () => void
}) {
  const agreement = useAgreement(supplierId, entry.documentTypeCode, open)
  const sign = useSignAgreement(supplierId)

  const [typedName, setTypedName] = useState(signerName)
  const [agreed, setAgreed] = useState(false)

  const failure = sign.error instanceof RequestFailed ? sign.error.message : null

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    await sign.mutateAsync({
      documentTypeCode: entry.documentTypeCode,
      enrollmentId: entry.enrollmentId,
      typedName,
    })
    close()
  }

  function close() {
    sign.reset()
    setAgreed(false)
    onClose()
  }

  return (
    <Dialog open={open} onClose={close} maxWidth="md">
      <form onSubmit={submit}>
        <DialogTitle>{entry.documentTypeName}</DialogTitle>

        <DialogContent dividers>
          {agreement.isPending ? (
            <Stack sx={{ alignItems: 'center', py: 6 }}>
              <CircularProgress size={26} />
            </Stack>
          ) : agreement.isError ? (
            <Alert severity="error">
              We could not load the agreement text. Refresh the page and try again.
            </Alert>
          ) : (
            <Stack spacing={2.5}>
              <Box
                sx={{
                  maxHeight: 320,
                  overflowY: 'auto',
                  border: '1px solid',
                  borderColor: 'divider',
                  borderRadius: 1,
                  p: 2,
                  bgcolor: 'background.default',
                }}
              >
                <Typography
                  component="pre"
                  variant="body2"
                  sx={{ whiteSpace: 'pre-wrap', fontFamily: 'inherit', m: 0 }}
                >
                  {agreement.data.body}
                </Typography>
              </Box>

              <Typography variant="caption" color="text.secondary">
                Template version {agreement.data.templateVersion} · SHA-256{' '}
                <span className="tabular">{agreement.data.templateSha256.slice(0, 16)}…</span> — recorded
                with your signature so this exact text stays provable.
              </Typography>

              {failure ? <Alert severity="error">{failure}</Alert> : null}

              <TextField
                label="Type your full name to sign"
                required
                fullWidth
                value={typedName}
                onChange={(event) => setTypedName(event.target.value)}
              />

              <FormControlLabel
                control={<Checkbox checked={agreed} onChange={(event) => setAgreed(event.target.checked)} />}
                label={
                  <Typography variant="body2">
                    I have read this agreement and I am authorised to sign it on behalf of my company.
                    I understand my name, the time, and my network address are recorded.
                  </Typography>
                }
              />
            </Stack>
          )}
        </DialogContent>

        <DialogActions>
          <Button onClick={close} color="inherit">
            Cancel
          </Button>
          <Button
            type="submit"
            variant="contained"
            disabled={!agreed || typedName.trim().length < 3 || sign.isPending}
          >
            {sign.isPending ? 'Signing…' : 'Sign and file with Acme'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
