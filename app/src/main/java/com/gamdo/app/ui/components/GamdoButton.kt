package com.gamdo.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamdo.app.ui.theme.Charcoal600
import com.gamdo.app.ui.theme.OnDarkHigh
import com.gamdo.app.ui.theme.OnDarkMuted
import com.gamdo.app.ui.theme.OnSage
import com.gamdo.app.ui.theme.OutlineDim
import com.gamdo.app.ui.theme.SageButton

/** Filled sage pill — the primary action shape across the app (design: h54, r27). */
@Composable
fun PrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(27.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SageButton,
            contentColor = OnSage,
            disabledContainerColor = Charcoal600,
            disabledContentColor = OnDarkMuted,
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp),
    ) {
        Text(text = text, fontSize = 15.5.sp, fontWeight = FontWeight.Bold)
    }
}

/** Outlined pill — secondary action (design: 1.5dp outline). */
@Composable
fun SecondaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(27.dp),
        border = BorderStroke(1.5.dp, OutlineDim),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = OnDarkHigh),
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp),
    ) {
        Text(text = text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
    }
}
