package xyz.hanson.fosslink.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import xyz.hanson.fosslink.ui.screens.AboutScreen
import xyz.hanson.fosslink.ui.screens.HomeScreen
import xyz.hanson.fosslink.ui.screens.SettingsScreen
import xyz.hanson.fosslink.ui.screens.wizard.DiscoveryStep
import xyz.hanson.fosslink.ui.screens.wizard.PairStep
import xyz.hanson.fosslink.ui.screens.wizard.PermissionsStep
import xyz.hanson.fosslink.ui.screens.wizard.SuccessStep
import xyz.hanson.fosslink.ui.screens.wizard.WelcomeStep
import xyz.hanson.fosslink.ui.viewmodel.AppConnectionState
import xyz.hanson.fosslink.ui.viewmodel.MainViewModel

@Composable
fun FossLinkNavHost(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val isSetupComplete by viewModel.isSetupComplete.collectAsState()
    val appState by viewModel.appState.collectAsState()
    val permissions by viewModel.permissions.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()

    val startDestination = if (isSetupComplete) NavRoutes.MAIN else NavRoutes.WIZARD

    // Show bottom nav only for main screens
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomNav = currentRoute in listOf(
        NavRoutes.MAIN_HOME, NavRoutes.MAIN_SETTINGS, NavRoutes.MAIN_ABOUT
    )

    Scaffold(
        bottomBar = {
            if (showBottomNav) {
                BottomNavBar(navController)
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(paddingValues)
        ) {
            // Wizard flow (first-time setup)
            navigation(
                startDestination = NavRoutes.WIZARD_WELCOME,
                route = NavRoutes.WIZARD
            ) {
                composable(NavRoutes.WIZARD_WELCOME) {
                    WelcomeStep(onNext = {
                        navController.navigate(NavRoutes.WIZARD_PERMISSIONS)
                    })
                }
                composable(NavRoutes.WIZARD_PERMISSIONS) {
                    PermissionsStep(
                        permissions = permissions,
                        onRequestPermission = { viewModel.refreshPermissions() },
                        onNext = {
                            navController.navigate(NavRoutes.WIZARD_DISCOVERY)
                        }
                    )
                }
                composable(NavRoutes.WIZARD_DISCOVERY) {
                    // Auto-navigate to pair step when desktop initiates pairing
                    LaunchedEffect(appState) {
                        if (appState is AppConnectionState.PairingRequested) {
                            navController.navigate(NavRoutes.WIZARD_PAIR)
                        }
                    }
                    DiscoveryStep(
                        appState = appState,
                        onSelectDevice = { address, port ->
                            viewModel.connectToDesktop(address, port)
                            navController.navigate(NavRoutes.WIZARD_PAIR)
                        },
                        onAlreadyConnected = {
                            navController.navigate(NavRoutes.WIZARD_SUCCESS) {
                                popUpTo(NavRoutes.WIZARD) { inclusive = false }
                            }
                        }
                    )
                }
                composable(NavRoutes.WIZARD_PAIR) {
                    PairStep(
                        appState = appState,
                        onConfirmPairing = { code ->
                            viewModel.confirmPairingCode(code)
                        },
                        onCancel = {
                            // Disconnect the pending connection
                            val pending = viewModel.desktopConnections.value.keys
                                .firstOrNull { it.startsWith("pending_") }
                            if (pending != null) {
                                viewModel.disconnectDesktop(pending)
                            }
                            navController.popBackStack(NavRoutes.WIZARD_DISCOVERY, false)
                        },
                        onPaired = {
                            navController.navigate(NavRoutes.WIZARD_SUCCESS) {
                                popUpTo(NavRoutes.WIZARD) { inclusive = false }
                            }
                        }
                    )
                }
                composable(NavRoutes.WIZARD_SUCCESS) {
                    val desktopName = when (val state = appState) {
                        is AppConnectionState.Connected -> state.desktops.firstOrNull()?.name ?: "your desktop"
                        is AppConnectionState.PairingRequested -> state.desktopName
                        else -> "your desktop"
                    }
                    SuccessStep(
                        deviceName = desktopName,
                        onDone = {
                            viewModel.completeSetup()
                            navController.navigate(NavRoutes.MAIN) {
                                popUpTo(NavRoutes.WIZARD) { inclusive = true }
                            }
                        }
                    )
                }
            }

            // Main app flow
            navigation(
                startDestination = NavRoutes.MAIN_HOME,
                route = NavRoutes.MAIN
            ) {
                composable(NavRoutes.MAIN_HOME) {
                    HomeScreen(
                        appState = appState,
                        permissions = permissions,
                        onRequestPermission = { viewModel.refreshPermissions() },
                        onConfirmPairing = { code -> viewModel.confirmPairingCode(code) },
                    )
                }
                composable(NavRoutes.MAIN_SETTINGS) {
                    SettingsScreen(
                        appState = appState,
                        pairedDevices = pairedDevices,
                        onDisconnect = { deviceId -> viewModel.disconnectDesktop(deviceId) },
                        onForgetDesktop = { deviceId ->
                            viewModel.forgetDesktop(deviceId)
                            if (viewModel.pairedDeviceIds.value.isEmpty()) {
                                navController.navigate(NavRoutes.WIZARD) {
                                    popUpTo(NavRoutes.MAIN) { inclusive = true }
                                }
                            }
                        },
                        onForgetAll = {
                            viewModel.forgetAllDesktops()
                            navController.navigate(NavRoutes.WIZARD) {
                                popUpTo(NavRoutes.MAIN) { inclusive = true }
                            }
                        }
                    )
                }
                composable(NavRoutes.MAIN_ABOUT) {
                    AboutScreen()
                }
            }
        }
    }
}
