/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package dev.abhi.arvex.ui.screens.settings.fragments

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.abhi.arvex.R
import dev.abhi.arvex.constants.SlimNavBarKey
import dev.abhi.arvex.ui.component.SwitchPreference
import dev.abhi.arvex.utils.rememberPreference

@Composable
fun ColumnScope.AppearanceMiscFrag() {
    val (slimNav, onSlimNavChange) = rememberPreference(SlimNavBarKey, defaultValue = false)

    SwitchPreference(
        title = { Text(stringResource(R.string.slim_navbar_title)) },
        description = stringResource(R.string.slim_navbar_description),
        icon = { Icon(Icons.Rounded.MoreHoriz, null) },
        checked = slimNav,
        onCheckedChange = onSlimNavChange
    )
}
