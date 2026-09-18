export type PermissionContext = {
  roles: string[]
  permissions: string[]
  workstations: string[]
}

export function hasRole(context: PermissionContext | null | undefined, role: string): boolean {
  return Boolean(context?.roles.includes(role))
}

export function isSuperAdmin(context: PermissionContext | null | undefined): boolean {
  return hasRole(context, 'SUPER_ADMIN')
}

export function hasPermission(context: PermissionContext | null | undefined, permission: string): boolean {
  return isSuperAdmin(context) || Boolean(context?.permissions.includes(permission))
}
