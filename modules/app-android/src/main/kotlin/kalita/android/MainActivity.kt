// SPDX-FileCopyrightText: 2026 Oleg Ekhlakov <o.ekhlakov@protonmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package kalita.android

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Placeholder screen (dev_plan.md's Этап 8 addendum, ahead of the real Этап 10 Compose UI): proves
 * the build → sign → install path end to end on a real device before stage 10's actual screens
 * (FR-A1…FR-A8) exist. Replaced wholesale once that stage starts.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PlaceholderScreen()
        }
    }
}

@Composable
private fun PlaceholderScreen() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Kalita", style = MaterialTheme.typography.headlineLarge)
                Text("Экран появится на Этапе 10", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Preview
@Composable
private fun PlaceholderScreenPreview() {
    PlaceholderScreen()
}
