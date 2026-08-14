import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CircularProgress from '@mui/material/CircularProgress'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import { Link as RouterLink } from 'react-router-dom'
import type { ExpiringDocument } from '../../api/client'
import { useExpirations } from '../../api/queries'
import { EmptyState, PageHeader, StatusChip } from '../../components/common'
import { daysUntil, formatDate } from '../../lib/format'
import { compliance } from '../../theme/theme'

/**
 * What is about to lapse, and what already has.
 *
 * This is the screen behind the client's most expensive failure: twice, a
 * supplier worked on an expired certificate because nobody was watching the
 * dates. The nightly sweep emails the supplier; this is the same information for
 * the person who has to chase them when the email does not work.
 */
export default function ExpirationsPage() {
  const expirations = useExpirations(60)

  if (expirations.isPending) {
    return (
      <Stack sx={{ alignItems: 'center', py: 10 }}>
        <CircularProgress size={28} />
      </Stack>
    )
  }

  if (expirations.isError) {
    return <Alert severity="error">We could not load upcoming expirations. Refresh and try again.</Alert>
  }

  const documents = expirations.data
  const expired = documents.filter((d) => (daysUntil(d.expiresOn) ?? 0) < 0)
  const thisMonth = documents.filter((d) => {
    const days = daysUntil(d.expiresOn) ?? 0
    return days >= 0 && days <= 30
  })
  const later = documents.filter((d) => (daysUntil(d.expiresOn) ?? 0) > 30)

  return (
    <>
      <PageHeader
        title="Expiring documents"
        description="Insurance and attestations lapse. Suppliers are reminded automatically at 30 days, at 7 days and the morning after — this is the same list, for when a reminder is not enough."
      />

      {documents.length === 0 ? (
        <EmptyState
          title="Nothing expires in the next 60 days"
          description="Every certificate and attestation on file is current with room to spare. Documents appear here as they come within two months of their expiry date."
        />
      ) : (
        <Stack spacing={4}>
          <Group
            title="Already expired"
            description="These suppliers are non-compliant today, and their onboarding has reopened."
            tone={compliance.nonCompliant}
            rows={expired}
          />
          <Group
            title="Within 30 days"
            description="Inside the warning band. The supplier has been told; a renewal has not arrived."
            tone={compliance.expiring}
            rows={thisMonth}
          />
          <Group
            title="Within 60 days"
            description="Not urgent. Useful when you are already talking to the supplier about something else."
            tone={compliance.inReview}
            rows={later}
          />
        </Stack>
      )}
    </>
  )
}

function Group({
  title,
  description,
  tone,
  rows,
}: {
  title: string
  description: string
  tone: string
  rows: ExpiringDocument[]
}) {
  return (
    <Box component="section">
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'baseline', mb: 0.5 }}>
        <Typography variant="h2">{title}</Typography>
        <Typography variant="body2" color="text.secondary" className="tabular">
          {rows.length}
        </Typography>
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        {description}
      </Typography>

      {rows.length === 0 ? (
        <Typography variant="body2" color="text.secondary" sx={{ fontStyle: 'italic' }}>
          Nothing here.
        </Typography>
      ) : (
        <Card sx={{ overflowX: 'auto' }}>
          <Table size="small" sx={{ minWidth: 840 }}>
            <TableHead>
              <TableRow>
                <TableCell>Expires</TableCell>
                <TableCell>Supplier</TableCell>
                <TableCell>Document</TableCell>
                <TableCell>Programs</TableCell>
                <TableCell>Contact</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((row) => {
                const days = daysUntil(row.expiresOn) ?? 0
                return (
                  <TableRow key={row.submissionId} hover>
                    <TableCell className="tabular">
                      <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                        <StatusChip
                          label={days < 0 ? `${Math.abs(days)}d ago` : days === 0 ? 'today' : `${days}d`}
                          color={tone}
                        />
                        <Typography variant="body2">{formatDate(row.expiresOn)}</Typography>
                      </Stack>
                    </TableCell>
                    <TableCell>
                      <Typography
                        component={RouterLink}
                        to={`/ops/suppliers/${row.supplierId}`}
                        variant="body2"
                        sx={{ fontWeight: 600, color: 'primary.main', textDecoration: 'none' }}
                      >
                        {row.supplierLegalName}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2">{row.documentTypeName}</Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2">{row.programNames.join(', ') || '—'}</Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption" color="text.secondary">
                        {row.contactEmail ?? 'No contact on file'}
                      </Typography>
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </Card>
      )}
    </Box>
  )
}
