import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import Checkbox from '@mui/material/Checkbox'
import CircularProgress from '@mui/material/CircularProgress'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormGroup from '@mui/material/FormGroup'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useState } from 'react'
import { RequestFailed, type StaffUser } from '../../api/client'
import {
  useAccessHistory,
  usePrograms,
  useStaffAdministration,
  useStaffUsers,
} from '../../api/queries'
import { EmptyState, PageHeader, StatusChip } from '../../components/common'
import { formatDateTime } from '../../lib/format'
import { actionLabel } from '../../lib/labels'
import { compliance } from '../../theme/theme'

type StaffRole = 'ADMIN' | 'OPS' | 'PROGRAM_MANAGER'

const ROLE_LABELS: Record<StaffRole, string> = {
  ADMIN: 'Administrator',
  OPS: 'Supplier operations',
  PROGRAM_MANAGER: 'Program manager (read-only)',
}

/**
 * Acme staff administration — the access report with actions attached.
 *
 * One surface rather than a read-only report plus a separate editor, because
 * the question an access review asks ("who has this, and should they?") and the
 * action it leads to ("not any more") belong on the same screen.
 *
 * Supplier users are deliberately absent: they are managed from their own
 * company's record, by ops. Keeping the two apart is what stops Acme-internal
 * access from being granted by someone working a supplier's file.
 */
export default function StaffAdminPage() {
  const staff = useStaffUsers()
  const programs = usePrograms()
  const admin = useStaffAdministration()

  const [inviting, setInviting] = useState(false)
  const [scoping, setScoping] = useState<StaffUser | null>(null)
  const [historyFor, setHistoryFor] = useState<StaffUser | null>(null)

  const failure = [admin.changeRole, admin.setStatus, admin.sendReset, admin.setProgramScope]
    .map((mutation) => (mutation.error instanceof RequestFailed ? mutation.error.message : null))
    .find(Boolean)

  if (staff.isPending) {
    return (
      <Stack sx={{ alignItems: 'center', py: 10 }}>
        <CircularProgress size={28} />
      </Stack>
    )
  }

  if (staff.isError) {
    return (
      <Alert severity="error">
        {staff.error instanceof RequestFailed
          ? staff.error.message
          : 'We could not load the access report.'}
      </Alert>
    )
  }

  const programName = (id: string) => programs.data?.find((program) => program.id === id)?.name ?? id

  return (
    <>
      <PageHeader
        title="Acme staff access"
        description="Everyone inside Acme with an account, what they can reach, and when they last used it. Supplier users are managed from their own company's record."
        actions={
          <Button variant="contained" onClick={() => setInviting(true)}>
            Invite a colleague
          </Button>
        }
      />

      {failure ? (
        <Alert severity="error" sx={{ mb: 3 }} onClose={() => admin.changeRole.reset()}>
          {failure}
        </Alert>
      ) : null}

      {staff.data.length === 0 ? (
        <EmptyState
          title="No internal accounts yet"
          description="Invite the first colleague and they appear here with their role, their program scope and their last sign-in."
          action={
            <Button variant="contained" onClick={() => setInviting(true)}>
              Invite a colleague
            </Button>
          }
        />
      ) : (
        <Card sx={{ overflowX: 'auto' }}>
          <Table size="small" sx={{ minWidth: 1040 }}>
            <TableHead>
              <TableRow>
                <TableCell>Person</TableCell>
                <TableCell>Role</TableCell>
                <TableCell>Programs</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Last signed in</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {staff.data.map((user) => (
                <TableRow key={user.id} hover>
                  <TableCell>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>
                      {user.fullName}
                    </Typography>
                    <Typography variant="caption" color="text.secondary" component="div">
                      {user.email}
                    </Typography>
                  </TableCell>

                  <TableCell>
                    <TextField
                      select
                      size="small"
                      value={user.role}
                      sx={{ minWidth: 190 }}
                      onChange={(event) =>
                        admin.changeRole.mutate({ userId: user.id, role: event.target.value as StaffRole })
                      }
                    >
                      {(Object.keys(ROLE_LABELS) as StaffRole[]).map((role) => (
                        <MenuItem key={role} value={role}>
                          {ROLE_LABELS[role]}
                        </MenuItem>
                      ))}
                    </TextField>
                  </TableCell>

                  <TableCell>
                    {user.role === 'PROGRAM_MANAGER' ? (
                      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }} useFlexGap>
                        <Typography variant="body2">
                          {user.programIds.length === 0
                            ? 'No programs yet'
                            : user.programIds.map(programName).join(', ')}
                        </Typography>
                        <Button size="small" onClick={() => setScoping(user)}>
                          Change
                        </Button>
                      </Stack>
                    ) : (
                      <Typography variant="body2" color="text.secondary">
                        All programs
                      </Typography>
                    )}
                  </TableCell>

                  <TableCell>
                    <StatusChip
                      label={user.status.toLowerCase()}
                      color={
                        user.status === 'ACTIVE'
                          ? compliance.compliant
                          : user.status === 'INVITED'
                            ? compliance.inReview
                            : compliance.idle
                      }
                    />
                  </TableCell>

                  <TableCell className="tabular">
                    <Typography variant="body2">
                      {user.lastLoginAt ? formatDateTime(user.lastLoginAt) : 'Never'}
                    </Typography>
                  </TableCell>

                  <TableCell align="right">
                    <Stack direction="row" spacing={0.5} sx={{ justifyContent: 'flex-end' }}>
                      <Button size="small" onClick={() => setHistoryFor(user)}>
                        History
                      </Button>
                      <Button
                        size="small"
                        onClick={() => admin.sendReset.mutate(user.id)}
                        disabled={user.status === 'DEACTIVATED' || admin.sendReset.isPending}
                      >
                        Send reset
                      </Button>
                      <Button
                        size="small"
                        color={user.status === 'DEACTIVATED' ? 'primary' : 'error'}
                        onClick={() =>
                          admin.setStatus.mutate({
                            userId: user.id,
                            active: user.status === 'DEACTIVATED',
                          })
                        }
                      >
                        {user.status === 'DEACTIVATED' ? 'Restore' : 'Remove access'}
                      </Button>
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Card>
      )}

      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 2 }}>
        Removing access ends the person's sessions immediately rather than at their next sign-in, and
        every change here is recorded against them. Nobody can remove their own administrator role,
        and the last remaining administrator cannot be removed at all.
      </Typography>

      {inviting ? <InviteDialog onClose={() => setInviting(false)} /> : null}
      {scoping ? <ScopeDialog user={scoping} onClose={() => setScoping(null)} /> : null}
      {historyFor ? <HistoryDialog user={historyFor} onClose={() => setHistoryFor(null)} /> : null}
    </>
  )
}

function InviteDialog({ onClose }: { onClose: () => void }) {
  const programs = usePrograms()
  const { invite } = useStaffAdministration()

  const [email, setEmail] = useState('')
  const [fullName, setFullName] = useState('')
  const [role, setRole] = useState<StaffRole>('OPS')
  const [programIds, setProgramIds] = useState<string[]>([])

  const failure = invite.error instanceof RequestFailed ? invite.error.message : null

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    await invite.mutateAsync({ email, fullName, role, programIds })
    onClose()
  }

  return (
    <Dialog open onClose={onClose}>
      <form onSubmit={submit}>
        <DialogTitle>Invite a colleague</DialogTitle>
        <DialogContent dividers>
          <Stack spacing={2.5}>
            {failure ? <Alert severity="error">{failure}</Alert> : null}

            <TextField
              label="Name"
              required
              fullWidth
              value={fullName}
              onChange={(event) => setFullName(event.target.value)}
              helperText="Shown in the access report and on every action they take."
            />
            <TextField
              label="Acme email address"
              type="email"
              required
              fullWidth
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              helperText="They set their own password from the link we send. Nobody here ever sees it."
            />
            <TextField
              select
              label="Role"
              fullWidth
              value={role}
              onChange={(event) => setRole(event.target.value as StaffRole)}
            >
              {(Object.keys(ROLE_LABELS) as StaffRole[]).map((option) => (
                <MenuItem key={option} value={option}>
                  {ROLE_LABELS[option]}
                </MenuItem>
              ))}
            </TextField>

            {role === 'PROGRAM_MANAGER' ? (
              <Box>
                <Typography variant="overline" component="div">
                  Which programs
                </Typography>
                <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
                  A program manager sees suppliers in these programs and nothing else, and cannot
                  change anything.
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
              </Box>
            ) : null}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} color="inherit">
            Cancel
          </Button>
          <Button type="submit" variant="contained" disabled={invite.isPending}>
            {invite.isPending ? 'Sending…' : 'Send the invitation'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}

function ScopeDialog({ user, onClose }: { user: StaffUser; onClose: () => void }) {
  const programs = usePrograms()
  const { setProgramScope } = useStaffAdministration()
  const [programIds, setProgramIds] = useState<string[]>(user.programIds)

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    await setProgramScope.mutateAsync({ userId: user.id, programIds })
    onClose()
  }

  return (
    <Dialog open onClose={onClose}>
      <form onSubmit={submit}>
        <DialogTitle>
          Program access
          <Typography variant="body2" color="text.secondary">
            {user.fullName}
          </Typography>
        </DialogTitle>
        <DialogContent dividers>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Takes effect on their next request. A program manager with no programs can sign in and
            see nothing, which is a valid way to park an account.
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
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} color="inherit">
            Cancel
          </Button>
          <Button type="submit" variant="contained" disabled={setProgramScope.isPending}>
            Save
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}

function HistoryDialog({ user, onClose }: { user: StaffUser; onClose: () => void }) {
  const history = useAccessHistory(user.id)

  return (
    <Dialog open onClose={onClose}>
      <DialogTitle>
        Access history
        <Typography variant="body2" color="text.secondary">
          {user.fullName} · {user.email}
        </Typography>
      </DialogTitle>
      <DialogContent dividers>
        {history.isPending ? (
          <Stack sx={{ alignItems: 'center', py: 4 }}>
            <CircularProgress size={24} />
          </Stack>
        ) : history.data && history.data.length > 0 ? (
          <Stack spacing={1.75}>
            {history.data.map((event) => (
              <Box key={event.id}>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>
                  {actionLabel(event.action)}
                </Typography>
                <Typography variant="caption" color="text.secondary" component="div">
                  {event.actorLabel} · {formatDateTime(event.occurredAt)}
                </Typography>
                {event.beforeState && event.afterState ? (
                  <Typography variant="caption" color="text.secondary" component="div">
                    {event.beforeState} → {event.afterState}
                  </Typography>
                ) : null}
              </Box>
            ))}
          </Stack>
        ) : (
          <Typography variant="body2" color="text.secondary">
            Nothing recorded yet. Invitations, role changes and access removals all land here.
          </Typography>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  )
}
