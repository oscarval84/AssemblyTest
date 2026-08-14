import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import CircularProgress from '@mui/material/CircularProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useState } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import { RequestFailed, type IntegrationMessage } from '../../api/client'
import { useIntegrationMessages, useRetryIntegrationMessage } from '../../api/queries'
import { EmptyState, PageHeader, StatusChip } from '../../components/common'
import { formatDateTime } from '../../lib/format'
import { compliance } from '../../theme/theme'

const STATUS_COLOURS: Record<string, string> = {
  SENT: compliance.compliant,
  RECEIVED: compliance.compliant,
  PENDING: compliance.idle,
  FAILED: compliance.expiring,
  DEAD_LETTER: compliance.nonCompliant,
}

/**
 * Every pull and every push, with the payload and a retry.
 *
 * A silently failing integration is worse than no integration, because everyone
 * downstream believes the VMS is current. The dead-letter banner is the point of
 * this screen: it makes the failure loud, and it answers the question Dana's
 * audit story needs — when was the VMS told, and what did we send.
 */
export default function IntegrationsPage() {
  const messages = useIntegrationMessages()
  const retry = useRetryIntegrationMessage()
  const [expanded, setExpanded] = useState<string | null>(null)

  if (messages.isPending) {
    return (
      <Stack sx={{ alignItems: 'center', py: 10 }}>
        <CircularProgress size={28} />
      </Stack>
    )
  }

  if (messages.isError) {
    return <Alert severity="error">We could not load the integration log. Refresh and try again.</Alert>
  }

  const rows = messages.data
  const deadLettered = rows.filter((row) => row.status === 'DEAD_LETTER')
  const failure = retry.error instanceof RequestFailed ? retry.error.message : null

  return (
    <>
      <PageHeader
        title="VMS integration"
        description="Assignments pull in from the VMS and start onboarding on their own; outcomes are written back to it. Every exchange in both directions is here."
      />

      {deadLettered.length > 0 ? (
        <Alert severity="error" sx={{ mb: 3 }}>
          <AlertTitle>
            {deadLettered.length === 1
              ? 'One update never reached the VMS'
              : `${deadLettered.length} updates never reached the VMS`}
          </AlertTitle>
          They were retried until they ran out of attempts and then stopped, rather than looping
          quietly. Until they are sent, the VMS is out of date about those suppliers — open one below
          and retry it once the far side is healthy.
        </Alert>
      ) : null}

      {failure ? (
        <Alert severity="error" sx={{ mb: 3 }}>
          {failure}
        </Alert>
      ) : null}

      {rows.length === 0 ? (
        <EmptyState
          title="Nothing exchanged yet"
          description="The sync runs on a schedule. Each run lands here — including the runs where nothing had changed, because knowing the integration is alive is half of what this screen is for."
        />
      ) : (
        <Stack spacing={1.5}>
          {rows.map((row) => {
            const open = expanded === row.id
            const inbound = row.direction === 'INBOUND'
            return (
              <Card key={row.id}>
                <CardContent onClick={() => setExpanded(open ? null : row.id)} sx={{ cursor: 'pointer' }}>
                  <Stack
                    direction={{ xs: 'column', sm: 'row' }}
                    spacing={2}
                    sx={{ justifyContent: 'space-between', alignItems: { sm: 'center' } }}
                  >
                    <Box sx={{ minWidth: 0 }}>
                      <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                        <Typography variant="body2" sx={{ fontWeight: 600 }}>
                          {inbound ? '← ' : '→ '}
                          {row.messageType.toLowerCase().replaceAll('_', ' ')}
                        </Typography>
                        <StatusChip
                          label={row.status.toLowerCase().replace('_', ' ')}
                          color={STATUS_COLOURS[row.status] ?? compliance.idle}
                        />
                      </Stack>
                      <Typography variant="caption" color="text.secondary" component="div">
                        {row.targetSystem}
                        {row.supplierLegalName ? ` · ${row.supplierLegalName}` : ''}
                        {row.externalRef ? ` · ${row.externalRef}` : ''} · {formatDateTime(row.createdAt)}
                        {row.attempts > 0 ? ` · ${row.attempts} attempt${row.attempts === 1 ? '' : 's'}` : ''}
                      </Typography>
                    </Box>

                    {row.status === 'DEAD_LETTER' || row.status === 'FAILED' ? (
                      <Button
                        size="small"
                        variant="contained"
                        disabled={retry.isPending}
                        onClick={(event) => {
                          event.stopPropagation()
                          retry.mutate(row.id)
                        }}
                      >
                        Retry
                      </Button>
                    ) : null}
                  </Stack>

                  {open ? (
                    <Box sx={{ mt: 2 }}>
                      {row.lastError ? (
                        <Alert severity="error" sx={{ mb: 2 }}>
                          {row.lastError}
                        </Alert>
                      ) : null}

                      <Box
                        sx={{
                          p: 2,
                          border: '1px solid',
                          borderColor: 'divider',
                          borderRadius: 1,
                          bgcolor: 'background.default',
                          overflowX: 'auto',
                        }}
                      >
                        <Typography component="pre" variant="caption" sx={{ m: 0 }}>
                          {prettyPayload(row)}
                        </Typography>
                      </Box>

                      {row.supplierId ? (
                        <Typography
                          component={RouterLink}
                          to={`/ops/suppliers/${row.supplierId}`}
                          variant="caption"
                          sx={{ display: 'inline-block', mt: 2, color: 'primary.main' }}
                        >
                          Open {row.supplierLegalName ?? 'the supplier'}
                        </Typography>
                      ) : null}
                    </Box>
                  ) : null}
                </CardContent>
              </Card>
            )
          })}
        </Stack>
      )}

      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 3 }}>
        Tax ID and bank account are classified Restricted and are never transmitted. A supplier
        invited here rather than pulled from the VMS carries no link and is never pushed as a new VMS
        record — this tool does not create records in someone else's system of record.
      </Typography>
    </>
  )
}

function prettyPayload(row: IntegrationMessage): string {
  try {
    return JSON.stringify(JSON.parse(row.payload), null, 2)
  } catch {
    return row.payload
  }
}
