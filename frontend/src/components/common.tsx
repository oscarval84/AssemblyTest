import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import type { ReactNode } from 'react'

/**
 * The small pieces both surfaces are built from.
 *
 * They exist so that a status looks the same wherever it appears and an empty
 * screen always offers a next step — the product principle that there are no
 * dead ends is only real if it is cheaper to follow than to ignore.
 */

/** Acme's wordmark. Deliberately typographic: no logo file to go stale. */
export function Brand({ surface }: { surface: 'supplier' | 'ops' }) {
  return (
    <Stack direction="row" spacing={1} sx={{ alignItems: 'baseline' }}>
      <Typography
        component="span"
        sx={{ fontWeight: 700, fontSize: '1.05rem', letterSpacing: '-0.03em', color: 'primary.main' }}
      >
        ACME
      </Typography>
      <Typography component="span" sx={{ fontSize: '0.82rem', color: 'text.secondary', letterSpacing: '0.06em' }}>
        {surface === 'ops' ? 'SUPPLIER OPERATIONS' : 'SUPPLIER ONBOARDING'}
      </Typography>
    </Stack>
  )
}

export function StatusChip({
  label,
  color,
  size = 'small',
}: {
  label: string
  color: string
  size?: 'small' | 'medium'
}) {
  return (
    <Chip
      label={label}
      size={size}
      sx={{
        color,
        backgroundColor: alpha(color, 0.1),
        border: `1px solid ${alpha(color, 0.28)}`,
      }}
    />
  )
}

export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string
  description?: ReactNode
  actions?: ReactNode
}) {
  return (
    <Stack
      direction={{ xs: 'column', sm: 'row' }}
     
     
      spacing={2}
      sx={{ alignItems: { xs: 'flex-start', sm: 'flex-end' }, justifyContent: 'space-between', mb: 3 }}
    >
      <Box>
        <Typography variant="h1">{title}</Typography>
        {description ? (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.75, maxWidth: '60ch' }}>
            {description}
          </Typography>
        ) : null}
      </Box>
      {actions ? <Stack direction="row" spacing={1}>{actions}</Stack> : null}
    </Stack>
  )
}

/**
 * An empty state says what would be here, why it is not, and what to do — in
 * that order. "No data" is the version of this that sends someone to email
 * their account manager, which is the process this product replaces.
 */
export function EmptyState({
  title,
  description,
  action,
}: {
  title: string
  description: string
  action?: ReactNode
}) {
  return (
    <Box
      sx={{
        border: '1px dashed',
        borderColor: 'divider',
        borderRadius: 1,
        px: 3,
        py: 5,
        textAlign: 'center',
      }}
    >
      <Typography variant="h3" sx={{ mb: 1 }}>
        {title}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mx: 'auto', maxWidth: '52ch' }}>
        {description}
      </Typography>
      {action ? <Box sx={{ mt: 2.5 }}>{action}</Box> : null}
    </Box>
  )
}

/** A labelled value. The label is a small-caps overline, set once in the theme. */
export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Box>
      <Typography variant="overline" component="div">
        {label}
      </Typography>
      <Typography variant="body2" component="div" className="tabular">
        {children ?? '—'}
      </Typography>
    </Box>
  )
}
