import { compliance } from '../theme/theme'
import type { ChecklistEntry, RequirementState, SupplierSummary } from '../api/client'
import { formatMoney } from './format'

/**
 * Every status the product shows a person, in one vocabulary.
 *
 * Both sides of the product read from this file, which is what keeps the promise
 * that ops and the supplier never see the same state described two different
 * ways. Where the wording differs by audience it does so deliberately: Acme
 * "rejected" a document, and the supplier needs to "replace" it.
 */

export interface StatusLook {
  label: string
  color: string
  /** What this state means for the reader, in a sentence. */
  hint?: string
}

export function requirementLook(state: RequirementState): StatusLook {
  switch (state) {
    case 'APPROVED':
      return { label: 'Approved', color: compliance.compliant, hint: 'Nothing to do.' }
    case 'EXPIRING_SOON':
      return {
        label: 'Expiring soon',
        color: compliance.expiring,
        hint: 'Still valid. Send a renewal before it lapses.',
      }
    case 'EXPIRED':
      return { label: 'Expired', color: compliance.nonCompliant, hint: 'Upload a current version.' }
    case 'IN_REVIEW':
      return { label: 'In review', color: compliance.inReview, hint: 'With Acme. Nothing needed from you.' }
    case 'CHANGES_REQUESTED':
      return {
        label: 'Changes requested',
        color: compliance.nonCompliant,
        hint: 'We could not accept the last version.',
      }
    case 'NOT_STARTED':
    default:
      return { label: 'Not started', color: compliance.idle }
  }
}

export function complianceLook(status: SupplierSummary['complianceStatus']): StatusLook {
  switch (status) {
    case 'COMPLIANT':
      return { label: 'Compliant', color: compliance.compliant }
    case 'EXPIRING_SOON':
      return { label: 'Expiring soon', color: compliance.expiring }
    case 'NON_COMPLIANT':
    default:
      return { label: 'Not compliant', color: compliance.nonCompliant }
  }
}

const stageLabels: Record<string, string> = {
  INVITED: 'Invited',
  REGISTERED: 'Registered',
  PROFILE_SUBMITTED: 'Profile submitted',
  DOCUMENTS_IN_PROGRESS: 'Documents in progress',
  IN_REVIEW: 'In review',
  CHANGES_REQUESTED: 'Changes requested',
  APPROVED: 'Approved',
}

export function stageLabel(stage: string): string {
  return stageLabels[stage] ?? stage
}

export function waitingOnLabel(waitingOn: string): string {
  switch (waitingOn) {
    case 'ACME':
      return 'Waiting on Acme'
    case 'SUPPLIER':
      return 'Waiting on supplier'
    default:
      return 'No action needed'
  }
}

export function waitingOnColor(waitingOn: string): string {
  switch (waitingOn) {
    case 'ACME':
      return compliance.inReview
    case 'SUPPLIER':
      return compliance.expiring
    default:
      return compliance.compliant
  }
}

const actionLabels: Record<string, string> = {
  USER_INVITED: 'User invited',
  INVITATION_ACCEPTED: 'Invitation accepted',
  USER_SIGNED_IN: 'Signed in',
  USER_SIGNED_OUT: 'Signed out',
  USER_ROLE_CHANGED: 'Role changed',
  USER_SCOPE_CHANGED: 'Program access changed',
  USER_DEACTIVATED: 'Access removed',
  USER_REACTIVATED: 'Access restored',
  PASSWORD_RESET_REQUESTED: 'Password reset requested',
  PASSWORD_RESET_COMPLETED: 'Password changed',
  SUPPLIER_INVITED: 'Supplier invited',
  SUPPLIER_PROFILE_UPDATED: 'Company profile updated',
  SUPPLIER_STAGE_CHANGED: 'Stage changed',
  SUPPLIER_ENROLLED: 'Enrolled in a program',
  DOCUMENT_UPLOADED: 'Document uploaded',
  DOCUMENT_APPROVED: 'Document approved',
  DOCUMENT_REJECTED: 'Document rejected',
  DOCUMENT_SIGNED: 'Agreement signed',
  DOCUMENT_ACCESSED: 'Document opened',
  DEMO_DATA_RESET: 'Demo data reset',
}

export function actionLabel(action: string): string {
  return actionLabels[action] ?? action.toLowerCase().replaceAll('_', ' ')
}

/**
 * Program constraints, written out.
 *
 * The shape differs per document type — a coverage minimum on a certificate has
 * no analogue on a W-9 — so this renders what it recognises and says nothing
 * about the rest rather than dumping raw JSON at a supplier.
 */
export function constraintLines(entry: ChecklistEntry): string[] {
  const constraints = (entry.constraints ?? {}) as Record<string, unknown>
  const lines: string[] = []

  const minimum = constraints.generalLiabilityMinimum
  if (typeof minimum === 'number') {
    const currency = typeof constraints.currency === 'string' ? constraints.currency : 'USD'
    lines.push(`General liability of at least ${formatMoney(minimum, currency)} in aggregate.`)
  }

  const renewal = constraints.renewalMonths
  if (typeof renewal === 'number') {
    lines.push(`Renewed every ${renewal} months.`)
  }

  return lines
}
