/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O﻿ute﻿rTu﻿ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package dev.abhi.arvex.ui.screens.settings

import android.content.ClipData
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.abhi.arvex.BuildConfig
import dev.abhi.arvex.R
import dev.abhi.arvex.constants.ENABLE_FFMETADATAEX
import dev.abhi.arvex.constants.LYRIC_FETCH_TIMEOUT
import dev.abhi.arvex.constants.LastUpdateCheckKey
import dev.abhi.arvex.constants.LastVersionKey
import dev.abhi.arvex.constants.MAX_LM_SCANNER_JOBS
import dev.abhi.arvex.constants.OOBE_VERSION
import dev.abhi.arvex.constants.SNACKBAR_VERY_SHORT
import dev.abhi.arvex.constants.TopBarInsets
import dev.abhi.arvex.ui.component.ColumnWithContentPadding
import dev.abhi.arvex.ui.component.ContributorCard
import dev.abhi.arvex.ui.component.ContributorInfo
import dev.abhi.arvex.ui.component.ContributorType.CUSTOM
import dev.abhi.arvex.ui.component.PreferenceEntry
import dev.abhi.arvex.ui.component.SettingsClickToReveal
import dev.abhi.arvex.ui.component.button.IconButton
import dev.abhi.arvex.ui.component.button.IconLabelButton
import dev.abhi.arvex.ui.utils.backToMain
import dev.abhi.arvex.utils.rememberPreference
import dev.abhi.arvex.utils.scanners.FFmpegScanner
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import java.text.DateFormat.getDateTimeInstance
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboard.current
    val uriHandler = LocalUriHandler.current

    val showDebugInfo = BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "userdebug"

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.launcher_monochrome),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground, BlendMode.SrcIn),
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(NavigationBarDefaults.Elevation))
                .clickable { }
        )

        Row(
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) | ${BuildConfig.FLAVOR}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(Modifier.width(4.dp))

            if (showDebugInfo) {
                Spacer(Modifier.width(4.dp))

                Text(
                    text = BuildConfig.BUILD_TYPE.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.secondary,
                            shape = CircleShape
                        )
                        .padding(
                            horizontal = 6.dp,
                            vertical = 2.dp
                        )
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 16.dp)
        ) {
            IconLabelButton(
                text = "GitHub",
                painter = painterResource(R.drawable.github),
                onClick = { uriHandler.openUri("https://github.com/abhianand123") },
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            IconLabelButton(
                text = "Instagram",
                icon = Icons.Rounded.AccountCircle,
                onClick = { uriHandler.openUri("https://instagram.com/chessbasebgs") },
                modifier = Modifier.padding(horizontal = 8.dp)
            )


        }

        Spacer(Modifier.height(96.dp))

        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
                // Removed Attribution and OSS Licenses sections as per rebranding request
                // PreferenceEntry(
                //     title = { Text(stringResource(R.string.attribution_title)) },
                //     onClick = {
                //         navController.navigate("settings/about/attribution")
                //     }
                // )
                // PreferenceEntry(
                //     title = { Text(stringResource(R.string.oss_licenses_title)) },
                //     onClick = {
                //         navController.navigate("settings/about/oss_licenses")
                //     }
                // )
            Spacer(modifier = Modifier.height(16.dp))

            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                PreferenceEntry(
                    title = { Text(stringResource(R.string.help_bug_report_action)) },
                    onClick = {
                        uriHandler.openUri("https://github.com/abhianand123")
                    }
                )
                PreferenceEntry(
                    title = { Text(stringResource(R.string.help_support_forum)) },
                    onClick = {
                        uriHandler.openUri("https://github.com/abhianand123")
                    }
                )
                PreferenceEntry(
                    title = { Text("Email: abhi55and@gmail") },
                    onClick = {
                        val clipData = ClipData.newPlainText(
                            context.getString(R.string.app_name),
                            AnnotatedString("abhi55and@gmail.com")
                        )
                        clipboardManager.nativeClipboard.setPrimaryClip(clipData)
                    }
                )
                PreferenceEntry(
                    title = { Text("Email: 12345abhianand12345@gmail") },
                    onClick = {
                        val clipData = ClipData.newPlainText(
                            context.getString(R.string.app_name),
                            AnnotatedString("12345abhianand12345@gmail.com")
                        )
                        clipboardManager.nativeClipboard.setPrimaryClip(clipData)
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                SettingsClickToReveal(stringResource(R.string.app_info_title)) {
                    val info = mutableListOf<String>(
                        "FFMetadataEx: $ENABLE_FFMETADATAEX",
                        "LM scanner concurrency: $MAX_LM_SCANNER_JOBS",
                        "LYRIC_FETCH_TIMEOUT: $LYRIC_FETCH_TIMEOUT",
                        "OOBE_VERSION: $OOBE_VERSION",
                        "LYRIC_FETCH_TIMEOUT: $LYRIC_FETCH_TIMEOUT",
                        "SNACKBAR_VERY_SHORT: $SNACKBAR_VERY_SHORT"
                    )
                    if (ENABLE_FFMETADATAEX) {
                        info.add("FFMetadataEx version: ${FFmpegScanner.VERSION_STRING}")
                        info.add("FFmpeg version: ${FfmpegLibrary.getVersion()}")
                        info.add("FFmpeg isAvailable: ${FfmpegLibrary.isAvailable()}")
                    }

                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        info.forEach {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                SettingsClickToReveal(stringResource(R.string.device_info_title)) {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val info = mutableListOf<String>(
                            "Device: ${Build.BRAND} ${Build.DEVICE} (${Build.MODEL})",
                            "Manufacturer: ${Build.MANUFACTURER}",
                            "HW: ${Build.BOARD} (${Build.HARDWARE})",
                            "ABIs: ${Build.SUPPORTED_ABIS.joinToString()})",
                            "Android: ${Build.VERSION.SDK_INT} (${Build.ID})",
                            Build.DISPLAY,
                            Build.PRODUCT,
                            Build.FINGERPRINT,
                            Build.VERSION.SECURITY_PATCH
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            info.add("SOC: ${Build.SOC_MODEL} (${Build.SOC_MANUFACTURER})")
                            info.add("SKU: ${Build.SKU} (${Build.ODM_SKU})")
                        }

                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            info.forEach {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (ENABLE_FFMETADATAEX) {
                ContributorCard(
                    contributor = ContributorInfo(
                        name = "FFmpeg",
                        description = stringResource(R.string.ffmpeg_lgpl),
                        type = listOf(CUSTOM),
                        url = "https://github.com/OuterTune/ffMetadataEx/blob/main/Modules.md"
                    )
                )
            }
        }

    }

    TopAppBar(
        title = { Text(stringResource(R.string.about)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null
                )
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior
    )
}
