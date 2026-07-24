package com.gamdo.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gamdo.app.data.AppContainer
import com.gamdo.app.ui.album.AlbumScreen
import com.gamdo.app.ui.camera.CameraScreen
import com.gamdo.app.ui.onboarding.OnboardingScreen
import com.gamdo.app.ui.result.ResultScreen
import kotlinx.coroutines.launch

/**
 * App navigation graph (t2 flow): onboarding → camera(home) → album → result.
 * Start destination depends on whether onboarding has been completed.
 */
@Composable
fun GamdoNavHost(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    val startDestination by produceState<String?>(initialValue = null, container) {
        value = if (container.settingsRepository.isOnboardingDone()) Routes.CAMERA else Routes.ONBOARDING
    }

    val start = startDestination
    if (start == null) {
        // Brief decision gate while we read the onboarding flag.
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {}
        return
    }

    NavHost(
        navController = navController,
        startDestination = start,
        modifier = modifier,
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    scope.launch { container.settingsRepository.setOnboardingDone() }
                    navController.navigate(Routes.CAMERA) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.CAMERA) {
            CameraScreen(
                onOpenAlbum = { navController.navigate(Routes.ALBUM) },
                onCaptured = { navController.navigate(Routes.result("demo")) },
            )
        }

        composable(Routes.ALBUM) {
            AlbumScreen(
                onBack = { navController.popBackStack() },
                onOpenPhoto = { captureId -> navController.navigate(Routes.result(captureId)) },
            )
        }

        composable(
            route = Routes.RESULT,
            arguments = listOf(navArgument(Routes.ARG_CAPTURE_ID) { type = NavType.StringType }),
        ) { entry ->
            val captureId = entry.arguments?.getString(Routes.ARG_CAPTURE_ID).orEmpty()
            ResultScreen(
                captureId = captureId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
