import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import ButtonGroup from '@mui/material/ButtonGroup'
import CircularProgress from '@mui/material/CircularProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { RequestFailed } from '../../api/client'
import { useCriteriaChecklist, useJudgeCriterion, usePrefillCriteria } from '../../api/queries'
import { StatusChip } from '../../components/common'
import { compliance } from '../../theme/theme'

const VERDICTS = [
  { value: 'PASS', label: 'Pass', colour: compliance.compliant },
  { value: 'FAIL', label: 'Fail', colour: compliance.nonCompliant },
  { value: 'UNCLEAR', label: 'Unclear', colour: compliance.expiring },
] as const

/**
 * The criteria checklist, beside the document.
 *
 * A reviewer works down Acme's own list rather than reading a certificate line
 * by line against requirements they have to remember. Marking a criterion failed
 * does not reject anything — it makes the rejection *specific* if the reviewer
 * goes on to reject, which is the difference between one resubmission and three.
 */
export default function CriteriaChecklist({
  submissionId,
  onRejectWith,
}: {
  submissionId: string
  /** The criterion's own wording travels with its id: it becomes the reason. */
  onRejectWith: (criterionId: string, text: string) => void
}) {
  const checklist = useCriteriaChecklist(submissionId)
  const judge = useJudgeCriterion(submissionId)
  const prefill = usePrefillCriteria(submissionId)

  if (checklist.isPending) {
    return (
      <Stack sx={{ alignItems: 'center', py: 3 }}>
        <CircularProgress size={22} />
      </Stack>
    )
  }

  if (checklist.isError || !checklist.data) return null

  const view = checklist.data

  // A requirement with no criteria is normal rather than broken: review falls
  // back to a person reading the document, exactly as it works today.
  if (view.criteria.length === 0) {
    return (
      <Alert severity="info" variant="outlined">
        No acceptance criteria are set for this requirement yet, so this is a straight read. Adding
        them makes every future submission checkable against the same list.
      </Alert>
    )
  }

  return (
    <Box>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'baseline', mb: 1 }}>
        <Typography variant="overline" component="div">
          Acceptance criteria
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={{ flexGrow: 1 }}>
          {view.programName ? `${view.programName} · ` : ''}version {view.criteriaVersion}
        </Typography>

        {/* Offered only where a model is configured *and* the document's
            classification permits sending it — a W-9 never shows this. */}
        {view.modelAvailable ? (
          <Button size="small" onClick={() => prefill.mutate()} disabled={prefill.isPending}>
            {prefill.isPending ? 'Reading…' : 'Ask the model'}
          </Button>
        ) : null}
      </Stack>

      {prefill.error instanceof RequestFailed ? (
        <Alert severity="warning" variant="outlined" sx={{ mb: 1.5 }}>
          {prefill.error.message}
        </Alert>
      ) : null}

      <Stack spacing={1.5}>
        {view.criteria.map((criterion) => {
          const verdict = VERDICTS.find((option) => option.value === criterion.verdict)
          return (
            <Box
              key={criterion.criterionId}
              sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1, p: 1.5 }}
            >
              <Stack
                direction={{ xs: 'column', sm: 'row' }}
                spacing={1.5}
                sx={{ justifyContent: 'space-between', alignItems: { sm: 'flex-start' } }}
              >
                <Box sx={{ minWidth: 0 }}>
                  <Typography variant="body2">
                    {criterion.ordinal}. {criterion.text}
                  </Typography>
                  {criterion.evidence ? (
                    <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.5 }}>
                      What the document shows: {criterion.evidence}
                    </Typography>
                  ) : null}
                  {criterion.source === 'MODEL' ? (
                    <Typography variant="caption" color="text.secondary" component="div">
                      Suggested by {view.model ?? 'the model'}
                      {criterion.confidence != null
                        ? ` · ${Math.round(criterion.confidence * 100)}% confident`
                        : ''}
                      {' · not decided until you say so'}
                    </Typography>
                  ) : criterion.decidedByName ? (
                    <Typography variant="caption" color="text.secondary" component="div">
                      Marked by {criterion.decidedByName}
                    </Typography>
                  ) : null}
                </Box>

                <Stack spacing={0.75} sx={{ alignItems: 'flex-end' }}>
                  <ButtonGroup size="small" disabled={judge.isPending}>
                    {VERDICTS.map((option) => (
                      <Button
                        key={option.value}
                        variant={criterion.verdict === option.value ? 'contained' : 'outlined'}
                        onClick={() =>
                          judge.mutate({ criterionId: criterion.criterionId, verdict: option.value })
                        }
                      >
                        {option.label}
                      </Button>
                    ))}
                  </ButtonGroup>

                  {criterion.verdict === 'FAIL' ? (
                    <Button size="small" color="error" onClick={() => onRejectWith(criterion.criterionId, criterion.text)}>
                      Reject using this
                    </Button>
                  ) : verdict ? (
                    <StatusChip label={verdict.label} color={verdict.colour} />
                  ) : null}
                </Stack>
              </Stack>
            </Box>
          )
        })}
      </Stack>

      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1.5 }}>
        Marking a criterion does not decide the document. A failed criterion becomes the wording the
        supplier reads if you reject it.
      </Typography>
    </Box>
  )
}
