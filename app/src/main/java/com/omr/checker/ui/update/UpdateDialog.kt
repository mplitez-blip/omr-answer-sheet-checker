package com.omr.checker.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omr.checker.BuildConfig

@Composable
fun UpdateDialog(
    viewModel: UpdateViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    if (!uiState.showDialog) return

    val update = uiState.availableUpdate

    when {
        uiState.isChecking -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissDialog() },
                title = { Text("Checking for Updates") },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Text("Checking GitHub Releases...")
                    }
                },
                confirmButton = {}
            )
        }

        update != null -> {
            AlertDialog(
                onDismissRequest = {
                    if (!uiState.isDownloading) viewModel.dismissDialog()
                },
                icon = {
                    Icon(
                        Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = {
                    Text(
                        text = "New Update Available!",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = update.latestVersion,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Current: v${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Release Notes:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 140.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = update.changelog,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }

                        if (uiState.isDownloading) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Downloading APK: ${(uiState.downloadProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { uiState.downloadProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.downloadAndInstall(context) },
                        enabled = !uiState.isDownloading
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (uiState.isDownloading) "Downloading..." else "Update Now")
                    }
                },
                dismissButton = {
                    if (!uiState.isDownloading) {
                        OutlinedButton(onClick = { viewModel.dismissDialog() }) {
                            Text("Not Now")
                        }
                    }
                }
            )
        }

        uiState.upToDateMessage != null -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissDialog() },
                icon = {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = { Text("App is Up to Date") },
                text = { Text(uiState.upToDateMessage ?: "") },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissDialog() }) {
                        Text("OK")
                    }
                }
            )
        }

        uiState.errorMessage != null -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissDialog() },
                title = { Text("Update Check") },
                text = { Text(uiState.errorMessage ?: "") },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissDialog() }) {
                        Text("OK")
                    }
                }
            )
        }
    }
}
