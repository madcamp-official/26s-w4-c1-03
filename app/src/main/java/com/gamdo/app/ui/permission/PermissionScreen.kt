package com.gamdo.app.ui.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gamdo.app.core.AppPermissions
import com.gamdo.app.ui.theme.GamdoTheme

/**
 * Guidance screen shown when camera / photo permissions are missing.
 * Visual and copy adapt to the [stage]; the button either re-requests or opens
 * system Settings (for the "don't ask again" case).
 */
@Composable
fun PermissionScreen(
    stage: PermissionStage,
    modifier: Modifier = Modifier,
    onRequest: () -> Unit = {},
) {
    val context = LocalContext.current

    val title: String
    val body: String
    val buttonLabel: String
    when (stage) {
        PermissionStage.INTRO -> {
            title = "촬영을 시작할게요"
            body = "감도는 카메라로 사진을 찍고, 갤러리의 사진을 불러와 살려냅니다.\n" +
                "이를 위해 카메라와 사진 접근 권한이 필요해요."
            buttonLabel = "권한 허용"
        }

        PermissionStage.RATIONALE -> {
            title = "권한이 있어야 촬영할 수 있어요"
            body = "카메라와 사진 접근을 허용하면 촬영과 사진 살리기를 이용할 수 있어요.\n" +
                "다시 한 번 허용해 주세요."
            buttonLabel = "권한 허용"
        }

        PermissionStage.BLOCKED -> {
            title = "설정에서 권한을 켜 주세요"
            body = "권한을 다시 묻지 않도록 설정되어 있어요.\n" +
                "설정 화면에서 카메라와 사진 접근을 직접 허용해 주세요."
            buttonLabel = "설정 열기"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 32.dp),
        )
        Button(
            onClick = {
                when (stage) {
                    PermissionStage.BLOCKED ->
                        context.startActivity(AppPermissions.appDetailsSettingsIntent(context))

                    else -> onRequest()
                }
            },
        ) {
            Text(buttonLabel)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0E10)
@Composable
private fun PermissionScreenIntroPreview() {
    GamdoTheme { PermissionScreen(stage = PermissionStage.INTRO) }
}

@Preview(showBackground = true, backgroundColor = 0xFF0E0E10)
@Composable
private fun PermissionScreenBlockedPreview() {
    GamdoTheme { PermissionScreen(stage = PermissionStage.BLOCKED) }
}
