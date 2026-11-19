package com.aura.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import com.aura.launcher.data.AppEntry
import com.aura.launcher.data.AppRepository
import com.aura.launcher.data.AppShortcut
import com.aura.launcher.ui.theme.Aura

/** What the press-and-hold menu on an app can do. */
data class AppMenuActions(
    val launchShortcut: (AppShortcut) -> Unit = {},
    val toggleFavorite: (AppEntry) -> Unit = {},
    val toggleHidden: (AppEntry) -> Unit = {},
    val appInfo: (AppEntry) -> Unit = {},
    val uninstall: (AppEntry) -> Unit = {},
    val isFavorite: (AppEntry) -> Boolean = { false },
    val isHidden: (AppEntry) -> Boolean = { false },
    val toggleDock: ((AppEntry) -> Unit)? = null,
    val isInDock: (AppEntry) -> Boolean = { false },
    /** Where shortcuts come from; null means the app's real shortcuts from Android. */
    val loadShortcuts: (suspend (AppEntry) -> List<AppShortcut>)? = null,
)

/**
 * Press-and-hold menu for an app: its shortcuts first ("New message", "Navigate home"…), then
 * favourite, hide, App info and Uninstall. Shortcuts are loaded only when the menu opens.
 */
@Composable
fun AppMenu(
    entry: AppEntry,
    repo: AppRepository,
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: AppMenuActions,
) {
    val shortcuts by produceState(emptyList<AppShortcut>(), entry.key, expanded) {
        val load = actions.loadShortcuts
        value = when {
            !expanded -> emptyList()
            load != null -> load(entry)
            else -> repo.shortcuts(entry)
        }
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = Aura.Menu,
        border = BorderStroke(1.dp, Aura.Stroke),
    ) {
        Text(
            entry.label, color = Aura.InkDim, fontSize = 13.sp, fontFamily = Aura.Sans, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
        )
        shortcuts.forEach { sc ->
            DropdownMenuItem(
                text = { Text(sc.label, color = Aura.Ink, fontFamily = Aura.Sans, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { ShortcutIcon(sc, repo) },
                onClick = { onDismiss(); actions.launchShortcut(sc) },
            )
        }
        if (shortcuts.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Aura.Stroke)
        val favorite = actions.isFavorite(entry)
        val hidden = actions.isHidden(entry)
        MenuItem(if (favorite) "Remove from favorites" else "Add to favorites") { onDismiss(); actions.toggleFavorite(entry) }
        actions.toggleDock?.let { toggle ->
            MenuItem(if (actions.isInDock(entry)) "Remove from dock" else "Add to dock") { onDismiss(); toggle(entry) }
        }
        MenuItem(if (hidden) "Show in app drawer" else "Hide from app drawer") { onDismiss(); actions.toggleHidden(entry) }
        MenuItem("App info") { onDismiss(); actions.appInfo(entry) }
        MenuItem("Uninstall") { onDismiss(); actions.uninstall(entry) }
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label, color = Aura.Ink, fontFamily = Aura.Sans) }, onClick = onClick)
}

@Composable
private fun ShortcutIcon(shortcut: AppShortcut, repo: AppRepository) {
    val bmp by produceState(null as androidx.compose.ui.graphics.ImageBitmap?, shortcut.id, shortcut.packageName) {
        value = repo.shortcutIcon(shortcut)
    }
    Box(Modifier.size(28.dp).clip(CircleShape).background(Aura.Glass)) {
        bmp?.let { Image(it, contentDescription = null, modifier = Modifier.size(28.dp)) }
    }
}
