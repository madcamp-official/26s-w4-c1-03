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
import com.gamdo.app.ui.theme.Ink700
import com.gamdo.app.ui.theme.TextHi
import com.gamdo.app.ui.theme.TextLow
import com.gamdo.app.ui.theme.OnAmber
import com.gamdo.app.ui.theme.Outline
import com.gamdo.app.ui.theme.Amber

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
            containerColor = Amber,
            contentColor = OnAmber,
            disabledContainerColor = Ink700,
            disabledContentColor = TextLow,
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
        border = BorderStroke(1.5.dp, Outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextHi),
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp),
    ) {
        Text(text = text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
    }
}
