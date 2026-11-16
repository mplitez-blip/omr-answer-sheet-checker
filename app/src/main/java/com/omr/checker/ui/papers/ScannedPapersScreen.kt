package com.omr.checker.ui.papers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Grading
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.omr.checker.data.entity.ScanResultEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannedPapersScreen(
    viewModel: ScannedPapersViewModel,
    initialExamId: Long? = null,
    onNavigateToResult: (Long) -> Unit,
    onNavigateToScan: (Long) -> Unit,
    onNavigateToExams: () -> Unit,
    onNavigateToTemplates: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var paperToDelete by remember { mutableStateOf<ScanResultEntity?>(null) }
    var paperToPreview by remember { mutableStateOf<ScanResultEntity?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showExamPickDialog by remember { mutableStateOf(false) }

    LaunchedEffect(initialExamId) {
        if (initialExamId != null && initialExamId > 0) {
            viewModel.setSelectedExam(initialExamId)
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
                title = {
                    Column {
                        Text("Scans", fontWeight = FontWeight.Bold)
                        val sub = when {
                            uiState.selectedExamId != null -> {
                                val currentExam = uiState.exams.find { it.id == uiState.selectedExamId }
                                currentExam?.title ?: "Filtered Exam"
                            }
                            else -> "All Exam Submissions"
                        }
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToExams,
                    icon = { Icon(Icons.Default.Assignment, contentDescription = null) },
                    label = { Text("Exams") }
                )
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Default.DocumentScanner, contentDescription = null) },
                    label = { Text("Scans") }
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
                onClick = {
                    val examId = uiState.selectedExamId
                    if (examId != null && examId > 0) {
                        onNavigateToScan(examId)
                    } else if (uiState.exams.isNotEmpty()) {
                        if (uiState.exams.size == 1) {
                            onNavigateToScan(uiState.exams.first().id)
                        } else {
                            showExamPickDialog = true
                        }
                    } else {
                        onNavigateToExams()
                    }
                },
                icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                text = { Text("Scan Paper") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. Exam Filter Horizontal Chips
            if (uiState.exams.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item {
                        FilterChip(
                            selected = uiState.selectedExamId == null,
                            onClick = { viewModel.setSelectedExam(null) },
                            label = { Text("All Exams") },
                            colors = FilterChipDefaults.filterChipColors()
                        )
                    }
                    items(uiState.exams, key = { it.id }) { exam ->
                        FilterChip(
                            selected = uiState.selectedExamId == exam.id,
                            onClick = { viewModel.setSelectedExam(exam.id) },
                            label = {
                                Text(
                                    text = if (exam.section.isNotBlank()) "${exam.title} (${exam.section})" else exam.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }

            // 2. Search & Sort Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                        fontWeight = if (uiState.sortOption == option) FontWeight.Bold else FontWeight.Normal
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

            // 3. Summary Stats Banner (if papers exist)
            if (uiState.stats.totalCount > 0) {
                SummaryStatsCard(
                    stats = uiState.stats,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            // 4. Papers List / Empty State
            if (uiState.papers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DocumentScanner,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            modifier = Modifier.size(72.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (uiState.searchQuery.isNotEmpty()) "No matching papers found" else "No papers scanned yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (uiState.searchQuery.isNotEmpty()) "Try a different search term" else "Scan an answer sheet with your camera to view graded submissions here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        if (uiState.exams.isNotEmpty()) {
                            Button(
                                onClick = {
                                    val examId = uiState.selectedExamId ?: uiState.exams.first().id
                                    onNavigateToScan(examId)
                                }
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Scan Answer Sheet")
                            }
                        } else {
                            Button(onClick = onNavigateToExams) {
                                Text("Create an Exam First")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.papers, key = { it.result.id }) { item ->
                        ScannedPaperCard(
                            item = item,
                            onReview = { onNavigateToResult(item.result.id) },
                            onPreviewImage = { paperToPreview = item.result },
                            onDelete = { paperToDelete = item.result }
                        )
                    }
                }
            }
        }
    }

    // Full-Screen Zoomable Sheet Inspection Dialog
    paperToPreview?.let { paper ->
        FullscreenSheetInspectionDialog(
            imagePath = paper.scannedImagePath,
            studentName = paper.studentName.ifBlank { "Scanned Paper #${paper.id}" },
            score = paper.score,
            totalPoints = paper.totalPoints,
            percentage = paper.percentage,
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
                    }
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

    // Exam Selection Dialog (When FAB clicked while on "All Exams")
    if (showExamPickDialog) {
        AlertDialog(
            onDismissRequest = { showExamPickDialog = false },
            title = { Text("Select Exam to Scan", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.exams.forEach { exam ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showExamPickDialog = false
                                    onNavigateToScan(exam.id)
                                }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(exam.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                if (exam.section.isNotBlank()) {
                                    Text("Section: ${exam.section}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExamPickDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SummaryStatsCard(
    stats: ScannedPapersSummaryStats,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatColumn(
                label = "Papers",
                value = stats.totalCount.toString()
            )
            StatColumn(
                label = "Average",
                value = "${stats.averagePercentage.toInt()}%"
            )
            StatColumn(
                label = "Pass Rate",
                value = "${stats.passRate.toInt()}%"
            )
            StatColumn(
                label = "High / Low",
                value = "${stats.highestScore.toInt()} / ${stats.lowestScore.toInt()}"
            )
        }
    }
}

@Composable
private fun StatColumn(
    label: String,
    value: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun ScannedPaperCard(
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (result.studentCode.isNotBlank()) {
                            Text(
                                text = "ID: ${result.studentCode}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = item.examTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
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

@Composable
private fun FullscreenSheetInspectionDialog(
    imagePath: String?,
    studentName: String,
    score: Float,
    totalPoints: Float,
    percentage: Float,
    onDismiss: () -> Unit
) {
    if (imagePath.isNullOrEmpty()) {
        onDismiss()
        return
    }

    val file = remember(imagePath) { File(imagePath) }
    if (!file.exists()) {
        onDismiss()
        return
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Interactive Zoomable / Pannable Image
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.8f, 5f)
                                offset = if (scale > 1f) {
                                    Offset(offset.x + pan.x, offset.y + pan.y)
                                } else {
                                    Offset.Zero
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = file,
                        contentDescription = "Graded Sheet High-Resolution",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
                    )
                }

                // Top Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = studentName,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Score: ${score.toInt()} / ${totalPoints.toInt()} (${percentage.toInt()}%)",
                            color = if (percentage >= 60f) Color(0xFF00E676) else Color(0xFFFF5252),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (scale != 1f) {
                            TextButton(onClick = {
                                scale = 1f
                                offset = Offset.Zero
                            }) {
                                Text("Reset Zoom", color = Color.White)
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }
                }

                // Bottom Hint
                Text(
                    text = "Pinch to zoom • Drag to pan",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}
