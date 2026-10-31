package com.mihon.presentation.settings.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun PreferenceGroupHeader(
    title: String,
    subtitle: String? = null,
    bold: Boolean = false,
    primary: Boolean = false,
    /** Space below the header, in dp. Lower it to pull the first item closer. */
    bottomPadding: Int = 8,
    /** Optional control shown at the end of the header row, e.g. a switch. */
    widget: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (bold) 20.dp else 14.dp, bottom = bottomPadding.dp)
            .padding(horizontal = PrefsHorizontalPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                    style = if (bold) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    fontWeight = if (bold) FontWeight.Bold else null,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (widget != null) {
                widget()
            }
        }
    }
}
