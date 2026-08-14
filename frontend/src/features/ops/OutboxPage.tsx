import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import CircularProgress from '@mui/material/CircularProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useState } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import { useOutbox } from '../../api/queries'
import { EmptyState, PageHeader, StatusChip } from '../../components/common'
import { formatDateTime } from '../../lib/format'
import { compliance } from '../../theme/theme'

/**
 * Every notification the system has queued, with the message as the recipient
 * received it.
 *
 * The outbox row is written in the same transaction as the state change that
 * caused it, so an email about a rejection cannot exist unless the rejection
 * committed. Showing it in the product is the other half of that guarantee: ops
 * can answer "was the supplier actually told, and what did it say" without
 * access to a mail server.
 */
export default function OutboxPage() {
  const outbox = useOutbox()
  const [expanded, setExpanded] = useState<string | null>(null)

  if (outbox.isPending) {
    return (
      <Stack sx={{ alignItems: 'center', py: 10 }}>
        <CircularProgress size={28} />
      </Stack>
    )
  }

  if (outbox.isError) {
    return <Alert severity="error">We could not load the notification log. Refresh and try again.</Alert>
  }

  const { transport, entries } = outbox.data

  return (
    <>
      <PageHeader
        title="Notifications"
        description={
          transport === 'outbox-only'
            ? 'Every message this system has produced. Delivery is switched off in this environment, so nothing has left the building — the record of what would have been sent is complete either way.'
            : 'Every message this system has produced, and whether it was delivered.'
        }
      />

      {entries.length === 0 ? (
        <EmptyState
          title="No notifications yet"
          description="Invitations, rejections and completion notices appear here the moment the change that caused them is committed."
        />
      ) : (
        <Stack spacing={1.5}>
          {entries.map((entry) => {
            const open = expanded === entry.id
            return (
              <Card key={entry.id}>
                <CardContent
                  onClick={() => setExpanded(open ? null : entry.id)}
                  sx={{ cursor: 'pointer' }}
                >
                  <Stack
                    direction={{ xs: 'column', sm: 'row' }}
                    spacing={2}
                    sx={{ justifyContent: 'space-between', alignItems: { sm: 'center' } }}
                  >
                    <Box sx={{ minWidth: 0 }}>
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>
                        {entry.subject}
                      </Typography>
                      <Typography variant="caption" color="text.secondary" component="div">
                        To {entry.recipientName ? `${entry.recipientName} <${entry.recipientEmail}>` : entry.recipientEmail}
                        {entry.supplierLegalName ? ` · ${entry.supplierLegalName}` : ''} ·{' '}
                        {formatDateTime(entry.createdAt)}
                      </Typography>
                    </Box>

                    <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                      <Typography variant="caption" color="text.secondary">
                        {entry.template.toLowerCase().replaceAll('_', ' ')}
                      </Typography>
                      <StatusChip
                        label={entry.status === 'SENT' ? 'sent' : entry.status === 'FAILED' ? 'failed' : 'queued'}
                        color={
                          entry.status === 'SENT'
                            ? compliance.compliant
                            : entry.status === 'FAILED'
                              ? compliance.nonCompliant
                              : compliance.idle
                        }
                      />
                    </Stack>
                  </Stack>

                  {open ? (
                    <Box
                      sx={{
                        mt: 2,
                        p: 2,
                        border: '1px solid',
                        borderColor: 'divider',
                        borderRadius: 1,
                        bgcolor: 'background.default',
                      }}
                    >
                      <Typography
                        component="pre"
                        variant="body2"
                        sx={{ whiteSpace: 'pre-wrap', fontFamily: 'inherit', m: 0 }}
                      >
                        {entry.bodyText}
                      </Typography>
                      {entry.supplierId ? (
                        <Typography
                          component={RouterLink}
                          to={`/ops/suppliers/${entry.supplierId}`}
                          variant="caption"
                          sx={{ display: 'inline-block', mt: 2, color: 'primary.main' }}
                        >
                          Open {entry.supplierLegalName ?? 'the supplier'}
                        </Typography>
                      ) : null}
                      {entry.lastError ? (
                        <Alert severity="error" sx={{ mt: 2 }}>
                          {entry.lastError}
                        </Alert>
                      ) : null}
                    </Box>
                  ) : null}
                </CardContent>
              </Card>
            )
          })}
        </Stack>
      )}
    </>
  )
}
