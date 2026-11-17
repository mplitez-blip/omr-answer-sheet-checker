package com.omr.checker.ui.results

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.omr.checker.ui.preview.FullscreenSheetViewerDialog
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanResultScreen(
    viewModel: ScanResultViewModel,
    onNavigateBack: () -> Unit,
    onScanNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showEnlargedImage by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.message) {
        uiState.message?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    val summary = uiState.scoreSummary
    val exam = uiState.exam
    val imagePath = uiState.scanResult?.scannedImagePath

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Grading Result", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.saveChanges {} }) {
                        Icon(Icons.Default.Save, contentDescription = "Save")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Done")
                    }
                    Button(
                        onClick = onScanNext,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Scan Next")
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Score Summary Banner
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "${summary?.percentage?.toInt() ?: 0}%",
                            fontSize = 44.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Score: ${summary?.earnedScore ?: 0f} / ${summary?.totalPoints ?: 0f}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatChip(label = "Correct", count = summary?.correctCount ?: 0, color = Color(0xFF2E7D32))
                            StatChip(label = "Incorrect", count = summary?.incorrectCount ?: 0, color = Color(0xFFC62828))
                            StatChip(label = "Blank", count = summary?.blankCount ?: 0, color = Color.Gray)
                        }
                    }
                }
            }

            // 2. Graded Answer Sheet (Processed/Annotated Image Card)
            if (!imagePath.isNullOrEmpty()) {
                item {
                    val imageFile = remember(imagePath) { File(imagePath) }
                    if (imageFile.exists()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "Graded Answer Sheet",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "✔ Correct  ❌ Wrong  Green Ring = Answer Key",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.DarkGray
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                AsyncImage(
                                    model = imageFile,
                                    contentDescription = "Graded Answer Sheet",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
                                        .clickable { showEnlargedImage = true }
                                )
                            }
                        }
                    }
                }
            }

            // 3. Student Info Inputs
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Student Information", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = uiState.studentName,
                            onValueChange = { viewModel.updateStudentName(it) },
                            label = { Text("Student Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = uiState.studentCode,
                            onValueChange = { viewModel.updateStudentCode(it) },
                            label = { Text("Student Code / ID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 4. Question Item Breakdown
            item {
                Text(
                    text = "Question Breakdown & Verification",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (exam != null) {
                val questions = exam.answerKey.keys.sorted()
                items(questions) { q ->
                    val correct = exam.answerKey[q]
                    val studentAnswer = uiState.rawAnswers[q]
                    val isCorrect = summary?.itemResults?.get(q)

                    QuestionReviewRow(
                        question = q,
                        correctAnswer = correct ?: 'A',
                        studentAnswer = studentAnswer,
                        isCorrect = isCorrect,
                        onSelectAnswer = { opt ->
                            viewModel.overrideAnswer(q, opt)
                        }
                    )
                }
            }
        }
    }

    // Fullscreen Sheet Audit Dialog
    if (showEnlargedImage && !imagePath.isNullOrEmpty()) {
        val totalPts = summary?.totalPoints ?: 0f
        val scorePts = summary?.earnedScore ?: 0f
        val pct = summary?.percentage ?: 0f
        val studentTitle = uiState.studentName.ifBlank { "Graded Answer Sheet" }
        val scoreSubtitle = "Score: ${scorePts.toInt()} / ${totalPts.toInt()} (${pct.toInt()}%)"

        FullscreenSheetViewerDialog(
            imagePath = imagePath,
            title = studentTitle,
            subtitle = scoreSubtitle,
            subtitleColor = if (pct >= 60f) Color(0xFF00E676) else Color(0xFFFF5252),
            onDismiss = { showEnlargedImage = false }
        )
    }
}

@Composable
fun StatChip(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = count.toString(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        Text(text = label, fontSize = 12.sp, color = Color.DarkGray)
    }
}

@Composable
fun QuestionReviewRow(
    question: Int,
    correctAnswer: Char,
    studentAnswer: Char?,
    isCorrect: Boolean?,
    onSelectAnswer: (Char?) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (isCorrect) {
                true -> Color(0xFFF1F8E9)
                false -> Color(0xFFFFEBEE)
                null -> Color(0xFFF5F5F5)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Item Number
                Text(
                    text = String.format("%2d.", question),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.width(28.dp)
                )

                // Mark badge: ✔ for correct, ❌ for wrong, — for blank
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = when (isCorrect) {
                        true -> Color(0xFF2E7D32)
                        false -> Color(0xFFC62828)
                        null -> Color.Gray
                    },
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Text(
                        text = when (isCorrect) {
                            true -> " ✔ "
                            false -> " ❌ "
                            null -> " — "
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // Detected Student Answer badge with colors
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = when (isCorrect) {
                        true -> Color(0xFFE8F5E9)
                        false -> Color(0xFFFFEBEE)
                        null -> Color(0xFFEEEEEE)
                    },
                    border = BorderStroke(
                        1.dp,
                        when (isCorrect) {
                            true -> Color(0xFF2E7D32)
                            false -> Color(0xFFC62828)
                            null -> Color.LightGray
                        }
                    ),
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Text(
                        text = "Ans: ${studentAnswer ?: "None"}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when (isCorrect) {
                            true -> Color(0xFF1B5E20)
                            false -> Color(0xFFB71C1C)
                            null -> Color.DarkGray
                        },
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                // Answer Key badge with colors
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFFE0F2F1),
                    border = BorderStroke(1.dp, Color(0xFF00897B)),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Text(
                        text = "Key: $correctAnswer",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF004D40),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }

            // Options Selector (A, B, C, D)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val options = listOf('A', 'B', 'C', 'D')
                for (opt in options) {
                    val isSelected = studentAnswer != null && studentAnswer.equals(opt, ignoreCase = true)
                    val isKey = correctAnswer.equals(opt, ignoreCase = true)

                    val bubbleBg = when {
                        isSelected && isKey -> Color(0xFF2E7D32)
                        isSelected && !isKey -> Color(0xFFC62828)
                        !isSelected && isKey -> Color(0x3500C853)
                        else -> Color.White
                    }

                    val borderColor = when {
                        isKey -> Color(0xFF00C853)
                        isSelected -> Color(0xFFC62828)
                        else -> Color.Gray
                    }

                    val textColor = when {
                        isSelected -> Color.White
                        isKey -> Color(0xFF00796B)
                        else -> Color.Black
                    }

                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .border(if (isKey) 2.dp else 1.dp, borderColor, CircleShape)
                            .background(bubbleBg, CircleShape)
                            .clickable {
                                onSelectAnswer(if (isSelected) null else opt)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = opt.toString(),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                    }
                }
            }
        }
    }
}
