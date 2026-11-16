package com.omr.checker.ui.exams

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Grading
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.omr.checker.data.entity.ScanResultEntity
import com.omr.checker.ui.papers.PaperItemUiModel
import com.omr.checker.ui.papers.PaperSortOption
import com.omr.checker.ui.papers.ScannedPapersViewModel
import com.omr.checker.ui.preview.FullscreenSheetViewerDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamDetailScreen(
    viewModel: ScannedPapersViewModel,
    examId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToScan: (Long) -> Unit,
    onNavigateToEditKey: (Long) -> Unit,
    onNavigateToResult: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var paperToDelete by remember { mutableStateOf<ScanResultEntity?>(null) }
    var paperToPreview by remember { mutableStateOf<ScanResultEntity?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }

    LaunchedEffect(examId) {
        viewModel.setSelectedExam(examId)
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    val exam = uiState.currentExam
    val examTitle = exam?.title ?: "Exam Details"
    val sectionInfo = if (exam != null && exam.section.isNotBlank()) "Section: ${exam.section} • ${exam.sheetCode}" else (exam?.sheetCode ?: "")

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Exams")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = examTitle,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (sectionInfo.isNotBlank()) {
                            Text(
                                text = sectionInfo,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { onNavigateToEditKey(examId) }) {
                        Icon(Icons.Default.Key, contentDescription = "Answer Key", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        },
        floatingActionButton = {
            if (uiState.papers.isNotEmpty() || uiState.searchQuery.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { onNavigateToScan(examId) },
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                    text = { Text("Scan Sheet") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { innerPadding ->
        if (uiState.papers.isEmpty() && uiState.searchQuery.isEmpty()) {
            // Empty State: Prompt directly to scan papers or review key
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.size(72.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.DocumentScanner,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }

                        Text(
                            text = "No Papers Scanned Yet",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = "You haven't scanned any answer sheets for ${exam?.section?.ifBlank { examTitle } ?: examTitle} yet.\nTap below to start grading student sheets with your camera.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        if (uiState.currentTemplateName.isNotBlank() || exam != null) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Template: ${uiState.currentTemplateName.ifBlank { "Answer Sheet" }} (${exam?.answerKey?.size ?: 0} Items)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Code: ${exam?.sheetCode ?: ""} • ${exam?.pointsPerItem ?: 1.0f} pt each",
                                        fontSize = 11.sp,
                                        color = Color.DarkGray
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Button(
                            onClick = { onNavigateToScan(examId) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Start Scanning Papers")
                        }

                        OutlinedButton(
                            onClick = { onNavigateToEditKey(examId) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Review Answer Key (${exam?.answerKey?.size ?: 0} Items)")
                        }
                    }
                }
            }
        } else {
            // Content List with Search, Filter & Summary Stats
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Search Bar & Sort Row
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = uiState.searchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            placeholder = { Text("Search student name or code...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                            trailingIcon = {
                                if (uiState.searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )

                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Default.Sort, contentDescription = "Sort Options")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                PaperSortOption.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = option.displayName,
                                                fontWeight = if (uiState.sortOption == option) FontWeight.Bold else FontWeight.Normal,
                                                color = if (uiState.sortOption == option) MaterialTheme.colorScheme.primary else Color.Unspecified
                                            )
                                        },
                                        onClick = {
                                            viewModel.updateSortOption(option)
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Metrics Summary Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            ExamStatItem(label = "Papers", value = uiState.stats.totalCount.toString())
                            val mpsValue = "${String.format(Locale.US, "%.1f", uiState.stats.averagePercentage)}%"
                            ExamStatItem(label = "MPS", value = mpsValue)
                            ExamStatItem(label = "Pass Rate", value = "${uiState.stats.passRate.toInt()}%")
                            ExamStatItem(label = "High / Low", value = "${uiState.stats.highestScore.toInt()} / ${uiState.stats.lowestScore.toInt()}")
                        }
                    }
                }

                // 3. Papers List
                if (uiState.papers.isEmpty() && uiState.searchQuery.isNotEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No students match \"${uiState.searchQuery}\"",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    items(uiState.papers, key = { it.result.id }) { item ->
                        PaperItemCard(
                            item = item,
                            onReview = { onNavigateToResult(item.result.id) },
                            onPreviewImage = { paperToPreview = item.result },
                            onDelete = { paperToDelete = item.result }
                        )
                    }
                }

                // Bottom padding to clear floating action button
                item {
                    Spacer(modifier = Modifier.height(64.dp))
                }
            }
        }
    }

    // Zoomable Image Preview Dialog
    paperToPreview?.let { paper ->
        FullscreenSheetViewerDialog(
            imagePath = paper.scannedImagePath,
            title = paper.studentName.ifBlank { "Scanned Paper #${paper.id}" },
            subtitle = "Score: ${paper.score.toInt()} / ${paper.totalPoints.toInt()} (${paper.percentage.toInt()}%)",
            subtitleColor = if (paper.percentage >= 60f) Color(0xFF00E676) else Color(0xFFFF5252),
            onDismiss = { paperToPreview = null }
        )
    }

    // Delete Confirmation Dialog
    paperToDelete?.let { paper ->
        AlertDialog(
            onDismissRequest = { paperToDelete = null },
            title = { Text("Delete Scanned Paper?") },
            text = {
                val student = paper.studentName.ifBlank { "Paper #${paper.id}" }
                Text("Are you sure you want to delete the submission for $student (Score: ${paper.score.toInt()}/${paper.totalPoints.toInt()})? This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteScan(paper)
                        paperToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { paperToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ExamStatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PaperItemCard(
    item: PaperItemUiModel,
    onReview: () -> Unit,
    onPreviewImage: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val result = item.result
    val studentName = result.studentName.ifBlank { "Student #${result.id}" }
    val formattedDate = remember(result.scannedAt) {
        val sdf = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
        sdf.format(Date(result.scannedAt))
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onReview() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: Student name, Code & Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = studentName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (result.studentCode.isNotBlank()) {
                        Text(
                            text = "ID: ${result.studentCode}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = formattedDate,
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = Color.Gray.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Body Row: Score on left, Graded sheet thumbnail on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Score presentation
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val passBg = if (item.isPass) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                        val passColor = if (item.isPass) Color(0xFF2E7D32) else Color(0xFFC62828)

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = passBg,
                            border = BorderStroke(1.dp, passColor.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = "${result.percentage.toInt()}%",
                                color = passColor,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column {
                            Text(
                                text = "${result.score.toInt()} / ${result.totalPoints.toInt()} pts",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = if (item.isPass) "Passed" else "Needs Improvement",
                                fontSize = 11.sp,
                                color = if (item.isPass) Color(0xFF2E7D32) else Color(0xFFC62828),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Thumbnail with click-to-zoom
                if (!result.scannedImagePath.isNullOrEmpty()) {
                    val file = remember(result.scannedImagePath) { File(result.scannedImagePath) }
                    if (file.exists()) {
                        Box(
                            modifier = Modifier
                                .width(70.dp)
                                .aspectRatio(1f / 1.414f)
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, Color.LightGray, RoundedCornerShape(6.dp))
                                .clickable { onPreviewImage() }
                        ) {
                            AsyncImage(
                                model = file,
                                contentDescription = "Graded Sheet Thumbnail",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            // Mini Zoom badge overlay
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(2.dp)
                                    .size(18.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.ZoomIn,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!result.scannedImagePath.isNullOrEmpty()) {
                    OutlinedButton(
                        onClick = onPreviewImage,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.ZoomIn, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("View Sheet")
                    }
                }

                Button(onClick = onReview) {
                    Icon(Icons.Default.Grading, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Grade Breakdown")
                }
            }
        }
    }
}
