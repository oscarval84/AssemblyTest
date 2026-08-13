import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Link from '@mui/material/Link'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useState } from 'react'
import { RequestFailed } from '../../api/client'
import { documentDownloadUrl, useRejectionReasons, useReviewDecision } from '../../api/queries'
import { Field } from '../../components/common'
import { formatDate, formatDateTime, formatFileSize } from '../../lib/format'

export interface ReviewTarget {
  submissionId: string
  supplierId: string
  supplierLegalName: string
  documentTypeName: string
  originalFilename: string
  sizeBytes: number
  version: number
  uploadedAt: string
  uploadedByName?: string | null
  issuedOn?: string | null
  expiresOn?: string | null
  /** False when the caller uploaded it: they must hand it to a colleague. */
  reviewableByCaller: boolean
}

/**
 * The review decision.
 *
 * Rejecting asks for a reason before it will submit, and that is the product
 * rule rather than form validation: the schema refuses to store a rejection
 * without one, because "rejected, no reason given" is the experience suppliers
 * described as faxing paperwork into a void. The note is optional and is where
 * the reason becomes an explanation.
 */
export default function ReviewDialog({
  target,
  open,
  onClose,
}: {
  target: ReviewTarget
  open: boolean
  onClose: () => void
}) {
  const reasons = useRejectionReasons()
  const decide = useReviewDecision(target.supplierId)

  const [mode, setMode] = useState<'decide' | 'reject'>('decide')
  const [reasonCode, setReasonCode] = useState('')
  const [note, setNote] = useState('')

  const failure = decide.error instanceof RequestFailed ? decide.error.message : null

  function close() {
    decide.reset()
    setMode('decide')
    setReasonCode('')
    setNote('')
    onClose()
  }

  async function approve() {
    await decide.mutateAsync({ submissionId: target.submissionId })
    close()
  }

  async function reject(event: React.FormEvent) {
    event.preventDefault()
    await decide.mutateAsync({
      submissionId: target.submissionId,
      reasonCode,
      note: note.trim() || null,
    })
    close()
  }

  return (
    <Dialog open={open} onClose={close} maxWidth="sm">
      <form onSubmit={reject}>
        <DialogTitle>
          {target.documentTypeName}
          <Typography variant="body2" color="text.secondary">
            {target.supplierLegalName}
          </Typography>
        </DialogTitle>

        <DialogContent dividers>
          <Stack spacing={2.5}>
            {failure ? <Alert severity="error">{failure}</Alert> : null}

            {!target.reviewableByCaller ? (
              <Alert severity="warning">
                You uploaded this document, so a colleague has to review it. Ops can upload on a
                supplier's behalf, but the approver is always a second pair of eyes.
              </Alert>
            ) : null}

            <Box
              sx={{
                display: 'grid',
                gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)' },
                gap: 2,
              }}
            >
              <Field label="File">
                <Link href={documentDownloadUrl(target.submissionId)} target="_blank" rel="noopener">
                  {target.originalFilename}
                </Link>{' '}
                ({formatFileSize(target.sizeBytes)})
              </Field>
              <Field label="Version">{target.version}</Field>
              <Field label="Sent">
                {formatDateTime(target.uploadedAt)}
                {target.uploadedByName ? ` by ${target.uploadedByName}` : ''}
              </Field>
              <Field label="Expires">{target.expiresOn ? formatDate(target.expiresOn) : 'Does not expire'}</Field>
            </Box>

            {mode === 'reject' ? (
              <>
                <TextField
                  select
                  required
                  label="Reason"
                  fullWidth
                  value={reasonCode}
                  onChange={(event) => setReasonCode(event.target.value)}
                  helperText="The supplier sees this, so it has to make sense to them."
                >
                  {(reasons.data ?? []).map((reason) => (
                    <MenuItem key={reason.code} value={reason.code}>
                      {reason.label}
                    </MenuItem>
                  ))}
                </TextField>

                <TextField
                  label="Note to the supplier"
                  fullWidth
                  multiline
                  minRows={3}
                  value={note}
                  onChange={(event) => setNote(event.target.value)}
                  helperText="Optional, and usually the difference between one resubmission and three."
                />
              </>
            ) : (
              <Typography variant="body2" color="text.secondary">
                Open the file, then accept it or hand it back. Approving the last outstanding
                document completes this supplier's onboarding and activates their programs.
              </Typography>
            )}
          </Stack>
        </DialogContent>

        <DialogActions>
          <Button onClick={close} color="inherit">
            Cancel
          </Button>

          {mode === 'decide' ? (
            <>
              <Button
                color="error"
                onClick={() => setMode('reject')}
                disabled={!target.reviewableByCaller}
              >
                Request changes
              </Button>
              <Button
                variant="contained"
                onClick={approve}
                disabled={decide.isPending || !target.reviewableByCaller}
              >
                {decide.isPending ? 'Saving…' : 'Approve'}
              </Button>
            </>
          ) : (
            <>
              <Button color="inherit" onClick={() => setMode('decide')}>
                Back
              </Button>
              <Button type="submit" variant="contained" color="error" disabled={!reasonCode || decide.isPending}>
                {decide.isPending ? 'Sending…' : 'Send it back'}
              </Button>
            </>
          )}
        </DialogActions>
      </form>
    </Dialog>
  )
}
