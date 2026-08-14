import createClient, { type Middleware } from 'openapi-fetch'
import type { components, paths } from './schema'

/**
 * The typed API client.
 *
 * `schema.d.ts` is generated from the backend's own OpenAPI document
 * (`npm run generate:api`), so a breaking backend change fails the type-check
 * here instead of failing in a supplier's browser. It is checked in on purpose:
 * a build must not depend on a running backend.
 */

type Schemas = components['schemas']

export type Session = Schemas['SessionDescription']
export type SupplierSummary = Schemas['SupplierSummary']
export type SupplierDetail = Schemas['SupplierDetail']
export type SupplierProfile = Schemas['SupplierProfileView']
export type ChecklistView = Schemas['ChecklistView']
export type ProgramChecklist = Schemas['ProgramChecklist']
export type ChecklistEntry = Schemas['ChecklistEntry']
export type SubmissionView = Schemas['SubmissionView']
export type ActivityRow = Schemas['ActivityRow']
export type InvitationPreview = Schemas['InvitationPreview']
export type AgreementPreview = Schemas['AgreementPreview']
export type DemoInfo = Schemas['DemoInfo']
export type ProgramRecord = Schemas['ProgramRecord']
export type SupplierUser = Schemas['SupplierUserView']
export type ProfileBody = Schemas['ProfileBody']
export type ReviewQueueItem = Schemas['ReviewQueueItem']
export type RejectionReason = Schemas['RejectionReasonRecord']
export type OutboxView = Schemas['OutboxView']
export type OutboxEntry = Schemas['OutboxEntry']
export type StaffUser = Schemas['StaffUserView']
export type ExpiringDocument = Schemas['ExpiringDocument']
export type IntegrationMessage = Schemas['IntegrationMessageRecord']
export type CriteriaChecklist = Schemas['CriteriaChecklist']
export type CriterionVerdict = Schemas['CriterionVerdict']
export type ChainVerification = Schemas['ChainVerificationView']
export type ExtractionView = Schemas['ExtractionView']

export type RequirementState = ChecklistEntry['state']
export type Role = Session['role']

/**
 * The session cookie is `HttpOnly`, so the browser attaches it and this code
 * never sees it. The CSRF token is the readable half of the pair: the server
 * sets it as an ordinary cookie and expects it echoed in a header, which proves
 * the request came from a page on this origin rather than from any site that can
 * make the browser send a cookie.
 */
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE'])

const csrfMiddleware: Middleware = {
  async onRequest({ request }) {
    if (SAFE_METHODS.has(request.method.toUpperCase())) return request

    const token = readCookie('XSRF-TOKEN')
    if (!token) return request

    // A new Request rather than a mutated one: the object handed to middleware
    // may carry immutable headers depending on how it was constructed, and a
    // silently dropped CSRF header fails as a 403 that looks like an
    // authorization bug rather than a missing header.
    const headers = new Headers(request.headers)
    headers.set('X-XSRF-TOKEN', token)
    return new Request(request, { headers })
  },
}

export const api = createClient<paths>({ credentials: 'include' })
api.use(csrfMiddleware)

function readCookie(name: string): string | undefined {
  return document.cookie
    .split('; ')
    .find((entry) => entry.startsWith(`${name}=`))
    ?.split('=')[1]
}

/** The error shape every endpoint returns; see the backend's ApiExceptionHandler. */
export interface ApiError {
  message: string
  status: number
  code?: string | null
}

export class RequestFailed extends Error {
  readonly status: number
  readonly code?: string | null

  constructor(status: number, message: string, code?: string | null) {
    super(message)
    this.status = status
    this.code = code
  }
}

/**
 * Unwraps a response, turning a failure into an exception carrying the server's
 * own message. The messages are written for the person reading them, so showing
 * them verbatim is the point — a generic "something went wrong" would throw away
 * the part of the response that tells a supplier what to do next.
 */
export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.error !== undefined || !result.response.ok) {
    const error = result.error as ApiError | undefined
    throw new RequestFailed(
      result.response.status,
      error?.message ?? 'Something went wrong. Try again in a moment.',
      error?.code,
    )
  }
  return result.data as T
}

/** The auditor export's filter, exactly as the screen offers it. */
export interface AuditExportFilter {
  supplierId?: string | null
  programId?: string | null
  /** Calendar dates, `2026-08-13`, both ends inclusive. */
  from?: string | null
  to?: string | null
}

/**
 * CSV for the person who will filter and pivot it; PDF for the one that gets
 * attached to an audit response. Same events either way.
 */
export type AuditExportFormat = 'csv' | 'pdf'

export function auditExportUrl(filter: AuditExportFilter, format: AuditExportFormat = 'csv'): string {
  const query = new URLSearchParams()
  if (filter.supplierId) query.set('supplierId', filter.supplierId)
  if (filter.programId) query.set('programId', filter.programId)
  if (filter.from) query.set('from', filter.from)
  if (filter.to) query.set('to', filter.to)

  const search = query.toString()
  return `/api/audit/export.${format}${search ? `?${search}` : ''}`
}

/**
 * Fetches the export and hands the file to the browser.
 *
 * A plain download link would be less code, and it was the first version. It
 * fails in the one case that matters: when the server refuses — a range too
 * wide, a program the caller may not read — a link saves the refusal *as the
 * file*, and the person ends up with `acme-audit-….csv` containing an error
 * they will not open until they have already sent it on. Reading the response
 * first means the message reaches the screen instead.
 */
export async function downloadAuditExport(
  filter: AuditExportFilter,
  format: AuditExportFormat = 'csv',
): Promise<number> {
  const response = await fetch(auditExportUrl(filter, format), { credentials: 'include' })

  if (!response.ok) {
    const error = (await response.json().catch(() => undefined)) as ApiError | undefined
    throw new RequestFailed(
      response.status,
      error?.message ?? 'We could not build that export. Try a narrower range.',
      error?.code,
    )
  }

  // The server names the file after what is in it; keep that name rather than
  // inventing one here, so two exports never collide in a downloads folder.
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const filename = /filename="?([^";]+)"?/.exec(disposition)?.[1] ?? `acme-audit-export.${format}`
  const rowCount = Number(response.headers.get('X-Audit-Row-Count') ?? '0')

  const objectUrl = URL.createObjectURL(await response.blob())
  const link = document.createElement('a')
  link.href = objectUrl
  link.download = filename
  link.click()

  // Released on the next tick rather than the lifetime of the tab: the blob is
  // a copy of the audit log. Not synchronously — some browsers have not started
  // reading it by the time click() returns, and revoking cancels the download.
  setTimeout(() => URL.revokeObjectURL(objectUrl), 0)

  return rowCount
}

/**
 * Multipart uploads bypass the generated client: it serialises bodies as JSON,
 * and a 10 MB certificate has no business being base64 in a JSON string.
 */
export async function uploadDocument(input: {
  supplierId: string
  file: File
  documentTypeCode: string
  enrollmentId?: string | null
  issuedOn?: string | null
  expiresOn?: string | null
}): Promise<void> {
  const body = new FormData()
  body.append('file', input.file)
  body.append('documentTypeCode', input.documentTypeCode)
  if (input.enrollmentId) body.append('enrollmentId', input.enrollmentId)
  if (input.issuedOn) body.append('issuedOn', input.issuedOn)
  if (input.expiresOn) body.append('expiresOn', input.expiresOn)

  const token = readCookie('XSRF-TOKEN')
  const response = await fetch(`/api/suppliers/${input.supplierId}/documents`, {
    method: 'POST',
    credentials: 'include',
    headers: token ? { 'X-XSRF-TOKEN': token } : undefined,
    body,
  })

  if (!response.ok) {
    const error = (await response.json().catch(() => undefined)) as ApiError | undefined
    throw new RequestFailed(
      response.status,
      error?.message ?? 'That upload did not go through. Try again.',
      error?.code,
    )
  }
}
