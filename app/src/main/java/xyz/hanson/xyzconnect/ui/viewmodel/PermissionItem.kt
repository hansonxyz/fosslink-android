package xyz.hanson.fosslink.ui.viewmodel

/** Action to perform for special-access permissions */
enum class SpecialAction {
    FILES_ACCESS,
    BATTERY_OPTIMIZATION
}

data class PermissionItem(
    val name: String,
    val permission: String,
    val granted: Boolean,
    val description: String,
    /** True for special permissions that require a Settings intent instead of the standard permission launcher */
    val isSpecialAccess: Boolean = false,
    /** Which special action to perform (only used when isSpecialAccess = true) */
    val specialAction: SpecialAction? = null,
    /** Additional permissions to request together (e.g. SEND_SMS alongside READ_SMS) */
    val relatedPermissions: List<String> = emptyList()
)
