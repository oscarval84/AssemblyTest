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
 * What the document says, next to what was claimed about it.
 *
 * For a certificate, the supplier types the expiry date at upload and the whole
 * compliance engine runs on it. Nobody checked it against the document — and a
 * date wrong by two months is the shape of the failure that let a supplier work
 * on a lapsed certificate twice. So this panel's job is the disagreement, not
 * the fields: the reading is only interesting where it contradicts something.
 *
 * For a W-9 the disagreement is a different one — whether the form is filed
 * under the company Acme thinks it is onboarding — and it only appears at all
 * where Acme has turned that on.
 *
 * Nothing here decides anything. Correcting the expiry is one deliberate click
 * by a reviewer who has both dates in front of them.
 */
export default function ExtractedFields({ submissionId }: { submissionId: string }) {
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
  const read = view.coi ?? view.w9 ?? null
  // A document this never reads, or one nobody has read yet in an environment
  // with no model. Saying nothing beats explaining an absence nobody asked about.
  if (!view.available && !read) return null

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
          What the document says
        </Typography>
        {view.available ? (
          <Button size="small" onClick={() => extract.mutate()} disabled={extract.isPending}>
            {extract.isPending ? 'Reading…' : read ? 'Read it again' : 'Read the fields'}
          </Button>
        ) : null}
      </Stack>

      {failure ? (
        <Alert severity="warning" variant="outlined" sx={{ mb: 1.5 }}>
          {failure}
        </Alert>
      ) : null}

      {!read ? (
        <Typography variant="body2" color="text.secondary">
          Nothing read yet. This checks the document against what the supplier entered and against
          what the program requires — it does not decide anything.
        </Typography>
      ) : (
        <Stack spacing={1.5}>
          {view.findings.length === 0 ? (
            <Alert severity="success" variant="outlined">
              Nothing disagrees: what is printed on the document matches what is on file.
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
            {view.coi ? (
              <>
                <Field label="Insurer">{view.coi.insurer}</Field>
                <Field label="Policy">{view.coi.policyNumber}</Field>
                <Field label="Insured">{view.coi.namedInsured}</Field>
                <Field label="Certificate holder">{view.coi.certificateHolder}</Field>
                <Field label="Each occurrence">
                  {view.coi.generalLiabilityEachOccurrence != null
                    ? formatMoney(view.coi.generalLiabilityEachOccurrence)
                    : null}
                </Field>
                <Field label="Aggregate">
                  {view.coi.generalLiabilityAggregate != null
                    ? formatMoney(view.coi.generalLiabilityAggregate)
                    : null}
                </Field>
                <Field label="Effective">{formatDate(view.coi.effectiveOn)}</Field>
                <Field label="Expires">{formatDate(view.coi.expiresOn)}</Field>
              </>
            ) : null}

            {view.w9 ? (
              <>
                <Field label="Name on the form">{view.w9.legalName}</Field>
                <Field label="Business name">{view.w9.businessName}</Field>
                <Field label="Tax classification">{view.w9.taxClassification}</Field>
                <Field label="Address">{view.w9.address}</Field>
                <Field label="Signed">
                  {view.w9.signed == null ? null : view.w9.signed ? 'Yes' : 'No'}
                </Field>
              </>
            ) : null}
          </Box>

          {view.w9 ? (
            <Alert severity="info" variant="outlined">
              The taxpayer ID is not read off the form and is not stored here. It is on the
              supplier's profile, encrypted, with only the last four digits ever shown.
            </Alert>
          ) : null}

          <Typography variant="caption" color="text.secondary">
            Read by {view.model}
            {view.confidence != null ? ` · ${Math.round(view.confidence * 100)}% confident` : ''}. A
            blank field means the document does not say, or that part of the scan is not legible —
            read it yourself before trusting the gap.
          </Typography>
        </Stack>
      )}
    </Box>
  )
}
