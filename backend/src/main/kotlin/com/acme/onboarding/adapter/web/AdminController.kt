package com.acme.onboarding.adapter.web

import com.acme.onboarding.adapter.persistence.ActivityRow
import com.acme.onboarding.application.admin.StaffAdministrationService
import com.acme.onboarding.application.admin.StaffUserView
import com.acme.onboarding.application.auth.InvitationService
import com.acme.onboarding.application.auth.PasswordResetService
import com.acme.onboarding.domain.user.Role
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Acme staff administration — `ADMIN` only.
 *
 * Supplier users are managed from the supplier's own record instead, by ops. The
 * separation is the control: it is what stops Acme-internal access from being
 * granted by someone working a supplier's file.
 */
@RestController
@RequestMapping("/api/admin/users")
@Tag(name = "Staff administration")
class AdminController(
    private val staff: StaffAdministrationService,
    private val invitations: InvitationService,
    private val passwordReset: PasswordResetService,
) {

    data class InviteRequest(
        @field:Email(message = "Enter a valid email address.")
        @field:NotBlank(message = "Enter an email address.")
        val email: String,
        @field:NotBlank(message = "Enter the person's name.")
        val fullName: String,
        val role: Role,
        /** Only meaningful for a program manager; ignored otherwise. */
        val programIds: List<UUID> = emptyList(),
    )

    data class RoleRequest(val role: Role)

    data class ProgramScopeRequest(val programIds: List<UUID>)

    @GetMapping
    @Operation(summary = "The access report: every internal user, role, scope and last sign-in")
    fun list(): List<StaffUserView> = staff.list(CurrentActor.require())

    @PostMapping("/invite")
    @Operation(summary = "Invite an Acme staff member")
    @ResponseStatus(HttpStatus.CREATED)
    fun invite(@Valid @RequestBody request: InviteRequest): Map<String, UUID> {
        val id = invitations.inviteStaff(
            actor = CurrentActor.require(),
            email = request.email,
            fullName = request.fullName,
            role = request.role,
            programIds = request.programIds,
        )
        return mapOf("userId" to id)
    }

    @PatchMapping("/{id}/role")
    @Operation(summary = "Change a staff member's role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changeRole(@PathVariable id: UUID, @RequestBody request: RoleRequest) =
        staff.changeRole(CurrentActor.require(), id, request.role)

    @PatchMapping("/{id}/programs")
    @Operation(summary = "Set which programs a program manager can see")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun setScope(@PathVariable id: UUID, @RequestBody request: ProgramScopeRequest) =
        staff.setProgramScope(CurrentActor.require(), id, request.programIds)

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "End a staff member's access immediately")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deactivate(@PathVariable id: UUID) = staff.deactivate(CurrentActor.require(), id)

    @PostMapping("/{id}/reactivate")
    @Operation(summary = "Restore a deactivated staff member")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reactivate(@PathVariable id: UUID) = staff.reactivate(CurrentActor.require(), id)

    @PostMapping("/{id}/reset-password")
    @Operation(summary = "Email this user a reset link on their behalf")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun resetPassword(@PathVariable id: UUID) = passwordReset.issueOnBehalf(CurrentActor.require(), id)

    @GetMapping("/{id}/access-history")
    @Operation(summary = "Every access change recorded for this user")
    fun accessHistory(@PathVariable id: UUID): List<ActivityRow> =
        staff.accessHistory(CurrentActor.require(), id)
}
