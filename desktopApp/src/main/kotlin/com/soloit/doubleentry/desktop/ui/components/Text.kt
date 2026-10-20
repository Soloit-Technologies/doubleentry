package com.soloit.doubleentry.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soloit.doubleentry.desktop.ui.AppColors
import com.soloit.doubleentry.domain.Money
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Text

/** Figures line up in columns. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/** An amount in cents. [signed] colors it: money in green, money out red. */
@Composable
fun MoneyText(
    cents: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = FluentTheme.typography.body,
    signed: Boolean = false,
    color: Color = Color.Unspecified,
) {
    val tint = when {
        color != Color.Unspecified -> color
        signed && cents > 0 -> AppColors.positive
        signed && cents < 0 -> AppColors.negative
        else -> Color.Unspecified
    }
    Text(
        if (signed) Money.formatSigned(cents) else Money.format(cents),
        modifier = modifier,
        style = style.tabular(),
        color = tint,
        textAlign = TextAlign.End,
        maxLines = 1,
    )
}

/** The title row of a page or pane, with its commands on the right. */
@Composable
fun PaneHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        // The commands never take the room the title needs.
        val actionsWidth = (maxWidth - 180.dp).coerceAtLeast(0.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = FluentTheme.typography.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = FluentTheme.typography.caption, color = FluentTheme.colors.text.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.widthIn(max = actionsWidth), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** A small heading above a group of rows. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = FluentTheme.typography.bodyStrong, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Shown in place of content that doesn't exist yet. */
@Composable
fun EmptyState(title: String, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 360.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = FluentTheme.typography.subtitle, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(text, color = FluentTheme.colors.text.text.secondary, textAlign = TextAlign.Center)
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                action()
            }
        }
    }
}

/** A label and its value on one line, as in a details pane. */
@Composable
fun DetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = FluentTheme.colors.text.text.secondary, modifier = Modifier.weight(0.4f))
        Text(value, modifier = Modifier.weight(0.6f))
    }
}
