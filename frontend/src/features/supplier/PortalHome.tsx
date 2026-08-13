import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import CircularProgress from '@mui/material/CircularProgress'
import Divider from '@mui/material/Divider'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useState } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import { documentDownloadUrl } from '../../api/queries'
import { useChecklist, useSession } from '../../api/queries'
import type { ChecklistEntry, ProgramChecklist } from '../../api/client'
import { EmptyState, PageHeader, StatusChip } from '../../components/common'
import { formatDate, formatDateTime, formatExpiry } from '../../lib/format'
import { complianceLook, constraintLines, requirementLook } from '../../lib/labels'
import SignDialog from './SignDialog'
import UploadDialog from './UploadDialog'

/**
 * The supplier's whole world: what Acme has, what Acme still needs, and why.
 *
 * The split inside each program is the client's own description of the
 * experience — a dashboard of what is already on file, plus whatever is net-new.
 * Reused documents are shown rather than hidden: a supplier looking at an empty
 * checklist concludes the system lost their paperwork, and one who sees "W-9,
 * approved, nothing needed" learns that it remembers them.
 */
export default function PortalHome() {
  const session = useSession()
  const supplierId = session.data?.supplierId ?? undefined
  const checklist = useChecklist(supplierId)

  const [uploading, setUploading] = useState<ChecklistEntry | null>(null)
  const [signing, setSigning] = useState<ChecklistEntry | null>(null)

  if (checklist.isPending || !supplierId) {
    return (
      <Stack sx={{ alignItems: 'center', py: 10 }}>
        <CircularProgress size={28} />
      </Stack>
    )
  }

  if (checklist.isError) {
    return (
      <Alert severity="error">
        We could not load your checklist. Refresh the page, and tell your Acme contact if it keeps
        happening.
      </Alert>
    )
  }

  const view = checklist.data
  const outstanding = view.programs.flatMap((program) => program.neededForThisProgram)
  const waitingOnUs = outstanding.filter((entry) => entry.state === 'IN_REVIEW')
  const yourMove = outstanding.filter((entry) => entry.state !== 'IN_REVIEW')

  return (
    <>
      <PageHeader
        title={view.legalName}
        description={
          view.stage === 'APPROVED'
            ? 'You are approved to work with Acme. Keep an eye on anything with an expiry date below.'
            : 'Everything Acme needs from you, and where each item stands right now.'
        }
      />

      {!view.profileComplete ? (
        <Alert
          severity="warning"
          sx={{ mb: 3 }}
          action={
            <Button component={RouterLink} to="/portal/profile" size="small" variant="contained">
              Complete the profile
            </Button>
          }
        >
          <AlertTitle>Start with your company profile</AlertTitle>
          Your legal name, address and tax ID tell us who we are contracting with. Documents are
          reviewed against it, so it comes first.
        </Alert>
      ) : null}

      <Stack
        direction={{ xs: 'column', md: 'row' }}
        spacing={2}
        sx={{ mb: 4 }}
        divider={<Divider orientation="vertical" flexItem />}
      >
        <Summary
          value={String(yourMove.length)}
          label={yourMove.length === 1 ? 'item needs you' : 'items need you'}
        />
        <Summary
          value={String(waitingOnUs.length)}
          label={waitingOnUs.length === 1 ? 'item with Acme' : 'items with Acme'}
        />
        <Summary value={String(view.programs.length)} label={view.programs.length === 1 ? 'program' : 'programs'} />
      </Stack>

      {view.programs.length === 0 ? (
        <EmptyState
          title="No programs yet"
          description="Acme enrols you into a client program before asking for documents. Your contact at Acme will be in touch, and this page will fill in as soon as that happens."
        />
      ) : (
        <Stack spacing={4}>
          {view.programs.map((program) => (
            <ProgramSection
              key={program.enrollmentId}
              program={program}
              onUpload={setUploading}
              onSign={setSigning}
            />
          ))}
        </Stack>
      )}

      {uploading ? (
        <UploadDialog
          supplierId={supplierId}
          entry={uploading}
          open
          onClose={() => setUploading(null)}
        />
      ) : null}

      {signing ? (
        <SignDialog
          supplierId={supplierId}
          entry={signing}
          signerName={session.data?.fullName ?? ''}
          open
          onClose={() => setSigning(null)}
        />
      ) : null}
    </>
  )
}

function Summary({ value, label }: { value: string; label: string }) {
  return (
    <Box sx={{ minWidth: 140 }}>
      <Typography variant="h1" component="div" className="tabular">
        {value}
      </Typography>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
    </Box>
  )
}

function ProgramSection({
  program,
  onUpload,
  onSign,
}: {
  program: ProgramChecklist
  onUpload: (entry: ChecklistEntry) => void
  onSign: (entry: ChecklistEntry) => void
}) {
  const look = complianceLook(program.complianceStatus)

  return (
    <Box component="section">
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', mb: 0.5 }}>
        <Typography variant="h2">{program.programName}</Typography>
        <StatusChip label={look.label} color={look.color} />
      </Stack>
      {program.programDescription ? (
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {program.programDescription}
        </Typography>
      ) : null}

      <Stack spacing={3} sx={{ mt: 2 }}>
        <Box>
          <Typography variant="overline" component="div" sx={{ mb: 1 }}>
            Needed for this program
          </Typography>
          {program.neededForThisProgram.length === 0 ? (
            <Alert severity="success" variant="outlined">
              Everything for {program.programName} is in and approved. Nothing is waiting on you.
            </Alert>
          ) : (
            <Stack spacing={1.5}>
              {program.neededForThisProgram.map((entry) => (
                <EntryCard
                  key={`${entry.documentTypeCode}-${entry.enrollmentId ?? 'shared'}`}
                  entry={entry}
                  onUpload={onUpload}
                  onSign={onSign}
                />
              ))}
            </Stack>
          )}
        </Box>

        {program.alreadyOnFile.length > 0 ? (
          <Box>
            <Typography variant="overline" component="div" sx={{ mb: 1 }}>
              Already on file
            </Typography>
            <Stack spacing={1.5}>
              {program.alreadyOnFile.map((entry) => (
                <EntryCard
                  key={`${entry.documentTypeCode}-${entry.enrollmentId ?? 'shared'}`}
                  entry={entry}
                  onUpload={onUpload}
                  onSign={onSign}
                />
              ))}
            </Stack>
          </Box>
        ) : null}
      </Stack>
    </Box>
  )
}

function EntryCard({
  entry,
  onUpload,
  onSign,
}: {
  entry: ChecklistEntry
  onUpload: (entry: ChecklistEntry) => void
  onSign: (entry: ChecklistEntry) => void
}) {
  const look = requirementLook(entry.state)
  const constraints = constraintLines(entry)
  const submission = entry.submission

  const actionLabel =
    entry.state === 'NOT_STARTED'
      ? entry.requiresSignature
        ? 'Read and sign'
        : 'Upload'
      : entry.requiresSignature
        ? 'Sign again'
        : 'Replace'

  return (
    <Card>
      <CardContent>
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
         
         
          spacing={2} sx={{ alignItems: { xs: 'flex-start', sm: 'center' }, justifyContent: 'space-between' }}>
          <Box sx={{ flexGrow: 1 }}>
            <Stack direction="row" spacing={1.25} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
              <Typography variant="h3">{entry.documentTypeName}</Typography>
              <StatusChip label={look.label} color={look.color} />
              {entry.shared ? (
                <Typography variant="caption" color="text.secondary">
                  shared across your programs
                </Typography>
              ) : null}
            </Stack>

            {look.hint ? (
              <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                {look.hint}
              </Typography>
            ) : null}

            {constraints.map((line) => (
              <Typography key={line} variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                {line}
              </Typography>
            ))}

            {submission ? (
              <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 1 }}>
                <Link href={documentDownloadUrl(submission.id)} target="_blank" rel="noopener">
                  {submission.originalFilename}
                </Link>{' '}
                · version {submission.version} · sent {formatDateTime(submission.uploadedAt)}
                {submission.expiresOn ? ` · ${formatExpiry(submission.expiresOn)}` : ''}
                {submission.signedAt
                  ? ` · signed by ${submission.signedBy} on ${formatDate(submission.signedAt.slice(0, 10))}`
                  : ''}
              </Typography>
            ) : null}

            {entry.state === 'CHANGES_REQUESTED' && submission ? (
              <Alert severity="error" variant="outlined" sx={{ mt: 1.5 }}>
                <AlertTitle sx={{ fontSize: '0.85rem' }}>
                  {submission.rejectionReasonLabel ?? 'We could not accept this document'}
                </AlertTitle>
                {submission.rejectionNote ? (
                  <Typography variant="body2">{submission.rejectionNote}</Typography>
                ) : null}
                {submission.reviewedByName ? (
                  <Typography variant="caption" color="text.secondary">
                    Reviewed by {submission.reviewedByName} on {formatDateTime(submission.reviewedAt)}
                  </Typography>
                ) : null}
              </Alert>
            ) : null}
          </Box>

          <Button
            variant={entry.state === 'NOT_STARTED' || entry.state === 'CHANGES_REQUESTED' ? 'contained' : 'outlined'}
            onClick={() => (entry.requiresSignature ? onSign(entry) : onUpload(entry))}
          >
            {actionLabel}
          </Button>
        </Stack>
      </CardContent>
    </Card>
  )
}
