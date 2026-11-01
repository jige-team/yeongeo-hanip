package app.redlight.hanmadi.presentation

import app.redlight.hanmadi.HanmadiApp
import app.redlight.hanmadi.data.FileSttProbe
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.content.Intent
import android.os.Bundle
import app.redlight.hanmadi.BuildConfig
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

class MainActivity : ComponentActivity() {

    private val viewModel: DriveViewModel by viewModels {
        viewModelFactory {
            initializer {
                val c = (application as HanmadiApp).container
                DriveViewModel(c.vehicleRepository, c.speechRepository, c.audioFocusRepository, c.soundRepository, c.phraseRepository, c.progressRepository, c.stopPractice, c.fakeVehicle)
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // 거부돼도 시작한다: 속도를 모르면 브레이크만으로 판단하고, 마이크가 없으면 판정이 실패로 나온다
            viewModel.onIntent(DriveIntent.PermissionsHandled)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) {
                    viewModel.sideEffect.collect { effect ->
                        when (effect) {
                            is DriveSideEffect.RequestPermissions -> permissionLauncher.launch(effect.permissions.toTypedArray())
                        }
                    }
                }
                LaunchedEffect(Unit) { viewModel.onIntent(DriveIntent.Start) }
                DriveScreen(state, viewModel::onIntent)
            }
        }
        handleMeasure(intent)
    }

    // singleTask라서 이미 떠 있을 때 다시 열면 여기로 온다
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleMeasure(intent)
    }

    /**
     * 디버그 전용 진단 (tools/measure_stt.sh, tools/probe_stt.sh)
     * - --ez measure true: 마이크로 표현 20개를 연달아 측정
     * - --ez probe true: 녹음 파일을 마이크 없이 엔진에 바로 넣어 측정
     */
    private fun handleMeasure(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        if (intent.getBooleanExtra(EXTRA_MEASURE, false)) viewModel.onIntent(DriveIntent.StartMeasure)
        if (intent.getBooleanExtra(EXTRA_PROBE, false)) lifecycleScope.launch { FileSttProbe(applicationContext).run() }
    }

    private companion object {
        const val EXTRA_MEASURE = "measure"
        const val EXTRA_PROBE = "probe"
    }
}
