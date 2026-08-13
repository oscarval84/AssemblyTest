import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import CircularProgress from '@mui/material/CircularProgress'
import Divider from '@mui/material/Divider'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { Link as RouterLink, useParams } from 'react-router-dom'
import {
  documentDownloadUrl,
  useActivity,
  useChecklist,
  useSupplier,
  useSupplierUsers,
} from '../../api/queries'
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
  const supplier = useSupplier(id)
  const checklist = useChecklist(id)
  const activity = useActivity(id)
  const users = useSupplierUsers(id)

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
                          <StatusChip label={entryLook.label} color={entryLook.color} />
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
              {users.data && users.data.length > 0 ? (
                <Stack spacing={1.5}>
                  {users.data.map((user) => (
                    <Box key={user.id}>
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>
                        {user.fullName}
                      </Typography>
                      <Typography variant="caption" color="text.secondary" component="div">
                        {user.email} · {user.status.toLowerCase()} · last signed in{' '}
                        {user.lastLoginAt ? formatDateTime(user.lastLoginAt) : 'never'}
                      </Typography>
                    </Box>
                  ))}
                </Stack>
              ) : (
                <Typography variant="body2" color="text.secondary">
                  Nobody has been invited yet.
                </Typography>
              )}
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
    </>
  )
}
