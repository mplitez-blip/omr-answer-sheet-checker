package com.omr.checker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.omr.checker.data.database.AppDatabase
import com.omr.checker.data.repository.OMRRepository
import com.omr.checker.ui.ViewModelFactory
import com.omr.checker.ui.designer.SheetDesignerScreen
import com.omr.checker.ui.designer.SheetDesignerViewModel
import com.omr.checker.ui.exams.AnswerKeyEditScreen
import com.omr.checker.ui.exams.AnswerKeyViewModel
import com.omr.checker.ui.exams.ExamListScreen
import com.omr.checker.ui.exams.ExamListViewModel
import com.omr.checker.ui.results.ScanResultScreen
import com.omr.checker.ui.results.ScanResultViewModel
import com.omr.checker.ui.scanner.ScannerScreen
import com.omr.checker.ui.scanner.ScannerViewModel
import com.omr.checker.ui.templates.TemplateListScreen
import com.omr.checker.ui.templates.TemplateListViewModel
import com.omr.checker.ui.theme.OMRAnswerSheetCheckerTheme
import com.omr.checker.ui.update.UpdateDialog
import com.omr.checker.ui.update.UpdateViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = AppDatabase.getInstance(this)
        val repository = OMRRepository(database)
        val viewModelFactory = ViewModelFactory(repository)

        setContent {
            OMRAnswerSheetCheckerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val updateViewModel: UpdateViewModel = viewModel()
                    UpdateDialog(viewModel = updateViewModel)

                    OMRAppNavigation(
                        viewModelFactory = viewModelFactory,
                        onCheckForUpdates = { updateViewModel.checkForUpdates() }
                    )
                }
            }
        }
    }
}

@Composable
fun OMRAppNavigation(
    viewModelFactory: ViewModelFactory,
    onCheckForUpdates: () -> Unit
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "exams"
    ) {
        // 1. Exams Screen (Home)
        composable("exams") {
            val examListViewModel: ExamListViewModel = viewModel(factory = viewModelFactory)
            ExamListScreen(
                viewModel = examListViewModel,
                onNavigateToScan = { examId ->
                    navController.navigate("scanner/$examId")
                },
                onNavigateToEditKey = { examId ->
                    navController.navigate("answer_key/$examId")
                },
                onNavigateToTemplates = {
                    navController.navigate("templates")
                },
                onCheckForUpdates = onCheckForUpdates
            )
        }

        // 2. Templates Screen
        composable("templates") {
            val templateListViewModel: TemplateListViewModel = viewModel(factory = viewModelFactory)
            TemplateListScreen(
                viewModel = templateListViewModel,
                onCreateNewTemplate = {
                    navController.navigate("designer/0")
                },
                onEditTemplate = { templateId ->
                    navController.navigate("designer/$templateId")
                },
                onNavigateToExams = {
                    navController.navigate("exams") {
                        popUpTo("exams") { inclusive = true }
                    }
                },
                onCheckForUpdates = onCheckForUpdates
            )
        }

        // 3. Custom Sheet Designer Screen
        composable(
            route = "designer/{templateId}",
            arguments = listOf(
                navArgument("templateId") { type = NavType.LongType; defaultValue = 0L }
            )
        ) { backStackEntry ->
            val templateId = backStackEntry.arguments?.getLong("templateId") ?: 0L
            val designerViewModel: SheetDesignerViewModel = viewModel(factory = viewModelFactory)

            if (templateId > 0L) {
                designerViewModel.loadTemplate(templateId)
            } else {
                designerViewModel.loadDefaultTemplate()
            }

            SheetDesignerScreen(
                viewModel = designerViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 4. Answer Key Edit Screen
        composable(
            route = "answer_key/{examId}",
            arguments = listOf(
                navArgument("examId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val examId = backStackEntry.arguments?.getLong("examId") ?: 0L
            val answerKeyViewModel: AnswerKeyViewModel = viewModel(factory = viewModelFactory)

            answerKeyViewModel.loadExam(examId)

            AnswerKeyEditScreen(
                viewModel = answerKeyViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 5. CameraX Live Scanner Screen
        composable(
            route = "scanner/{examId}",
            arguments = listOf(
                navArgument("examId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val examId = backStackEntry.arguments?.getLong("examId") ?: 0L
            val scannerViewModel: ScannerViewModel = viewModel(factory = viewModelFactory)

            scannerViewModel.loadExam(examId)

            ScannerScreen(
                viewModel = scannerViewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToResult = { scanResultId ->
                    navController.navigate("result/$scanResultId") {
                        popUpTo("scanner/$examId") { inclusive = true }
                    }
                }
            )
        }

        // 6. Scan Result & Grading Breakdown Screen
        composable(
            route = "result/{scanResultId}",
            arguments = listOf(
                navArgument("scanResultId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val scanResultId = backStackEntry.arguments?.getLong("scanResultId") ?: 0L
            val resultViewModel: ScanResultViewModel = viewModel(factory = viewModelFactory)

            resultViewModel.loadResult(scanResultId)

            ScanResultScreen(
                viewModel = resultViewModel,
                onNavigateBack = { navController.popBackStack() },
                onScanNext = {
                    val examId = resultViewModel.uiState.value.exam?.id ?: 0L
                    navController.navigate("scanner/$examId") {
                        popUpTo("result/$scanResultId") { inclusive = true }
                    }
                }
            )
        }
    }
}
