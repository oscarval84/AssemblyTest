import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CircularProgress from '@mui/material/CircularProgress'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import { useState } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import type { SupplierSummary } from '../../api/client'
import { useSession, useSuppliers } from '../../api/queries'
import { EmptyState, PageHeader, StatusChip } from '../../components/common'
import { formatDate } from '../../lib/format'
import { complianceLook, stageLabel, waitingOnColor, waitingOnLabel } from '../../lib/labels'
import NewSupplierDialog from './NewSupplierDialog'

/**
 * The pipeline: every supplier, their stage, and what each is blocked on.
 *
 * "Waiting on Acme" is the first column that matters, because it is the part
 * Marcus's team controls and is measured on. The blocker itself is derived on
 * every read — a stored one drifts the moment a document is approved or expires,
 * and silently, which is how today's spreadsheet ends up disagreeing with reality.
 */
export default function SuppliersPage() {
  const session = useSession()
  const suppliers = useSuppliers()
  const [inviting, setInviting] = useState(false)

  const canInvite = session.data?.role === 'OPS' || session.data?.role === 'ADMIN'

  if (suppliers.isPending) {
    return (
      <Stack sx={{ alignItems: 'center', py: 10 }}>
        <CircularProgress size={28} />
      </Stack>
    )
  }

  if (suppliers.isError) {
    return <Alert severity="error">We could not load the pipeline. Refresh and try again.</Alert>
  }

  const rows = suppliers.data
  const waitingOnAcme = rows.filter((row) => row.blocker.waitingOn === 'ACME')
  const waitingOnSupplier = rows.filter((row) => row.blocker.waitingOn === 'SUPPLIER')
  const clear = rows.filter((row) => row.blocker.waitingOn === 'NOBODY')

  return (
    <>
      <PageHeader
        title="Supplier pipeline"
        description="Every supplier being onboarded, grouped by who has to act next."
        actions={
          canInvite ? (
            <Button variant="contained" onClick={() => setInviting(true)}>
              Invite a supplier
            </Button>
          ) : null
        }
      />

      {rows.length === 0 ? (
        <EmptyState
          title="No suppliers yet"
          description="Onboarding starts either from a VMS assignment or from an invitation you send here. Invite the first company and the pipeline fills in as they progress."
          action={
            canInvite ? (
              <Button variant="contained" onClick={() => setInviting(true)}>
                Invite a supplier
              </Button>
            ) : null
          }
        />
      ) : (
        <Stack spacing={4}>
          <PipelineGroup
            title="Waiting on Acme"
            description="Our queue. These are the ones our cycle time is measured on."
            rows={waitingOnAcme}
            emptyMessage="Nothing is sitting with us right now."
          />
          <PipelineGroup
            title="Waiting on the supplier"
            description="Sent, chased, or not started. Each row says exactly what is missing."
            rows={waitingOnSupplier}
            emptyMessage="Every supplier has done their part."
          />
          <PipelineGroup
            title="Clear"
            description="Approved and compliant today."
            rows={clear}
            emptyMessage="No supplier is fully approved yet."
          />
        </Stack>
      )}

      {inviting ? <NewSupplierDialog open onClose={() => setInviting(false)} /> : null}
    </>
  )
}

function PipelineGroup({
  title,
  description,
  rows,
  emptyMessage,
}: {
  title: string
  description: string
  rows: SupplierSummary[]
  emptyMessage: string
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
          {emptyMessage}
        </Typography>
      ) : (
        // The pipeline carries six columns; on a laptop split-screen it scrolls
        // sideways rather than crushing the supplier name to three characters.
        <Card sx={{ overflowX: 'auto' }}>
          <Table size="small" sx={{ minWidth: 900 }}>
            <TableHead>
              <TableRow>
                <TableCell>Supplier</TableCell>
                <TableCell>Stage</TableCell>
                <TableCell>Blocked on</TableCell>
                <TableCell>Programs</TableCell>
                <TableCell>Compliance</TableCell>
                <TableCell>Next expiry</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((row) => {
                const look = complianceLook(row.complianceStatus)
                return (
                  <TableRow key={row.id} hover>
                    <TableCell>
                      <Typography
                        component={RouterLink}
                        to={`/ops/suppliers/${row.id}`}
                        variant="body2"
                        sx={{ fontWeight: 600, color: 'primary.main', textDecoration: 'none' }}
                      >
                        {row.legalName}
                      </Typography>
                      <Typography variant="caption" color="text.secondary" component="div">
                        {row.primaryContactEmail}
                      </Typography>
                    </TableCell>
                    <TableCell>{stageLabel(row.stage)}</TableCell>
                    <TableCell>
                      <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                        <StatusChip
                          label={waitingOnLabel(row.blocker.waitingOn)}
                          color={waitingOnColor(row.blocker.waitingOn)}
                        />
                        <Typography variant="body2">{row.blocker.summary}</Typography>
                      </Stack>
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2">{row.programNames.join(', ') || '—'}</Typography>
                    </TableCell>
                    <TableCell>
                      <StatusChip label={look.label} color={look.color} />
                    </TableCell>
                    <TableCell className="tabular">{formatDate(row.nextExpiry)}</TableCell>
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
