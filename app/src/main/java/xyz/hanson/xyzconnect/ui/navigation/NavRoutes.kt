package xyz.hanson.fosslink.ui.navigation

object NavRoutes {
    const val WIZARD = "wizard"
    const val WIZARD_WELCOME = "wizard/welcome"
    const val WIZARD_PERMISSIONS = "wizard/permissions"
    const val WIZARD_SAMSUNG = "wizard/samsung"
    const val WIZARD_DISCOVERY = "wizard/discovery"
    const val WIZARD_PAIR = "wizard/pair"
    const val WIZARD_SUCCESS = "wizard/success"

    /** Standalone Samsung deep-sleep acknowledgment screen, used outside the
     *  wizard. Mode arg: "forced" (upgrade install, must ack to continue) or
     *  "settings" (entered from settings tap, Close always enabled, no ack). */
    const val SAMSUNG_DEEP_SLEEP = "samsung_deep_sleep/{mode}"
    fun samsungDeepSleep(mode: String): String = "samsung_deep_sleep/$mode"
    const val SAMSUNG_MODE_FORCED = "forced"
    const val SAMSUNG_MODE_SETTINGS = "settings"

    const val MAIN = "main"
    const val MAIN_HOME = "main/home"
    const val MAIN_SETTINGS = "main/settings"
    const val MAIN_ABOUT = "main/about"
}
