import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import CircularProgress from '@mui/material/CircularProgress'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { RequestFailed } from '../../api/client'
import { useAuditExport, useChainVerification, usePrograms, useSession, useSuppliers } from '../../api/queries'
import { EmptyState, Field, PageHeader, StatusChip } from '../../components/common'
import { compliance } from '../../theme/theme'

const ANY = ''

/**
 * The history Dana hands to an auditor.
 *
 * The timeline on a supplier's record answers "what happened here" while you
 * are looking at it. This answers the question an audit actually asks, which is
 * narrower and colder: *every* recorded action, for these companies, in this
 * period, in a file that leaves the building. So the filters are the three an
 * auditor scopes a request by — a supplier, a program, a date range — and the
 * screen says plainly what a row contains before anyone downloads one.
 *
 * The verification below the filters is the other half. A history is only
 * evidence if the person handing it over can say it is whole, and that claim
 * should not require an engineer to check.
 */
export default function AuditExportPage() {
  const [params, setParams] = useSearchParams()
  const session = useSession()
  const suppliers = useSuppliers()
  const programs = usePrograms()
  const exportCsv = useAuditExport()

  // A program manager's "everything" is their programs, and the server enforces
  // that. Saying so here is the difference between a scoped file and one they
  // hand on believing it covers the whole company.
  const scoped = session.data?.role === 'PROGRAM_MANAGER'
  const everySupplier = scoped ? 'Every supplier in your programs' : 'Every supplier'

  // The filter lives in the URL so a pre-filtered link from a supplier's record
  // arrives here with the supplier already chosen, and so a colleague can be
  // sent the exact scope rather than a description of it.
  const supplierId = params.get('supplierId') ?? ANY
  const programId = params.get('programId') ?? ANY
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')

  const verification = useChainVerification(supplierId || undefined)
  const backwards = Boolean(from && to && from > to)
  const failure = exportCsv.error instanceof RequestFailed ? exportCsv.error.message : null

  function download(format: 'csv' | 'pdf') {
    exportCsv.mutate({
      supplierId: supplierId || null,
      programId: programId || null,
      from: from || null,
      to: to || null,
      format,
    })
  }

  function setFilter(key: string, value: string) {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    setParams(next, { replace: true })
  }

  if (suppliers.isPending) {
    return (
      <Stack sx={{ alignItems: 'center', py: 10 }}>
        <CircularProgress size={28} />
      </Stack>
    )
  }

  if (suppliers.isError) {
    return <Alert severity="error">We could not load the supplier list. Refresh and try again.</Alert>
  }

  if (suppliers.data.length === 0) {
    return (
      <>
        <PageHeader title="Audit export" description={INTRO} />
        <EmptyState
          title="There is nothing to export yet"
          description="The log fills itself as work happens: the first invitation, every upload, every approval and every rejection. Invite a supplier and this becomes their history."
        />
      </>
    )
  }

  const supplier = suppliers.data.find((candidate) => candidate.id === supplierId)

  return (
    <>
      <PageHeader title="Audit export" description={INTRO} />

      <Stack direction={{ xs: 'column', lg: 'row' }} spacing={3} sx={{ alignItems: 'flex-start' }}>
        <Card sx={{ flex: 2, minWidth: 0, width: '100%' }}>
          <CardContent>
            <Typography variant="overline" component="div" sx={{ mb: 2 }}>
              What to include
            </Typography>

            <Stack spacing={2.5}>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <TextField
                  select
                  fullWidth
                  label="Supplier"
                  value={supplierId}
                  onChange={(event) => setFilter('supplierId', event.target.value)}
                  // The default is a real choice — "every supplier" — not an
                  // unanswered question. Without both of these the field renders
                  // blank, and a blank filter reads as one nobody has filled in.
                  slotProps={{ inputLabel: { shrink: true }, select: { displayEmpty: true } }}
                >
                  <MenuItem value={ANY}>{everySupplier}</MenuItem>
                  {suppliers.data.map((option) => (
                    <MenuItem key={option.id} value={option.id}>
                      {option.legalName}
                    </MenuItem>
                  ))}
                </TextField>

                <TextField
                  select
                  fullWidth
                  label="Program"
                  value={programId}
                  onChange={(event) => setFilter('programId', event.target.value)}
                  helperText="A program covers every supplier enrolled in it."
                  slotProps={{ inputLabel: { shrink: true }, select: { displayEmpty: true } }}
                >
                  <MenuItem value={ANY}>{scoped ? 'Every program you manage' : 'Every program'}</MenuItem>
                  {(programs.data ?? []).map((option) => (
                    <MenuItem key={option.id} value={option.id}>
                      {option.name}
                    </MenuItem>
                  ))}
                </TextField>
              </Stack>

              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <TextField
                  fullWidth
                  type="date"
                  label="From"
                  value={from}
                  onChange={(event) => setFrom(event.target.value)}
                  slotProps={{ inputLabel: { shrink: true } }}
                  helperText="Leave both dates empty for the whole history."
                />
                <TextField
                  fullWidth
                  type="date"
                  label="To"
                  value={to}
                  onChange={(event) => setTo(event.target.value)}
                  slotProps={{ inputLabel: { shrink: true } }}
                  error={backwards}
                  helperText={
                    backwards
                      ? 'The end date is before the start date.'
                      : 'Both ends included, in Acme’s time zone.'
                  }
                />
              </Stack>

              {failure ? <Alert severity="error">{failure}</Alert> : null}

              <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
                <Button
                  variant="contained"
                  disabled={backwards || exportCsv.isPending}
                  onClick={() => download('csv')}
                >
                  {exportCsv.isPending ? 'Preparing…' : 'Download CSV'}
                </Button>

                {/* Same events, different recipient: the CSV gets filtered and
                    pivoted, the PDF gets attached to an audit response. */}
                <Button
                  variant="outlined"
                  disabled={backwards || exportCsv.isPending}
                  onClick={() => download('pdf')}
                >
                  Download PDF
                </Button>

                <Typography variant="body2" color="text.secondary">
                  {scopeSentence({
                    supplierName: supplier?.legalName,
                    programName: programs.data?.find((option) => option.id === programId)?.name,
                    everySupplier,
                    from,
                    to,
                  })}
                </Typography>
              </Stack>

              {exportCsv.isSuccess ? (
                <Typography variant="caption" color="text.secondary">
                  {exportCsv.data === 0
                    ? 'Nothing happened in that range — the file has its column headings and no rows.'
                    : `${exportCsv.data} ${exportCsv.data === 1 ? 'event' : 'events'} downloaded. Taking a copy is itself recorded in the log.`}
                </Typography>
              ) : null}
            </Stack>
          </CardContent>
        </Card>

        <Stack spacing={3} sx={{ flex: 1, minWidth: 0, width: '100%' }}>
          <Card>
            <CardContent>
              <Typography variant="overline" component="div" sx={{ mb: 1.5 }}>
                What a row contains
              </Typography>
              <Stack spacing={1.25}>
                <Field label="When">The exact moment, in UTC, to the millisecond.</Field>
                <Field label="Who and what">
                  The person or job that acted, the action, and the record it acted on.
                </Field>
                <Field label="Before and after">
                  The values that changed. Tax IDs and bank details never appear — the log records
                  that they were set, never what they were set to.
                </Field>
                <Field label="Proof">
                  The chain key, the position in the chain and the event’s hash, so the file can be
                  checked against the system it came from.
                </Field>
              </Stack>
            </CardContent>
          </Card>

          <Card>
            <CardContent>
              <Typography variant="overline" component="div" sx={{ mb: 1.5 }}>
                Chain integrity
              </Typography>

              {!supplierId ? (
                <Typography variant="body2" color="text.secondary">
                  Every supplier has their own hash-chained history. Choose one above to walk its
                  chain and confirm nothing has been altered or removed.
                </Typography>
              ) : verification.isPending ? (
                <CircularProgress size={20} />
              ) : verification.isError ? (
                <Typography variant="body2" color="text.secondary">
                  We could not check that chain just now.
                </Typography>
              ) : verification.data.intact ? (
                <Box>
                  <StatusChip label="Unbroken" color={compliance.compliant} />
                  <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                    {verification.data.eventCount}{' '}
                    {verification.data.eventCount === 1 ? 'event' : 'events'}, each carrying the hash
                    of the one before it, from the first to the last with no gaps.
                  </Typography>
                </Box>
              ) : (
                <Box>
                  <StatusChip label="Broken" color={compliance.nonCompliant} />
                  <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                    The chain stops matching at event {verification.data.brokenAtSequence}. This
                    record has been altered outside the application — treat the export as evidence
                    of that, and escalate it rather than sending it on.
                  </Typography>
                </Box>
              )}
            </CardContent>
          </Card>
        </Stack>
      </Stack>
    </>
  )
}

const INTRO =
  'Every recorded action, as a spreadsheet you can hand over. Nothing here is generated for the ' +
  'occasion: it is the same append-only log the product writes as work happens, filtered to what ' +
  'was asked for.'

/** Says back what is about to be downloaded, so nobody sends the wrong period. */
function scopeSentence(scope: {
  supplierName?: string
  programName?: string
  everySupplier: string
  from: string
  to: string
}): string {
  const who =
    scope.supplierName ??
    (scope.programName ? `every supplier in ${scope.programName}` : scope.everySupplier.toLowerCase())
  const when = scope.from && scope.to
    ? `${scope.from} to ${scope.to}`
    : scope.from
      ? `since ${scope.from}`
      : scope.to
        ? `up to ${scope.to}`
        : 'the whole history'

  return `${who}, ${when}.`
}
