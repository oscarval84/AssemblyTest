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

export type RequirementState = ChecklistEntry['state']
export type Role = Session['role']

/**
 * The session cookie is `HttpOnly`, so the browser attaches it and this code
 * never sees it. The CSRF token is the readable half of the pair: the server
 * sets it as an ordinary cookie and expects it echoed in a header, which proves
 * the request came from a page on this origin rather than from any site that can
 * make the browser send a cookie.
 */
const csrfMiddleware: Middleware = {
  async onRequest({ request }) {
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      const token = readCookie('XSRF-TOKEN')
      if (token) request.headers.set('X-XSRF-TOKEN', token)
    }
    return request
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
