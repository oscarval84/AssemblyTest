import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { RequestFailed } from '../../api/client'
import { useApplyExtractedExpiry, useExtractFields, useExtraction } from '../../api/queries'
import { Field } from '../../components/common'
import { formatDate, formatMoney } from '../../lib/format'

/**
 * What the certificate says, next to what was claimed about it.
 *
 * The supplier types the expiry date at upload and the whole compliance engine
 * runs on it. Nobody checked it against the document — and a date wrong by two
 * months is the shape of the failure that let a supplier work on a lapsed
 * certificate twice. So this panel's job is the disagreement, not the fields:
 * the reading is only interesting where it contradicts something.
 *
 * Nothing here decides anything. Correcting the expiry is one deliberate click
 * by a reviewer who has both dates in front of them.
 */
export default function CertificateFields({ submissionId }: { submissionId: string }) {
  const extraction = useExtraction(submissionId)
  const extract = useExtractFields(submissionId)
  const applyExpiry = useApplyExtractedExpiry(submissionId)

  if (extraction.isPending) {
    return (
      <Stack sx={{ alignItems: 'center', py: 2 }}>
        <CircularProgress size={20} />
      </Stack>
    )
  }

  if (extraction.isError || !extraction.data) return null

  const view = extraction.data
  // Not a certificate, or a document that may never be sent to a model. Saying
  // nothing beats explaining an absence nobody asked about.
  if (!view.available && !view.fields) return null

  const failure =
    extract.error instanceof RequestFailed
      ? extract.error.message
      : applyExpiry.error instanceof RequestFailed
        ? applyExpiry.error.message
        : null

  return (
    <Box>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'baseline', mb: 1 }}>
        <Typography variant="overline" component="div" sx={{ flexGrow: 1 }}>
          What the certificate says
        </Typography>
        {view.available ? (
          <Button size="small" onClick={() => extract.mutate()} disabled={extract.isPending}>
            {extract.isPending ? 'Reading…' : view.fields ? 'Read it again' : 'Read the fields'}
          </Button>
        ) : null}
      </Stack>

      {failure ? (
        <Alert severity="warning" variant="outlined" sx={{ mb: 1.5 }}>
          {failure}
        </Alert>
      ) : null}

      {!view.fields ? (
        <Typography variant="body2" color="text.secondary">
          Nothing read yet. This checks the certificate against the expiry date entered on upload and
          against what the program requires — it does not decide anything.
        </Typography>
      ) : (
        <Stack spacing={1.5}>
          {view.findings.length === 0 ? (
            <Alert severity="success" variant="outlined">
              Nothing disagrees: the dates, the limits and the names on the certificate match what is
              on file.
            </Alert>
          ) : (
            view.findings.map((finding) => (
              <Alert key={finding.flag} severity="warning" variant="outlined">
                {finding.detail}
                {finding.flag === 'EXPIRY_MISMATCH' ? (
                  <Box sx={{ mt: 1 }}>
                    <Button
                      size="small"
                      variant="outlined"
                      disabled={applyExpiry.isPending}
                      onClick={() => applyExpiry.mutate()}
                    >
                      Use the certificate's date
                    </Button>
                    <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
                      Compliance runs on this date. Changing it is recorded with both values.
                    </Typography>
                  </Box>
                ) : null}
              </Alert>
            ))
          )}

          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)' },
              gap: 1.5,
            }}
          >
            <Field label="Insurer">{view.fields.insurer}</Field>
            <Field label="Policy">{view.fields.policyNumber}</Field>
            <Field label="Insured">{view.fields.namedInsured}</Field>
            <Field label="Certificate holder">{view.fields.certificateHolder}</Field>
            <Field label="Each occurrence">
              {view.fields.generalLiabilityEachOccurrence != null
                ? formatMoney(view.fields.generalLiabilityEachOccurrence)
                : null}
            </Field>
            <Field label="Aggregate">
              {view.fields.generalLiabilityAggregate != null
                ? formatMoney(view.fields.generalLiabilityAggregate)
                : null}
            </Field>
            <Field label="Effective">{formatDate(view.fields.effectiveOn)}</Field>
            <Field label="Expires">{formatDate(view.fields.expiresOn)}</Field>
          </Box>

          <Typography variant="caption" color="text.secondary">
            Read by {view.model}
            {view.confidence != null ? ` · ${Math.round(view.confidence * 100)}% confident` : ''}. A
            blank field means the certificate does not say, or that part of the scan is not legible —
            read it yourself before trusting the gap.
          </Typography>
        </Stack>
      )}
    </Box>
  )
}
