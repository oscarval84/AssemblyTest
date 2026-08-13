import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useState } from 'react'
import { RequestFailed, type ChecklistEntry } from '../../api/client'
import { useUploadDocument } from '../../api/queries'
import { constraintLines } from '../../lib/labels'
import { formatFileSize } from '../../lib/format'

const ACCEPTED = 'application/pdf,image/png,image/jpeg'

/**
 * One upload, with the requirement it satisfies stated on the same screen.
 *
 * The acceptance terms are shown here rather than only in the checklist, because
 * this is the moment someone is deciding whether the file in front of them is
 * the right one. A rejection two days later that says "coverage below the
 * program minimum" is a question that could have been answered now.
 */
export default function UploadDialog({
  supplierId,
  entry,
  open,
  onClose,
}: {
  supplierId: string
  entry: ChecklistEntry
  open: boolean
  onClose: () => void
}) {
  const upload = useUploadDocument(supplierId)
  const [file, setFile] = useState<File | null>(null)
  const [issuedOn, setIssuedOn] = useState('')
  const [expiresOn, setExpiresOn] = useState('')

  const constraints = constraintLines(entry)
  const failure = upload.error instanceof RequestFailed ? upload.error.message : null

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (!file) return
    await upload.mutateAsync({
      file,
      documentTypeCode: entry.documentTypeCode,
      enrollmentId: entry.enrollmentId,
      issuedOn: issuedOn || null,
      expiresOn: expiresOn || null,
    })
    close()
  }

  function close() {
    upload.reset()
    setFile(null)
    setIssuedOn('')
    setExpiresOn('')
    onClose()
  }

  return (
    <Dialog open={open} onClose={close}>
      <form onSubmit={submit}>
        <DialogTitle>Send your {entry.documentTypeName.toLowerCase()}</DialogTitle>

        <DialogContent dividers>
          <Stack spacing={2.5}>
            {entry.description ? (
              <Typography variant="body2" color="text.secondary">
                {entry.description}
              </Typography>
            ) : null}

            {constraints.length > 0 ? (
              <Alert severity="info">
                <Typography variant="body2" sx={{ fontWeight: 600, mb: 0.5 }}>
                  What this program requires
                </Typography>
                {constraints.map((line) => (
                  <Typography key={line} variant="body2">
                    {line}
                  </Typography>
                ))}
              </Alert>
            ) : null}

            {failure ? <Alert severity="error">{failure}</Alert> : null}

            <Box>
              <Button variant="outlined" component="label" fullWidth sx={{ py: 1.5 }}>
                {file ? `${file.name} · ${formatFileSize(file.size)}` : 'Choose a file'}
                <input
                  hidden
                  type="file"
                  accept={ACCEPTED}
                  onChange={(event) => setFile(event.target.files?.[0] ?? null)}
                />
              </Button>
              <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: 'block' }}>
                PDF, PNG or JPEG, up to 10 MB. A phone photo is fine as long as every corner is
                readable.
              </Typography>
            </Box>

            {entry.expiring ? (
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <TextField
                  label="Issued on"
                  type="date"
                  fullWidth
                  slotProps={{ inputLabel: { shrink: true } }}
                  value={issuedOn}
                  onChange={(event) => setIssuedOn(event.target.value)}
                />
                <TextField
                  label="Expires on"
                  type="date"
                  required
                  fullWidth
                  slotProps={{ inputLabel: { shrink: true } }}
                  value={expiresOn}
                  onChange={(event) => setExpiresOn(event.target.value)}
                  helperText="The date printed on the document."
                />
              </Stack>
            ) : null}
          </Stack>
        </DialogContent>

        <DialogActions>
          <Button onClick={close} color="inherit">
            Cancel
          </Button>
          <Button type="submit" variant="contained" disabled={!file || upload.isPending}>
            {upload.isPending ? 'Sending…' : 'Send to Acme'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
