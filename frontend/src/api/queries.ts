import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query'
import {
  api,
  downloadAuditExport,
  RequestFailed,
  unwrap,
  uploadDocument,
  type ActivityRow,
  type AgreementPreview,
  type AuditExportFilter,
  type ChainVerification,
  type ChecklistView,
  type DemoInfo,
  type InvitationPreview,
  type CriteriaChecklist,
  type ExpiringDocument,
  type IntegrationMessage,
  type OutboxView,
  type ProfileBody,
  type ProgramRecord,
  type RejectionReason,
  type ReviewQueueItem,
  type StaffUser,
  type Session,
  type SupplierDetail,
  type SupplierProfile,
  type SupplierSummary,
  type SupplierUser,
} from './client'

/**
 * Query keys, in one place.
 *
 * The invalidations below are the mechanism behind a product promise: ops and
 * the supplier must never see different answers about the same document. A
 * mutation that changes a document invalidates every view derived from it, so
 * neither side is left reading a cached version of a truth that has moved.
 */
export const keys = {
  session: ['session'] as const,
  demo: ['demo-accounts'] as const,
  programs: ['programs'] as const,
  suppliers: ['suppliers'] as const,
  supplier: (id: string) => ['supplier', id] as const,
  checklist: (id: string) => ['checklist', id] as const,
  activity: (id: string) => ['activity', id] as const,
  supplierUsers: (id: string) => ['supplier-users', id] as const,
  invitation: (token: string) => ['invitation', token] as const,
  reviewQueue: ['review-queue'] as const,
  rejectionReasons: ['rejection-reasons'] as const,
  outbox: ['outbox'] as const,
  staff: ['staff-users'] as const,
  expirations: ['expirations'] as const,
  integrations: ['integration-messages'] as const,
  criteria: (submissionId: string) => ['criteria', submissionId] as const,
  accessHistory: (id: string) => ['access-history', id] as const,
  chainVerification: (chainKey: string) => ['chain-verification', chainKey] as const,
  agreement: (supplierId: string, code: string) => ['agreement', supplierId, code] as const,
}

/** `null` means "signed out", which is an answer rather than a failure. */
export function useSession(): UseQueryResult<Session | null> {
  return useQuery({
    queryKey: keys.session,
    retry: false,
    staleTime: 30_000,
    queryFn: async () => {
      try {
        return unwrap(await api.GET('/api/auth/session'))
      } catch (error) {
        if (error instanceof RequestFailed && error.status === 401) return null
        throw error
      }
    },
  })
}

export function useDemoAccounts(): UseQueryResult<DemoInfo | null> {
  return useQuery({
    queryKey: keys.demo,
    retry: false,
    staleTime: Infinity,
    queryFn: async () => {
      try {
        return unwrap(await api.GET('/api/demo/accounts'))
      } catch {
        // Demo mode is off in this environment; the sign-in screen simply
        // does not offer the shortcut.
        return null
      }
    },
  })
}

export function useLogin() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (body: { email: string; password: string }) =>
      unwrap(await api.POST('/api/auth/login', { body })),
    onSuccess: (session) => {
      queryClient.setQueryData(keys.session, session)
    },
  })
}

export function useLogout() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      await api.POST('/api/auth/logout')
    },
    onSuccess: () => {
      queryClient.clear()
      queryClient.setQueryData(keys.session, null)
    },
  })
}

export function useRequestPasswordReset() {
  return useMutation({
    mutationFn: async (body: { email: string }) =>
      unwrap(await api.POST('/api/auth/password-reset', { body })),
  })
}

export function useCompletePasswordReset(token: string) {
  return useMutation({
    mutationFn: async (body: { password: string }) =>
      unwrap(await api.POST('/api/auth/password-reset/{token}', { params: { path: { token } }, body })),
  })
}

export function useInvitation(token: string): UseQueryResult<InvitationPreview> {
  return useQuery({
    queryKey: keys.invitation(token),
    retry: false,
    queryFn: async () =>
      unwrap(await api.GET('/api/invitations/{token}', { params: { path: { token } } })),
  })
}

export function useAcceptInvitation(token: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (body: { password: string }) =>
      unwrap(await api.POST('/api/invitations/{token}/accept', { params: { path: { token } }, body })),
    onSuccess: (session) => {
      queryClient.setQueryData(keys.session, session)
    },
  })
}

export function useSuppliers(enabled = true): UseQueryResult<SupplierSummary[]> {
  return useQuery({
    queryKey: keys.suppliers,
    enabled,
    queryFn: async () => unwrap(await api.GET('/api/suppliers')),
  })
}

export function useSupplier(id: string | undefined): UseQueryResult<SupplierDetail> {
  return useQuery({
    queryKey: keys.supplier(id ?? ''),
    enabled: Boolean(id),
    queryFn: async () => unwrap(await api.GET('/api/suppliers/{id}', { params: { path: { id: id! } } })),
  })
}

export function useChecklist(id: string | undefined): UseQueryResult<ChecklistView> {
  return useQuery({
    queryKey: keys.checklist(id ?? ''),
    enabled: Boolean(id),
    queryFn: async () =>
      unwrap(await api.GET('/api/suppliers/{id}/checklist', { params: { path: { id: id! } } })),
  })
}

export function useActivity(id: string | undefined): UseQueryResult<ActivityRow[]> {
  return useQuery({
    queryKey: keys.activity(id ?? ''),
    enabled: Boolean(id),
    queryFn: async () =>
      unwrap(await api.GET('/api/suppliers/{id}/activity', { params: { path: { id: id! } } })),
  })
}

export function useSupplierUsers(id: string | undefined): UseQueryResult<SupplierUser[]> {
  return useQuery({
    queryKey: keys.supplierUsers(id ?? ''),
    enabled: Boolean(id),
    queryFn: async () =>
      unwrap(await api.GET('/api/suppliers/{id}/users', { params: { path: { id: id! } } })),
  })
}

export function usePrograms(): UseQueryResult<ProgramRecord[]> {
  return useQuery({
    queryKey: keys.programs,
    staleTime: 5 * 60_000,
    queryFn: async () => unwrap(await api.GET('/api/programs')),
  })
}

export function useProfile(id: string | undefined): UseQueryResult<SupplierProfile> {
  return useQuery({
    queryKey: [...keys.supplier(id ?? ''), 'profile'],
    enabled: Boolean(id),
    queryFn: async () =>
      unwrap(await api.GET('/api/suppliers/{id}/profile', { params: { path: { id: id! } } })),
  })
}

export function useSaveProfile(supplierId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (body: ProfileBody) =>
      unwrap(
        await api.PATCH('/api/suppliers/{id}/profile', {
          params: { path: { id: supplierId } },
          body,
        }),
      ),
    onSuccess: () => invalidateSupplier(queryClient, supplierId),
  })
}

export function useUploadDocument(supplierId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: {
      file: File
      documentTypeCode: string
      enrollmentId?: string | null
      issuedOn?: string | null
      expiresOn?: string | null
    }) => uploadDocument({ supplierId, ...input }),
    onSuccess: () => invalidateSupplier(queryClient, supplierId),
  })
}

export function useAgreement(supplierId: string, documentTypeCode: string, enabled: boolean): UseQueryResult<AgreementPreview> {
  return useQuery({
    queryKey: keys.agreement(supplierId, documentTypeCode),
    enabled,
    queryFn: async () =>
      unwrap(await api.GET('/api/documents/agreement', { params: { query: { supplierId, documentTypeCode } } })),
  })
}

export function useSignAgreement(supplierId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (body: { documentTypeCode: string; enrollmentId?: string | null; typedName: string }) =>
      unwrap(
        await api.POST('/api/documents/sign', {
          body: { supplierId, ...body },
        }),
      ),
    onSuccess: () => invalidateSupplier(queryClient, supplierId),
  })
}

export function useCreateSupplier() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (body: {
      legalName: string
      contactName: string
      contactEmail: string
      programIds: string[]
    }) => unwrap(await api.POST('/api/suppliers', { body })),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: keys.suppliers })
    },
  })
}

export function useResetDemo() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      await api.POST('/api/demo/reset')
    },
    onSuccess: () => queryClient.clear(),
  })
}

/** Everything derived from one supplier's documents, refreshed together. */
function invalidateSupplier(
  queryClient: ReturnType<typeof useQueryClient>,
  supplierId: string,
): void {
  void queryClient.invalidateQueries({ queryKey: keys.supplier(supplierId) })
  void queryClient.invalidateQueries({ queryKey: keys.checklist(supplierId) })
  void queryClient.invalidateQueries({ queryKey: keys.activity(supplierId) })
  void queryClient.invalidateQueries({ queryKey: keys.suppliers })
}

export function useReviewQueue(enabled = true): UseQueryResult<ReviewQueueItem[]> {
  return useQuery({
    queryKey: keys.reviewQueue,
    enabled,
    queryFn: async () => unwrap(await api.GET('/api/documents/review-queue')),
  })
}

export function useRejectionReasons(): UseQueryResult<RejectionReason[]> {
  return useQuery({
    queryKey: keys.rejectionReasons,
    staleTime: 5 * 60_000,
    queryFn: async () => unwrap(await api.GET('/api/documents/rejection-reasons')),
  })
}

export function useOutbox(enabled = true): UseQueryResult<OutboxView> {
  return useQuery({
    queryKey: keys.outbox,
    enabled,
    queryFn: async () => unwrap(await api.GET('/api/outbox')),
  })
}

/**
 * A review changes the supplier's stage, their checklist, the pipeline and the
 * outbox at once, so all of them are invalidated together. Ops and the supplier
 * seeing different answers after a decision is the specific failure this
 * product exists to remove.
 */
export function useReviewDecision(supplierId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (input: { submissionId: string; reasonCode?: string; note?: string | null }) => {
      if (input.reasonCode) {
        await api.POST('/api/documents/{id}/reject', {
          params: { path: { id: input.submissionId } },
          body: { reasonCode: input.reasonCode, note: input.note ?? null },
        }).then(unwrap)
      } else {
        await api.POST('/api/documents/{id}/approve', {
          params: { path: { id: input.submissionId } },
        }).then(unwrap)
      }
    },
    onSuccess: () => {
      invalidateSupplier(queryClient, supplierId)
      void queryClient.invalidateQueries({ queryKey: keys.reviewQueue })
      void queryClient.invalidateQueries({ queryKey: keys.outbox })
    },
  })
}

export function useExpirations(withinDays = 60): UseQueryResult<ExpiringDocument[]> {
  return useQuery({
    queryKey: [...keys.expirations, withinDays],
    queryFn: async () =>
      unwrap(await api.GET('/api/compliance/expirations', { params: { query: { withinDays } } })),
  })
}

export function useCriteriaChecklist(submissionId: string, enabled = true): UseQueryResult<CriteriaChecklist> {
  return useQuery({
    queryKey: keys.criteria(submissionId),
    enabled,
    queryFn: async () =>
      unwrap(await api.GET('/api/documents/{submissionId}/criteria', {
        params: { path: { submissionId } },
      })),
  })
}

/**
 * A reviewer's verdict on one criterion.
 *
 * Advisory in the strict sense: recording a FAIL does not reject the document.
 * It makes the rejection *specific* if the reviewer goes on to click reject.
 */
export function useJudgeCriterion(submissionId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (input: { criterionId: string; verdict: string; evidence?: string | null }) =>
      unwrap(
        await api.POST('/api/documents/{submissionId}/criteria/{criterionId}', {
          params: { path: { submissionId, criterionId: input.criterionId } },
          body: { verdict: input.verdict, evidence: input.evidence ?? null },
        }),
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: keys.criteria(submissionId) })
    },
  })
}

export async function rejectionNoteFor(submissionId: string, criterionId: string): Promise<string> {
  const result = unwrap(
    await api.GET('/api/documents/{submissionId}/criteria/{criterionId}/rejection-note', {
      params: { path: { submissionId, criterionId } },
    }),
  )
  return result.note ?? ''
}

export function useIntegrationMessages(): UseQueryResult<IntegrationMessage[]> {
  return useQuery({
    queryKey: keys.integrations,
    // The sync runs on a schedule, so this screen goes stale on its own.
    refetchInterval: 30_000,
    queryFn: async () => unwrap(await api.GET('/api/integrations/messages')),
  })
}

export function useRetryIntegrationMessage() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (id: string) =>
      unwrap(await api.POST('/api/integrations/messages/{id}/retry', { params: { path: { id } } })),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: keys.integrations })
    },
  })
}

/**
 * Whether one supplier's chain still walks cleanly.
 *
 * Shown next to the export rather than on an admin screen, because the person
 * handing a history over is the one who needs to be able to say it is whole.
 */
export function useChainVerification(chainKey: string | undefined): UseQueryResult<ChainVerification> {
  return useQuery({
    queryKey: keys.chainVerification(chainKey ?? ''),
    enabled: Boolean(chainKey),
    queryFn: async () =>
      unwrap(
        await api.GET('/api/audit/chains/{chainKey}/verification', {
          params: { path: { chainKey: chainKey! } },
        }),
      ),
  })
}

/**
 * The auditor export.
 *
 * A mutation rather than a query even though it reads: it runs when a person
 * asks for it, it is not cached, and it writes an `AUDIT_EXPORTED` event — so
 * the timeline of the supplier just exported is now one event out of date.
 */
export function useAuditExport() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (filter: AuditExportFilter) => downloadAuditExport(filter),
    onSuccess: (_rowCount, filter) => {
      if (filter.supplierId) {
        void queryClient.invalidateQueries({ queryKey: keys.activity(filter.supplierId) })
        void queryClient.invalidateQueries({ queryKey: keys.chainVerification(filter.supplierId) })
      }
    },
  })
}

export function useStaffUsers(enabled = true): UseQueryResult<StaffUser[]> {
  return useQuery({
    queryKey: keys.staff,
    enabled,
    queryFn: async () => unwrap(await api.GET('/api/admin/users')),
  })
}

export function useAccessHistory(userId: string | undefined): UseQueryResult<ActivityRow[]> {
  return useQuery({
    queryKey: keys.accessHistory(userId ?? ''),
    enabled: Boolean(userId),
    queryFn: async () =>
      unwrap(await api.GET('/api/admin/users/{id}/access-history', { params: { path: { id: userId! } } })),
  })
}

/**
 * Every staff administration action, behind one mutation.
 *
 * They share an invalidation because they share a consequence: a role or scope
 * change takes effect on the target's next request, so the screen that made it
 * must not keep showing the access they no longer have.
 */
export function useStaffAdministration() {
  const queryClient = useQueryClient()

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: keys.staff })
    void queryClient.invalidateQueries({ queryKey: ['access-history'] })
  }

  const invite = useMutation({
    mutationFn: async (body: {
      email: string
      fullName: string
      role: 'ADMIN' | 'OPS' | 'PROGRAM_MANAGER'
      programIds: string[]
    }) => unwrap(await api.POST('/api/admin/users/invite', { body })),
    onSuccess: refresh,
  })

  const changeRole = useMutation({
    mutationFn: async (input: { userId: string; role: 'ADMIN' | 'OPS' | 'PROGRAM_MANAGER' }) =>
      unwrap(
        await api.PATCH('/api/admin/users/{id}/role', {
          params: { path: { id: input.userId } },
          body: { role: input.role },
        }),
      ),
    onSuccess: refresh,
  })

  const setProgramScope = useMutation({
    mutationFn: async (input: { userId: string; programIds: string[] }) =>
      unwrap(
        await api.PATCH('/api/admin/users/{id}/programs', {
          params: { path: { id: input.userId } },
          body: { programIds: input.programIds },
        }),
      ),
    onSuccess: refresh,
  })

  const setStatus = useMutation({
    mutationFn: async (input: { userId: string; active: boolean }) => {
      const path = input.active ? '/api/admin/users/{id}/reactivate' : '/api/admin/users/{id}/deactivate'
      return unwrap(await api.POST(path, { params: { path: { id: input.userId } } }))
    },
    onSuccess: refresh,
  })

  const sendReset = useMutation({
    mutationFn: async (userId: string) =>
      unwrap(await api.POST('/api/admin/users/{id}/reset-password', { params: { path: { id: userId } } })),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: keys.outbox })
    },
  })

  return { invite, changeRole, setProgramScope, setStatus, sendReset }
}

/** Supplier user management: a different surface, a different role, on purpose. */
export function useSupplierUserAdministration(supplierId: string) {
  const queryClient = useQueryClient()
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: keys.supplierUsers(supplierId) })
    void queryClient.invalidateQueries({ queryKey: keys.activity(supplierId) })
    void queryClient.invalidateQueries({ queryKey: keys.outbox })
  }

  const invite = useMutation({
    mutationFn: async (body: { email: string; fullName: string }) =>
      unwrap(
        await api.POST('/api/suppliers/{id}/users/invite', {
          params: { path: { id: supplierId } },
          body,
        }),
      ),
    onSuccess: refresh,
  })

  const setStatus = useMutation({
    mutationFn: async (input: { userId: string; active: boolean }) => {
      const path = input.active
        ? '/api/suppliers/{id}/users/{userId}/reactivate'
        : '/api/suppliers/{id}/users/{userId}/deactivate'
      return unwrap(
        await api.POST(path, { params: { path: { id: supplierId, userId: input.userId } } }),
      )
    },
    onSuccess: refresh,
  })

  return { invite, setStatus }
}

export function documentDownloadUrl(submissionId: string): string {
  return `/api/documents/${submissionId}/download`
}
