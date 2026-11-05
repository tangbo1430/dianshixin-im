package dev.icos.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.icos.app.R

@Composable
internal fun FirstRunWelcomeSection() {
    val compact = LocalConfiguration.current.screenHeightDp < 700
    Column(
        Modifier.fillMaxWidth().padding(top = if (compact) 8.dp else 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.dsx_logo),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.size(72.dp),
            contentScale = ContentScale.Fit,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "让每一笔，都清楚",
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 32.sp, lineHeight = 40.sp),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "本机记账，账号同步",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 26.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Image(
            painter = painterResource(R.drawable.first_run_wallet),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).aspectRatio(3f / 2f),
            contentScale = ContentScale.Fit,
        )
    }
}
