package com.acme.onboarding.adapter.web

import com.acme.onboarding.adapter.persistence.ActivityRow
import com.acme.onboarding.adapter.persistence.CatalogRepository
import com.acme.onboarding.adapter.persistence.ProgramRecord
import com.acme.onboarding.application.document.DocumentService
import com.acme.onboarding.application.document.UploadRequest
import com.acme.onboarding.application.supplier.ChecklistView
import com.acme.onboarding.application.supplier.NewSupplierRequest
import com.acme.onboarding.application.supplier.ProfileUpdateRequest
import com.acme.onboarding.application.supplier.SupplierDetail
import com.acme.onboarding.application.supplier.SupplierProfileView
import com.acme.onboarding.application.supplier.SupplierService
import com.acme.onboarding.application.supplier.SupplierSummary
import com.acme.onboarding.application.supplier.SupplierUserView
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/suppliers")
@Tag(name = "Suppliers")
class SupplierController(
    private val suppliers: SupplierService,
    private val documents: DocumentService,
) {

    data class NewSupplierBody(
        @field:NotBlank(message = "Enter the company's legal name.")
        val legalName: String,
        @field:NotBlank(message = "Enter the name of your contact at the company.")
        val contactName: String,
        @field:Email(message = "Enter a valid email address.")
        @field:NotBlank(message = "Enter the contact's email address.")
        val contactEmail: String,
        val programIds: List<UUID>,
    )

    data class ProfileBody(
        @field:NotBlank(message = "Enter your company's legal name, exactly as it appears on your W-9.")
        val legalName: String,
        val dbaName: String? = null,
        val entityType: String? = null,
        /** Sent once. Only the last four digits are ever returned. */
        val taxId: String? = null,
        val addressLine1: String? = null,
        val addressLine2: String? = null,
        val city: String? = null,
        val state: String? = null,
        val postalCode: String? = null,
        val primaryContactName: String? = null,
        val primaryContactEmail: String? = null,
        val primaryContactPhone: String? = null,
    )

    data class InviteUserBody(
        @field:Email(message = "Enter a valid email address.")
        @field:NotBlank(message = "Enter an email address.")
        val email: String,
        @field:NotBlank(message = "Enter the person's name.")
        val fullName: String,
    )

    @GetMapping
    @Operation(summary = "Every supplier the caller may see, with what each is blocked on")
    fun list(): List<SupplierSummary> = suppliers.list(CurrentActor.require())

    @PostMapping
    @Operation(summary = "Start onboarding: create the company, enrol it and invite its first user")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody body: NewSupplierBody): SupplierDetail =
        suppliers.createAndInvite(
            CurrentActor.require(),
            NewSupplierRequest(
                legalName = body.legalName,
                contactName = body.contactName,
                contactEmail = body.contactEmail,
                programIds = body.programIds,
            ),
        )

    @GetMapping("/{id}")
    @Operation(summary = "One supplier's record, with compliance per program")
    fun detail(@PathVariable id: UUID): SupplierDetail = suppliers.detail(CurrentActor.require(), id)

    @GetMapping("/{id}/profile")
    @Operation(summary = "The company profile")
    fun profile(@PathVariable id: UUID): SupplierProfileView = suppliers.profile(CurrentActor.require(), id)

    @PatchMapping("/{id}/profile")
    @Operation(summary = "Save the company profile")
    fun updateProfile(
        @PathVariable id: UUID,
        @Valid @RequestBody body: ProfileBody,
    ): SupplierProfileView = suppliers.updateProfile(
        CurrentActor.require(),
        id,
        ProfileUpdateRequest(
            legalName = body.legalName,
            dbaName = body.dbaName,
            entityType = body.entityType,
            taxId = body.taxId,
            addressLine1 = body.addressLine1,
            addressLine2 = body.addressLine2,
            city = body.city,
            state = body.state,
            postalCode = body.postalCode,
            primaryContactName = body.primaryContactName,
            primaryContactEmail = body.primaryContactEmail,
            primaryContactPhone = body.primaryContactPhone,
        ),
    )

    @GetMapping("/{id}/checklist")
    @Operation(summary = "What this supplier owes, per program, split into on-file and outstanding")
    fun checklist(@PathVariable id: UUID): ChecklistView = suppliers.checklist(CurrentActor.require(), id)

    @GetMapping("/{id}/activity")
    @Operation(summary = "The supplier's audit timeline, newest first")
    fun activity(@PathVariable id: UUID): List<ActivityRow> = suppliers.activity(CurrentActor.require(), id)

    @GetMapping("/{id}/users")
    @Operation(summary = "The people at this supplier who can sign in")
    fun users(@PathVariable id: UUID): List<SupplierUserView> =
        suppliers.supplierUsers(CurrentActor.require(), id)

    @PostMapping("/{id}/users/invite")
    @Operation(summary = "Invite another person at this supplier")
    @ResponseStatus(HttpStatus.CREATED)
    fun inviteUser(@PathVariable id: UUID, @Valid @RequestBody body: InviteUserBody): Map<String, UUID> =
        mapOf("userId" to suppliers.inviteUser(CurrentActor.require(), id, body.email, body.fullName))

    @PostMapping("/{id}/users/{userId}/deactivate")
    @Operation(summary = "End one supplier user's access, immediately")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deactivateUser(@PathVariable id: UUID, @PathVariable userId: UUID) =
        suppliers.deactivateUser(CurrentActor.require(), id, userId)

    @PostMapping("/{id}/users/{userId}/reactivate")
    @Operation(summary = "Restore a deactivated supplier user")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reactivateUser(@PathVariable id: UUID, @PathVariable userId: UUID) =
        suppliers.reactivateUser(CurrentActor.require(), id, userId)

    /**
     * Multipart rather than JSON with a base64 field: a 10 MB certificate would
     * be a 13 MB string, and the request would be held in memory twice.
     */
    @PostMapping("/{id}/documents")
    @Operation(summary = "Upload a document against one requirement")
    @ResponseStatus(HttpStatus.CREATED)
    fun upload(
        @PathVariable id: UUID,
        @RequestPart("file") file: MultipartFile,
        @RequestParam documentTypeCode: String,
        @RequestParam(required = false) enrollmentId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) issuedOn: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) expiresOn: LocalDate?,
    ): Map<String, UUID> {
        val submissionId = documents.upload(
            CurrentActor.require(),
            UploadRequest(
                supplierId = id,
                documentTypeCode = documentTypeCode,
                enrollmentId = enrollmentId,
                originalFilename = file.originalFilename ?: "upload",
                declaredContentType = file.contentType,
                bytes = file.bytes,
                issuedOn = issuedOn,
                expiresOn = expiresOn,
            ),
        )
        return mapOf("submissionId" to submissionId)
    }
}

@RestController
@RequestMapping("/api/programs")
@Tag(name = "Programs")
class ProgramController(private val catalog: CatalogRepository) {

    @GetMapping
    @Operation(summary = "Acme's client programs")
    fun list(): List<ProgramRecord> {
        CurrentActor.require()
        return catalog.programs()
    }
}
