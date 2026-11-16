package com.omr.checker.ui.exams

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
    initialTemplateId: Long? = null,
    onNavigateToExamDetail: (Long) -> Unit,
    onNavigateToEditKey: (Long) -> Unit,
    onNavigateToTemplates: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showCreateDialog by remember { mutableStateOf(false) }
    var newExamTitle by remember { mutableStateOf("Midterm Exam") }
    var newExamSection by remember { mutableStateOf("Section A") }
    var selectedTemplateId by remember { mutableLongStateOf(1L) }
    var examToDelete by remember { mutableStateOf<ExamWithStats?>(null) }
    var examToDuplicate by remember { mutableStateOf<ExamWithStats?>(null) }
    var duplicateExamTitle by remember { mutableStateOf("") }
    var duplicateSectionName by remember { mutableStateOf("") }

    LaunchedEffect(initialTemplateId) {
        if (initialTemplateId != null && initialTemplateId > 0) {
            selectedTemplateId = initialTemplateId
            showCreateDialog = true
        }
    }

    LaunchedEffect(uiState.templates) {
        if (uiState.templates.isNotEmpty() && !uiState.templates.any { it.id == selectedTemplateId }) {
            val defaultTpl = uiState.templates.find { it.isDefault } ?: uiState.templates.firstOrNull()
            if (defaultTpl != null) {
                selectedTemplateId = defaultTpl.id
            }
        }
    }

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
                title = { Text("Exams & Grading", fontWeight = FontWeight.Bold) }
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
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToSettings,
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") }
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
                        item = item,
                        onClick = { onNavigateToExamDetail(item.exam.id) },
                        onDuplicate = {
                            examToDuplicate = item
                            duplicateExamTitle = item.exam.title
                            duplicateSectionName = when {
                                item.exam.section.trim().endsWith("A", ignoreCase = true) -> item.exam.section.trim().dropLast(1) + "B"
                                item.exam.section.isNotBlank() -> ""
                                else -> ""
                            }
                        },
                        onDelete = { examToDelete = item }
                    )
                }
            }
        }
    }

    // Duplicate Exam Dialog
    examToDuplicate?.let { item ->
        val original = item.exam
        AlertDialog(
            onDismissRequest = { examToDuplicate = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Duplicate Exam", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Copy this exam for another class or section. The full answer key (${original.answerKey.size} items) and template will be preserved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Source Exam: ${original.title}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            if (original.section.isNotBlank()) {
                                Text("Original Section: ${original.section}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("Template: ${item.templateName} (${original.answerKey.size} items)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    OutlinedTextField(
                        value = duplicateExamTitle,
                        onValueChange = { duplicateExamTitle = it },
                        label = { Text("Exam Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = duplicateSectionName,
                        onValueChange = { duplicateSectionName = it },
                        label = { Text("New Class / Section (e.g. Section B)") },
                        placeholder = { Text("e.g. Section B") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "A new unique sheet code will be assigned. Scanned submissions will start fresh at 0.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (duplicateExamTitle.isNotBlank()) {
                            viewModel.duplicateExam(
                                originalExam = original,
                                newSection = duplicateSectionName,
                                newTitle = duplicateExamTitle
                            )
                            examToDuplicate = null
                        }
                    }
                ) {
                    Text("Duplicate Exam")
                }
            },
            dismissButton = {
                TextButton(onClick = { examToDuplicate = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Exam Confirmation Dialog
    examToDelete?.let { item ->
        val exam = item.exam
        AlertDialog(
            onDismissRequest = { examToDelete = null },
            title = { Text("Delete Exam?", fontWeight = FontWeight.Bold) },
            text = {
                val papersWarning = if (item.totalScans > 0) {
                    " This will permanently delete all ${item.totalScans} scanned paper(s) associated with this exam."
                } else ""
                val sectionText = if (exam.section.isNotBlank()) " (${exam.section})" else ""
                Text("Are you sure you want to delete \"${exam.title}\"$sectionText?$papersWarning This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteExam(exam)
                        examToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { examToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create New Exam", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
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

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Choose Answer Sheet Template:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    if (uiState.templates.isEmpty()) {
                        Text(
                            text = "Loading templates...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        uiState.templates.forEach { template ->
                            val isSelected = template.id == selectedTemplateId
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(0.5.dp, Color.LightGray),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedTemplateId = template.id }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { selectedTemplateId = template.id }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = template.name,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                                fontSize = 14.sp
                                            )
                                            if (template.isDefault) {
                                                Text(
                                                    text = "DEFAULT",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        Text(
                                            text = "${template.totalItems} Questions (${template.optionsPerItem} Options: A-${'A' + template.optionsPerItem - 1})",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
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
    item: ExamWithStats,
    onClick: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
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
                        text = item.exam.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (item.exam.section.isNotBlank()) "Section: ${item.exam.section} • ${item.exam.sheetCode}" else item.exam.sheetCode,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Sheet: ${item.templateName.ifBlank { "Template" }} (${item.exam.answerKey.size} Items) • ${item.exam.pointsPerItem} pt each",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (item.totalScans > 0) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = if (item.totalScans > 0) {
                                val mpsStr = item.mps?.let { " • MPS: ${String.format(java.util.Locale.US, "%.1f", it)}%" } ?: ""
                                "📄 ${item.totalScans} Papers Scanned$mpsStr"
                            } else {
                                "📄 0 Papers Scanned • Tap to start"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (item.totalScans > 0) MaterialTheme.colorScheme.primary else Color.Gray,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    IconButton(onClick = onDuplicate) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Duplicate for another section",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
