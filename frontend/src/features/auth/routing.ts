import type { Role } from '../../api/client'

/**
 * Where each role belongs after signing in.
 *
 * A supplier user has no ops console to be sent to, and a program manager has no
 * portal — routing them to a screen they will be refused is a dead end dressed
 * up as navigation.
 */
export function homeFor(role: Role): string {
  return role === 'SUPPLIER_USER' ? '/portal' : '/ops'
}
