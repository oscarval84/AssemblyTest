import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormGroup from '@mui/material/FormGroup'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { RequestFailed } from '../../api/client'
import { useCreateSupplier, usePrograms } from '../../api/queries'

/**
 * Supplier intake, in one step.
 *
 * The company, its programs and the first user's invitation are created in a
 * single transaction, so there is no state where a supplier exists in the
 * pipeline and has never heard from Acme.
 */
export default function NewSupplierDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const programs = usePrograms()
  const create = useCreateSupplier()
  const navigate = useNavigate()

  const [legalName, setLegalName] = useState('')
  const [contactName, setContactName] = useState('')
  const [contactEmail, setContactEmail] = useState('')
  const [programIds, setProgramIds] = useState<string[]>([])

  const failure = create.error instanceof RequestFailed ? create.error.message : null

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    const supplier = await create.mutateAsync({ legalName, contactName, contactEmail, programIds })
    onClose()
    navigate(`/ops/suppliers/${supplier.profile.id}`)
  }

  return (
    <Dialog open={open} onClose={onClose}>
      <form onSubmit={submit}>
        <DialogTitle>Invite a supplier</DialogTitle>

        <DialogContent dividers>
          <Stack spacing={2.5}>
            {failure ? <Alert severity="error">{failure}</Alert> : null}

            <TextField
              label="Legal name"
              required
              fullWidth
              value={legalName}
              onChange={(event) => setLegalName(event.target.value)}
              helperText="As it appears on their W-9. They can correct it in their profile."
            />
            <TextField
              label="Contact name"
              required
              fullWidth
              value={contactName}
              onChange={(event) => setContactName(event.target.value)}
            />
            <TextField
              label="Contact email"
              type="email"
              required
              fullWidth
              value={contactEmail}
              onChange={(event) => setContactEmail(event.target.value)}
              helperText="The invitation goes here. It is single-use and expires in seven days."
            />

            <div>
              <Typography variant="overline" component="div">
                Programs
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
                The programs decide which documents we ask for. More can be added later, and
                anything already on file carries over.
              </Typography>
              <FormGroup>
                {(programs.data ?? []).map((program) => (
                  <FormControlLabel
                    key={program.id}
                    control={
                      <Checkbox
                        checked={programIds.includes(program.id)}
                        onChange={(event) =>
                          setProgramIds((current) =>
                            event.target.checked
                              ? [...current, program.id]
                              : current.filter((id) => id !== program.id),
                          )
                        }
                      />
                    }
                    label={program.name}
                  />
                ))}
              </FormGroup>
            </div>
          </Stack>
        </DialogContent>

        <DialogActions>
          <Button onClick={onClose} color="inherit">
            Cancel
          </Button>
          <Button type="submit" variant="contained" disabled={create.isPending || programIds.length === 0}>
            {create.isPending ? 'Sending…' : 'Create and send the invitation'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
