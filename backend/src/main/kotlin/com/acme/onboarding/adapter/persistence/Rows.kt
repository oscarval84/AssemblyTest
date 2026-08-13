package com.acme.onboarding.adapter.persistence

import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Reading helpers shared by every row mapper.
 *
 * The nullable variants exist because `ResultSet` returns zero-like values for
 * SQL NULL on primitive getters, and a document with no expiry date must not
 * read back as one that expired at the epoch.
 */

internal fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

internal fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)

internal fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant()

internal fun ResultSet.instantOrNull(column: String): Instant? = getTimestamp(column)?.toInstant()

/** Calendar facts — `DATE` columns — never instants. See architecture.md § Dates. */
internal fun ResultSet.localDateOrNull(column: String): LocalDate? = getDate(column)?.toLocalDate()

/**
 * Instants are bound as UTC offsets rather than `java.sql.Timestamp`, which the
 * driver would otherwise interpret through the JVM's default zone — a value that
 * differs between a developer's laptop and Cloud Run.
 */
internal fun Instant.asParam(): OffsetDateTime = atOffset(ZoneOffset.UTC)
