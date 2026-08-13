/**
 * One helper per kind of value, and nothing formatted inline anywhere else.
 *
 * The split between the first two functions is the one that matters. A document
 * date is a calendar fact printed on a certificate — "expires 15 September 2026"
 * — with no time and no zone of its own, so it is never passed through a
 * timezone conversion. An event timestamp is a real moment, stored in UTC and
 * rendered in the reader's own zone. Formatting an expiry date as if it were an
 * instant is how a supplier ends up flagged as expired a day early.
 */

const dateFormat = new Intl.DateTimeFormat(undefined, {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
})

const dateTimeFormat = new Intl.DateTimeFormat(undefined, {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
})

/** A `DATE` from the API: `2026-09-15`. Parsed as a calendar date, never shifted. */
export function formatDate(value: string | null | undefined): string {
  if (!value) return '—'
  const [year, month, day] = value.split('-').map(Number)
  if (!year || !month || !day) return value
  return dateFormat.format(new Date(year, month - 1, day))
}

/** An instant from the API, rendered in the reader's own time zone. */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  return dateTimeFormat.format(new Date(value))
}

export function formatMoney(amount: number, currency = 'USD'): string {
  return new Intl.NumberFormat(undefined, {
    style: 'currency',
    currency,
    maximumFractionDigits: 0,
  }).format(amount)
}

export function formatFileSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${Math.max(1, Math.round(bytes / 1024))} KB`
}

/**
 * How long until a calendar date, in whole days, resolved against the reader's
 * today. Used for expiry copy, where "in 18 days" is the number a supplier acts
 * on and the date itself is the fact they verify.
 */
export function daysUntil(value: string | null | undefined): number | null {
  if (!value) return null
  const [year, month, day] = value.split('-').map(Number)
  if (!year || !month || !day) return null
  const target = new Date(year, month - 1, day)
  const today = new Date()
  const startOfToday = new Date(today.getFullYear(), today.getMonth(), today.getDate())
  return Math.round((target.getTime() - startOfToday.getTime()) / 86_400_000)
}

export function formatExpiry(value: string | null | undefined): string {
  const days = daysUntil(value)
  if (days === null) return '—'
  if (days < 0) return `expired ${formatDate(value)}`
  if (days === 0) return `expires today, ${formatDate(value)}`
  if (days === 1) return `expires tomorrow, ${formatDate(value)}`
  if (days <= 60) return `expires in ${days} days, ${formatDate(value)}`
  return `expires ${formatDate(value)}`
}
