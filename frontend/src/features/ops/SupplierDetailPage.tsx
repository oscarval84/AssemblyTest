import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import CircularProgress from '@mui/material/CircularProgress'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Divider from '@mui/material/Divider'
import TextField from '@mui/material/TextField'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useState } from 'react'
import { Link as RouterLink, useParams } from 'react-router-dom'
import {
  documentDownloadUrl,
  useActivity,
  useChecklist,
  useSession,
  useSupplier,
  useSupplierUserAdministration,
  useSupplierUsers,
} from '../../api/queries'
import { RequestFailed } from '../../api/client'
import ReviewDialog, { type ReviewTarget } from './ReviewDialog'
import { EmptyState, Field, PageHeader, StatusChip } from '../../components/common'
import { formatDate, formatDateTime, formatExpiry } from '../../lib/format'
import {
  actionLabel,
  complianceLook,
  requirementLook,
  stageLabel,
  waitingOnColor,
  waitingOnLabel,
} from '../../lib/labels'

/**
 * One supplier's full record: the profile, every program and its compliance,
 * every document, and the activity history behind all of it.
 *
 * The timeline is the feature Dana asked for by name — the history she hands an
 * auditor — so it sits on the record rather than behind an export button.
 */
export default function SupplierDetailPage() {
  const { id } = useParams()
  const session = useSession()
  const supplier = useSupplier(id)
  const [reviewing, setReviewing] = useState<ReviewTarget | null>(null)
  const canReview = session.data?.role === 'OPS' || session.data?.role === 'ADMIN'
  const checklist = useChecklist(id)
  const activity = useActivity(id)
  const users = useSupplierUsers(id)
  const supplierUsers = useSupplierUserAdministration(id ?? '')
  const [invitingUser, setInvitingUser] = useState(false)

  if (supplier.isPending) {
    return (
      <Stack sx={{ alignItems: 'center', py: 10 }}>
        <CircularProgress size={28} />
      </Stack>
    )
  }

  if (supplier.isError) {
    return (
      <Alert severity="error">
        We could not load that supplier. It may have been deactivated.{' '}
        <Link component={RouterLink} to="/ops">
          Back to the pipeline
        </Link>
      </Alert>
    )
  }

  const { profile, blocker, enrollments, complianceStatus } = supplier.data
  const look = complianceLook(complianceStatus)

  return (
    <>
      <PageHeader
        title={profile.legalName}
        description={
          <Stack direction="row" spacing={1.5} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
            <StatusChip label={stageLabel(profile.stage)} color="#4A5C6A" />
            <StatusChip label={look.label} color={look.color} />
            <StatusChip label={waitingOnLabel(blocker.waitingOn)} color={waitingOnColor(blocker.waitingOn)} />
            <Typography variant="body2">{blocker.summary}</Typography>
          </Stack>
        }
      />

      <Stack direction={{ xs: 'column', lg: 'row' }} spacing={3} sx={{ alignItems: 'flex-start' }}>
        <Stack spacing={3} sx={{ flex: 2, minWidth: 0, width: '100%' }}>
          <Card>
            <CardContent>
              <Typography variant="overline" component="div" sx={{ mb: 2 }}>
                Company profile
              </Typography>
              <Box
                sx={{
                  display: 'grid',
                  gridTemplateColumns: { xs: '1fr', sm: 'repeat(3, 1fr)' },
                  gap: 2.5,
                }}
              >
                <Field label="Legal name">{profile.legalName}</Field>
                <Field label="Doing business as">{profile.dbaName}</Field>
                <Field label="Entity type">{profile.entityType}</Field>
                <Field label="Tax ID">
                  {profile.taxIdLast4 ? `•••••${profile.taxIdLast4}` : 'Not provided'}
                </Field>
                <Field label="Address">
                  {[profile.addressLine1, profile.city, profile.state, profile.postalCode]
                    .filter(Boolean)
                    .join(', ') || null}
                </Field>
                <Field label="Contact">
                  {profile.primaryContactName ? (
                    <>
                      {profile.primaryContactName}
                      <br />
                      {profile.primaryContactEmail}
                    </>
                  ) : null}
                </Field>
              </Box>
            </CardContent>
          </Card>

          <Card>
            <CardContent>
              <Typography variant="overline" component="div" sx={{ mb: 2 }}>
                Programs and compliance
              </Typography>
              <Stack spacing={2} divider={<Divider flexItem />}>
                {enrollments.length === 0 ? (
                  <Typography variant="body2" color="text.secondary">
                    Not enrolled in any program yet.
                  </Typography>
                ) : (
                  enrollments.map((enrollment) => {
                    const enrollmentLook = complianceLook(enrollment.complianceStatus)
                    return (
                      <Box key={enrollment.enrollmentId}>
                        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', mb: 0.5 }}>
                          <Typography variant="h3">{enrollment.programName}</Typography>
                          <StatusChip label={enrollmentLook.label} color={enrollmentLook.color} />
                          <Typography variant="caption" color="text.secondary">
                            {enrollment.status.toLowerCase()}
                          </Typography>
                        </Stack>
                        {enrollment.findings.length === 0 ? (
                          <Typography variant="body2" color="text.secondary">
                            Every requirement is approved and current.
                          </Typography>
                        ) : (
                          enrollment.findings.map((finding) => (
                            <Typography key={finding.documentTypeCode} variant="body2" color="text.secondary">
                              {finding.documentTypeName} — {finding.issue.toLowerCase().replaceAll('_', ' ')}
                              {finding.expiresOn ? `, ${formatExpiry(finding.expiresOn)}` : ''}
                            </Typography>
                          ))
                        )}
                      </Box>
                    )
                  })
                )}
              </Stack>
            </CardContent>
          </Card>

          <Card>
            <CardContent>
              <Typography variant="overline" component="div" sx={{ mb: 2 }}>
                Documents
              </Typography>
              {checklist.data ? (
                <Stack spacing={1.5}>
                  {checklist.data.programs
                    .flatMap((program) => [...program.neededForThisProgram, ...program.alreadyOnFile])
                    // A shared document appears once per program it satisfies.
                    .filter(
                      (entry, index, all) =>
                        all.findIndex(
                          (other) =>
                            other.documentTypeCode === entry.documentTypeCode &&
                            other.enrollmentId === entry.enrollmentId,
                        ) === index,
                    )
                    .map((entry) => {
                      const entryLook = requirementLook(entry.state)
                      return (
                        <Stack
                          key={`${entry.documentTypeCode}-${entry.enrollmentId ?? 'shared'}`}
                          direction="row"
                          spacing={1.5} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
                          <Box>
                            <Typography variant="body2" sx={{ fontWeight: 600 }}>
                              {entry.documentTypeName}
                            </Typography>
                            {entry.submission ? (
                              <Typography variant="caption" color="text.secondary">
                                <Link href={documentDownloadUrl(entry.submission.id)} target="_blank" rel="noopener">
                                  {entry.submission.originalFilename}
                                </Link>{' '}
                                · v{entry.submission.version} ·{' '}
                                {formatDateTime(entry.submission.uploadedAt)}
                                {entry.submission.expiresOn
                                  ? ` · ${formatExpiry(entry.submission.expiresOn)}`
                                  : ''}
                              </Typography>
                            ) : (
                              <Typography variant="caption" color="text.secondary">
                                Nothing submitted yet
                              </Typography>
                            )}
                          </Box>
                          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                            <StatusChip label={entryLook.label} color={entryLook.color} />
                            {canReview && entry.state === 'IN_REVIEW' && entry.submission ? (
                              <Button
                                size="small"
                                variant="contained"
                                onClick={() =>
                                  setReviewing({
                                    submissionId: entry.submission!.id,
                                    supplierId: profile.id,
                                    supplierLegalName: profile.legalName,
                                    documentTypeName: entry.documentTypeName,
                                    originalFilename: entry.submission!.originalFilename,
                                    sizeBytes: entry.submission!.sizeBytes,
                                    version: entry.submission!.version,
                                    uploadedAt: entry.submission!.uploadedAt,
                                    uploadedByName: entry.submission!.uploadedByName,
                                    issuedOn: entry.submission!.issuedOn,
                                    expiresOn: entry.submission!.expiresOn,
                                    // The server refuses if this reviewer uploaded
                                    // it, with a message worth reading.
                                    reviewableByCaller: true,
                                  })
                                }
                              >
                                Review
                              </Button>
                            ) : null}
                          </Stack>
                        </Stack>
                      )
                    })}
                </Stack>
              ) : (
                <CircularProgress size={20} />
              )}
            </CardContent>
          </Card>
        </Stack>

        <Stack spacing={3} sx={{ flex: 1, minWidth: 0, width: '100%' }}>
          <Card>
            <CardContent>
              <Typography variant="overline" component="div" sx={{ mb: 2 }}>
                People at this supplier
              </Typography>

              {/* Supplier user management, deliberately not the same screen as
                  Acme staff administration: different population, different
                  role, different audit trail. */}
              {users.data && users.data.length > 0 ? (
                <Stack spacing={1.75}>
                  {users.data.map((user) => (
                    <Stack
                      key={user.id}
                      direction="row"
                      spacing={1}
                      sx={{ justifyContent: 'space-between', alignItems: 'flex-start' }}
                    >
                      <Box sx={{ minWidth: 0 }}>
                        <Typography variant="body2" sx={{ fontWeight: 600 }}>
                          {user.fullName}
                        </Typography>
                        <Typography variant="caption" color="text.secondary" component="div">
                          {user.email} · {user.status.toLowerCase()} · last signed in{' '}
                          {user.lastLoginAt ? formatDateTime(user.lastLoginAt) : 'never'}
                        </Typography>
                      </Box>
                      {canReview ? (
                        <Button
                          size="small"
                          color={user.status === 'DEACTIVATED' ? 'primary' : 'error'}
                          disabled={supplierUsers.setStatus.isPending}
                          onClick={() =>
                            supplierUsers.setStatus.mutate({
                              userId: user.id,
                              active: user.status === 'DEACTIVATED',
                            })
                          }
                        >
                          {user.status === 'DEACTIVATED' ? 'Restore' : 'Remove'}
                        </Button>
                      ) : null}
                    </Stack>
                  ))}
                </Stack>
              ) : (
                <Typography variant="body2" color="text.secondary">
                  Nobody has been invited yet.
                </Typography>
              )}

              {supplierUsers.setStatus.error instanceof RequestFailed ? (
                <Alert severity="error" sx={{ mt: 2 }}>
                  {supplierUsers.setStatus.error.message}
                </Alert>
              ) : null}

              {canReview ? (
                <Button size="small" sx={{ mt: 2 }} onClick={() => setInvitingUser(true)}>
                  Invite someone here
                </Button>
              ) : null}
            </CardContent>
          </Card>

          <Card>
            <CardContent>
              <Typography variant="overline" component="div" sx={{ mb: 2 }}>
                Activity
              </Typography>
              {activity.data && activity.data.length > 0 ? (
                <Stack spacing={1.75}>
                  {activity.data.slice(0, 25).map((event) => (
                    <Box key={event.id}>
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>
                        {actionLabel(event.action)}
                      </Typography>
                      <Typography variant="caption" color="text.secondary" component="div">
                        {event.actorLabel} · {formatDateTime(event.occurredAt)}
                      </Typography>
                    </Box>
                  ))}
                </Stack>
              ) : (
                <EmptyState
                  title="Nothing yet"
                  description="Every state change lands here, with who made it and when."
                />
              )}
            </CardContent>
          </Card>

          <Typography variant="caption" color="text.secondary">
            Record created {formatDate(profile.updatedAt.slice(0, 10))}. Suppliers are deactivated,
            never deleted — statutory retention outlasts the relationship.
          </Typography>
        </Stack>
      </Stack>

      {reviewing ? <ReviewDialog target={reviewing} open onClose={() => setReviewing(null)} /> : null}
      {invitingUser && id ? (
        <InviteSupplierUserDialog supplierId={id} onClose={() => setInvitingUser(false)} />
      ) : null}
    </>
  )
}

/**
 * Invites another person at the supplier's own company.
 *
 * Ops operates this, scoped to the one supplier whose record they are looking
 * at. It cannot create an Acme account: the role is fixed by the endpoint, not
 * chosen here.
 */
function InviteSupplierUserDialog({
  supplierId,
  onClose,
}: {
  supplierId: string
  onClose: () => void
}) {
  const { invite } = useSupplierUserAdministration(supplierId)
  const [email, setEmail] = useState('')
  const [fullName, setFullName] = useState('')

  const failure = invite.error instanceof RequestFailed ? invite.error.message : null

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    await invite.mutateAsync({ email, fullName })
    onClose()
  }

  return (
    <Dialog open onClose={onClose}>
      <form onSubmit={submit}>
        <DialogTitle>Invite someone at this supplier</DialogTitle>
        <DialogContent dividers>
          <Stack spacing={2.5}>
            {failure ? <Alert severity="error">{failure}</Alert> : null}
            <TextField
              label="Name"
              required
              fullWidth
              value={fullName}
              onChange={(event) => setFullName(event.target.value)}
            />
            <TextField
              label="Email address"
              type="email"
              required
              fullWidth
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              helperText="They get their own sign-in for this company only. The invitation expires in seven days."
            />
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
