package com.omr.checker.ui.designer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omr.checker.data.model.ElementType
import com.omr.checker.data.model.SheetElement
import com.omr.checker.generator.PdfExporterHelper
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetDesignerScreen(
    viewModel: SheetDesignerViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearUserMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = uiState.templateName,
                        onValueChange = { viewModel.updateTemplateName(it) },
                        singleLine = true,
                        placeholder = { Text("Answer Sheet Name") },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadDefaultTemplate() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset to Default 60")
                    }
                    IconButton(onClick = { viewModel.saveTemplate {} }) {
                        Icon(Icons.Default.Save, contentDescription = "Save")
                    }
                    if (uiState.isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = {
                            viewModel.exportPdf(context) { file ->
                                PdfExporterHelper.viewOrPrintPdf(context, file)
                            }
                        }) {
                            Icon(Icons.Default.Download, contentDescription = "Download / Print PDF")
                        }
                    }
                }
            )
        },
        bottomBar = {
            DesignerBottomBar(
                elements = uiState.elements,
                selectedElementId = uiState.selectedElementId,
                onAddElement = { viewModel.addElement(it) },
                onRemoveElement = { viewModel.removeElement(it) },
                onUpdateLabel = { id, label -> viewModel.updateElementLabel(id, label) },
                onUpdateGridConfig = { id, q, cols -> viewModel.updateGridConfig(id, q, cols) },
                onDeselect = { viewModel.selectElement(null) }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFFE8ECEF)),
            contentAlignment = Alignment.Center
        ) {
            // A4 Aspect Ratio Container (Width : Height = 1 : 1.414)
            BoxWithConstraints(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxSize()
                    .aspectRatio(1f / 1.414f, matchHeightConstraintsFirst = true)
                    .shadow(8.dp, RoundedCornerShape(2.dp))
                    .background(Color.White)
                    .clickable { viewModel.selectElement(null) }
            ) {
                val canvasWidth = maxWidth
                val canvasHeight = maxHeight
                val density = LocalDensity.current

                val canvasWidthPx = with(density) { canvasWidth.toPx() }
                val canvasHeightPx = with(density) { canvasHeight.toPx() }

                // 1. Fixed Corner Fiducial Markers (Locked Anchors for OMR detection)
                val anchorSize = canvasWidth * 0.04f
                val margin = canvasWidth * 0.035f

                // Top-Left Anchor
                Box(
                    modifier = Modifier
                        .offset(x = margin, y = margin)
                        .size(anchorSize)
                        .background(Color.Black)
                )
                // Top-Right Anchor
                Box(
                    modifier = Modifier
                        .offset(x = canvasWidth - margin - anchorSize, y = margin)
                        .size(anchorSize)
                        .background(Color.Black)
                )
                // Bottom-Left Anchor
                Box(
                    modifier = Modifier
                        .offset(x = margin, y = canvasHeight - margin - anchorSize)
                        .size(anchorSize)
                        .background(Color.Black)
                )
                // Bottom-Right Anchor
                Box(
                    modifier = Modifier
                        .offset(
                            x = canvasWidth - margin - anchorSize,
                            y = canvasHeight - margin - anchorSize
                        )
                        .size(anchorSize)
                        .background(Color.Black)
                )

                // 2. Render all draggable elements
                for (element in uiState.elements) {
                    val isSelected = element.id == uiState.selectedElementId
                    val elementWidth = canvasWidth * element.width
                    val elementHeight = canvasHeight * element.height
                    val elementOffsetX = canvasWidth * element.x
                    val elementOffsetY = canvasHeight * element.y

                    DraggableElementBox(
                        element = element,
                        isSelected = isSelected,
                        width = elementWidth,
                        height = elementHeight,
                        offsetX = elementOffsetX,
                        offsetY = elementOffsetY,
                        onDrag = { dxPx, dyPx ->
                            val deltaNormX = dxPx / canvasWidthPx
                            val deltaNormY = dyPx / canvasHeightPx
                            viewModel.updateElementPosition(element.id, deltaNormX, deltaNormY)
                        },
                        onSelect = { viewModel.selectElement(element.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun DraggableElementBox(
    element: SheetElement,
    isSelected: Boolean,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    offsetX: androidx.compose.ui.unit.Dp,
    offsetY: androidx.compose.ui.unit.Dp,
    onDrag: (Float, Float) -> Unit,
    onSelect: () -> Unit
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray
    val borderWidth = if (isSelected) 2.dp else 1.dp

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    offsetX.roundToPx(),
                    offsetY.roundToPx()
                )
            }
            .size(width, height)
            .border(borderWidth, borderColor, RoundedCornerShape(3.dp))
            .background(if (isSelected) Color(0x101E88E5) else Color(0x05000000))
            .pointerInput(element.id) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
            .clickable { onSelect() }
            .padding(2.dp)
    ) {
        when (element.type) {
            ElementType.BUBBLE_GRID -> {
                BubbleGridPreview(element)
            }
            else -> {
                HeaderFieldPreview(element)
            }
        }
    }
}

@Composable
fun HeaderFieldPreview(element: SheetElement) {
    Column(modifier = Modifier.fillMaxSize().padding(2.dp)) {
        Text(
            text = element.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(0.5.dp, Color.Gray, RoundedCornerShape(2.dp))
                .background(Color.White)
        )
    }
}

@Composable
fun BubbleGridPreview(element: SheetElement) {
    val config = element.gridConfig ?: com.omr.checker.data.model.BubbleGridConfig()
    val columns = config.columns.coerceAtLeast(1)

    Column(modifier = Modifier.fillMaxSize().padding(2.dp)) {
        Text(
            text = "${element.label} [${config.options.joinToString("")}]",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
        Row(
            modifier = Modifier.fillMaxSize().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val questionsPerCol = (config.endQuestion - config.startQuestion + 1) / columns
            for (col in 0 until columns) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .border(0.5.dp, Color.LightGray, RoundedCornerShape(2.dp))
                        .padding(2.dp),
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    val startQ = config.startQuestion + col * questionsPerCol
                    val endQ = (startQ + questionsPerCol - 1).coerceAtMost(config.endQuestion)
                    Text(
                        text = "Q$startQ-$endQ",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.DarkGray
                    )
                    // Sample bubble row preview
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("1.", fontSize = 7.sp, fontWeight = FontWeight.Bold)
                        for (opt in config.options) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .border(0.6.dp, Color.Black, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(opt.toString(), fontSize = 5.sp)
                            }
                        }
                    }
                    Text("...", fontSize = 7.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
fun DesignerBottomBar(
    elements: List<SheetElement>,
    selectedElementId: String?,
    onAddElement: (ElementType) -> Unit,
    onRemoveElement: (String) -> Unit,
    onUpdateLabel: (String, String) -> Unit,
    onUpdateGridConfig: (String, Int, Int) -> Unit,
    onDeselect: () -> Unit
) {
    val selectedElement = elements.firstOrNull { it.id == selectedElementId }

    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (selectedElement != null) {
                // Inspector for Selected Element
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Edit: ${selectedElement.type.name}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = { onRemoveElement(selectedElement.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                    }
                }

                OutlinedTextField(
                    value = selectedElement.label,
                    onValueChange = { onUpdateLabel(selectedElement.id, it) },
                    label = { Text("Element Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (selectedElement.type == ElementType.BUBBLE_GRID) {
                    val config = selectedElement.gridConfig ?: com.omr.checker.data.model.BubbleGridConfig()
                    var questionsText by remember(selectedElement.id) {
                        mutableStateOf((config.endQuestion - config.startQuestion + 1).toString())
                    }
                    var columnsText by remember(selectedElement.id) {
                        mutableStateOf(config.columns.toString())
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = questionsText,
                            onValueChange = {
                                questionsText = it
                                it.toIntOrNull()?.let { q ->
                                    onUpdateGridConfig(selectedElement.id, q, config.columns)
                                }
                            },
                            label = { Text("Questions (e.g. 60)") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = columnsText,
                            onValueChange = {
                                columnsText = it
                                it.toIntOrNull()?.let { cols ->
                                    onUpdateGridConfig(
                                        selectedElement.id,
                                        config.endQuestion - config.startQuestion + 1,
                                        cols
                                    )
                                }
                            },
                            label = { Text("Columns (1-3)") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                TextButton(
                    onClick = onDeselect,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Done")
                }
            } else {
                // Element Palette (Add draggable elements to canvas)
                Text(
                    text = "Add Elements to Sheet",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PaletteChip("+ Test Name") { onAddElement(ElementType.TEST_NAME) }
                    PaletteChip("+ Student Name") { onAddElement(ElementType.STUDENT_NAME) }
                    PaletteChip("+ Section") { onAddElement(ElementType.SECTION) }
                    PaletteChip("+ Date") { onAddElement(ElementType.DATE) }
                    PaletteChip("+ Sheet Code") { onAddElement(ElementType.ANSWER_SHEET_CODE) }
                    PaletteChip("+ Bubble Grid") { onAddElement(ElementType.BUBBLE_GRID) }
                }
            }
        }
    }
}

@Composable
fun PaletteChip(label: String, onClick: () -> Unit) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) }
    )
}
