import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CircularProgress from '@mui/material/CircularProgress'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import { useState } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import { documentDownloadUrl, useReviewQueue } from '../../api/queries'
import { EmptyState, PageHeader, StatusChip } from '../../components/common'
import { compliance } from '../../theme/theme'
import { formatDate, formatDateTime } from '../../lib/format'
import ReviewDialog, { type ReviewTarget } from './ReviewDialog'

/**
 * Marcus's morning: everything sitting with Acme, oldest first.
 *
 * Wait time is the first thing on the row because it is the number Acme is
 * measured on — three to six weeks per supplier is the problem this product was
 * bought to fix, and most of that time is spent here.
 */
export default function ReviewQueuePage() {
  const queue = useReviewQueue()
  const [reviewing, setReviewing] = useState<ReviewTarget | null>(null)

  if (queue.isPending) {
    return (
      <Stack sx={{ alignItems: 'center', py: 10 }}>
        <CircularProgress size={28} />
      </Stack>
    )
  }

  if (queue.isError) {
    return <Alert severity="error">We could not load the review queue. Refresh and try again.</Alert>
  }

  const items = queue.data
  const oldest = items.reduce((worst, item) => Math.max(worst, item.waitingDays), 0)

  return (
    <>
      <PageHeader
        title="Review queue"
        description="Documents suppliers have sent that nobody at Acme has ruled on yet."
      />

      {items.length === 0 ? (
        <EmptyState
          title="Nothing is waiting on us"
          description="Every document a supplier has sent has been reviewed. New submissions land here as soon as they arrive, oldest first."
          action={
            <Button component={RouterLink} to="/ops" variant="outlined">
              Back to the pipeline
            </Button>
          }
        />
      ) : (
        <>
          <Stack direction="row" spacing={4} sx={{ mb: 3 }}>
            <Box>
              <Typography variant="h1" component="div" className="tabular">
                {items.length}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                {items.length === 1 ? 'document waiting' : 'documents waiting'}
              </Typography>
            </Box>
            <Box>
              <Typography variant="h1" component="div" className="tabular">
                {oldest}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                {oldest === 1 ? 'day, the longest wait' : 'days, the longest wait'}
              </Typography>
            </Box>
          </Stack>

          <Card sx={{ overflowX: 'auto' }}>
            <Table size="small" sx={{ minWidth: 980 }}>
              <TableHead>
                <TableRow>
                  <TableCell>Waiting</TableCell>
                  <TableCell>Supplier</TableCell>
                  <TableCell>Document</TableCell>
                  <TableCell>Programs</TableCell>
                  <TableCell>Expires</TableCell>
                  <TableCell>Sent</TableCell>
                  <TableCell align="right">Decision</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {items.map((item) => (
                  <TableRow key={item.submissionId} hover>
                    <TableCell className="tabular">
                      <StatusChip
                        label={item.waitingDays === 0 ? 'today' : `${item.waitingDays}d`}
                        color={item.waitingDays >= 3 ? compliance.expiring : compliance.inReview}
                      />
                    </TableCell>
                    <TableCell>
                      <Typography
                        component={RouterLink}
                        to={`/ops/suppliers/${item.supplierId}`}
                        variant="body2"
                        sx={{ fontWeight: 600, color: 'primary.main', textDecoration: 'none' }}
                      >
                        {item.supplierLegalName}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>
                        {item.documentTypeName}
                      </Typography>
                      <Typography variant="caption" color="text.secondary" component="div">
                        <Link href={documentDownloadUrl(item.submissionId)} target="_blank" rel="noopener">
                          {item.originalFilename}
                        </Link>{' '}
                        · v{item.version} · {item.classification.toLowerCase()}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2">{item.programNames.join(', ') || '—'}</Typography>
                    </TableCell>
                    <TableCell className="tabular">
                      {item.expiresOn ? formatDate(item.expiresOn) : '—'}
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption" color="text.secondary">
                        {formatDateTime(item.uploadedAt)}
                        {item.uploadedByName ? ` · ${item.uploadedByName}` : ''}
                      </Typography>
                    </TableCell>
                    <TableCell align="right">
                      <Button
                        size="small"
                        variant={item.reviewableByCaller ? 'contained' : 'outlined'}
                        onClick={() => setReviewing(item as ReviewTarget)}
                      >
                        {item.reviewableByCaller ? 'Review' : 'Open'}
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Card>
        </>
      )}

      {reviewing ? <ReviewDialog target={reviewing} open onClose={() => setReviewing(null)} /> : null}
    </>
  )
}
