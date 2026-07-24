package com.gamdo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gamdo.app.GamdoApplication
import com.gamdo.app.ui.permission.PermissionGate
import com.gamdo.app.ui.theme.GamdoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Root composable. Day 1: gates the app behind camera/photo permissions (§1-2);
 * once granted it shows a placeholder that also surfaces the §1-3 local-data
 * state (device UUID prefix + seeded preset count) for on-device verification.
 * Navigation (§1-4) replaces the placeholder body.
 */
@Composable
fun GamdoApp() {
    GamdoTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            PermissionGate(modifier = Modifier.padding(innerPadding)) {
                MainPlaceholder()
            }
        }
    }
}

@Composable
private fun MainPlaceholder(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as GamdoApplication).container
    }

    // Verification for §1-3: device UUID is stable across restarts and the 6
    // presets are loaded into Room. Seeding is idempotent, so calling it here
    // makes the shown count deterministic regardless of the startup race.
    val status by produceState(initialValue = "불러오는 중…", container) {
        value = withContext(Dispatchers.IO) {
            val deviceId = container.deviceIdStore.getOrCreate()
            container.presetRepository.seedFromAssetsIfEmpty()
            val presetCount = container.presetRepository.count()
            "device ${deviceId.take(8)} · presets $presetCount"
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "감도 (GAMDO)",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
