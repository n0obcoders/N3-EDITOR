package com.nitinshukla.notebook.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nitinshukla.notebook.R

/** The N3-Core logo as the launcher shows it: teal field, mint <N> mark, rounded corners. */
@Composable
fun N3Logo(size: Dp = 96.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.26f))) {
        // The adaptive-icon layers are 108dp with a 72dp visible area; scale them to fill the box.
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize().scale(1.5f))
        Image(painterResource(R.drawable.ic_launcher_foreground), "N3-Core logo", Modifier.fillMaxSize().scale(1.5f))
    }
}
