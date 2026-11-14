package com.omr.checker.ui.exams

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omr.checker.data.entity.ExamEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamListScreen(
    viewModel: ExamListViewModel,
    onNavigateToScan: (Long) -> Unit,
    onNavigateToEditKey: (Long) -> Unit,
    onNavigateToTemplates: () -> Unit,
    onCheckForUpdates: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showCreateDialog by remember { mutableStateOf(false) }
    var newExamTitle by remember { mutableStateOf("Midterm Exam") }
    var newExamSection by remember { mutableStateOf("Section A") }
    var selectedTemplateId by remember { mutableLongStateOf(1L) }

    LaunchedEffect(uiState.message) {
        uiState.message?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Exams & Grading", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onCheckForUpdates) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = "Check for Updates")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Default.Assignment, contentDescription = null) },
                    label = { Text("Exams") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToTemplates,
                    icon = { Icon(Icons.Default.Description, contentDescription = null) },
                    label = { Text("Templates") }
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Create Exam") }
            )
        }
    ) { innerPadding ->
        if (uiState.examsWithStats.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No exams created yet.", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { showCreateDialog = true }) {
                        Text("Create Your First Exam")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.examsWithStats, key = { it.exam.id }) { item ->
                    ExamCard(
                        exam = item.exam,
                        onScan = { onNavigateToScan(item.exam.id) },
                        onEditKey = { onNavigateToEditKey(item.exam.id) },
                        onDelete = { viewModel.deleteExam(item.exam) }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create New Exam", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newExamTitle,
                        onValueChange = { newExamTitle = it },
                        label = { Text("Exam / Subject Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newExamSection,
                        onValueChange = { newExamSection = it },
                        label = { Text("Class / Section (e.g. Grade 10-A)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newExamTitle.isNotBlank()) {
                            viewModel.createDefaultExam(newExamTitle, newExamSection, selectedTemplateId) { examId ->
                                showCreateDialog = false
                                newExamTitle = ""
                                newExamSection = ""
                                onNavigateToEditKey(examId)
                            }
                        }
                    }
                ) {
                    Text("Create & Set Key")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ExamCard(
    exam: ExamEntity,
    onScan: () -> Unit,
    onEditKey: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = exam.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (exam.section.isNotBlank()) "Section: ${exam.section} • ${exam.sheetCode}" else exam.sheetCode,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${exam.answerKey.size} Items • ${exam.pointsPerItem} pt each",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onEditKey,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Answer Key")
                }

                Button(
                    onClick = onScan
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan Sheets")
                }
            }
        }
    }
}
