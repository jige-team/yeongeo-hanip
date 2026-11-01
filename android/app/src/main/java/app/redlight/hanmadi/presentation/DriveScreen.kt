package app.redlight.hanmadi.presentation

import app.redlight.hanmadi.R
import app.redlight.hanmadi.domain.model.DrivePhase
import app.redlight.hanmadi.domain.model.DriveSignal
import app.redlight.hanmadi.domain.model.Gear
import app.redlight.hanmadi.domain.model.TurnSignal
import app.redlight.hanmadi.domain.model.Judgement
import app.redlight.hanmadi.domain.model.Phrase
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val FULL_RMS = 2500.0

private object Palette {
    val background = Color(0xFF0F1419)
    val surface = Color(0xFF1B232C)
    val text = Color(0xFFECEFF3)
    val subtle = Color(0xFF8A96A3)
    val red = Color(0xFFE5484D)
    val green = Color(0xFF3DD68C)
    val amber = Color(0xFFF5A524)
    /** 아직 안 한 표현의 점. 배경과 카드 위 모두에서 보이도록 둘보다 밝게 */
    val pending = Color(0xFF3E4A57)
}

@Composable
fun DriveScreen(state: DriveState, onIntent: (DriveIntent) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Header(state)
        Spacer(Modifier.height(24.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // 측정 모드는 주차 중이지만 정차 연습 화면을 그대로 쓴다
            val screen = when {
                state.measureLabel != null -> Screen.SPEAK
                // 서 있을 때만 바꾼다. 달리는 중에는 "주행 중" 화면을 그대로 둔다
                state.phase.isStopped && state.allDone -> Screen.DONE
                state.phase.isStopped && state.paused -> Screen.PAUSED
                else -> state.phase.toScreen()
            }
            AnimatedContent(targetState = screen, label = "phase") { screen ->
                when (screen) {
                    Screen.LISTEN -> ListenScreen(state)
                    Screen.SPEAK -> SpeakScreen(state, onIntent)
                    Screen.HIDDEN -> Box(Modifier.fillMaxSize())
                    Screen.REST -> TurnWaitScreen()
                    Screen.REVIEW -> ReviewScreen(state, onIntent)
                    Screen.PAUSED -> PausedScreen(onIntent)
                    Screen.DONE -> AllDoneScreen(state)
                }
            }
        }
        state.debugSignal?.let { DebugPanel(it, onIntent) }
    }
}

private enum class Screen { LISTEN, SPEAK, HIDDEN, REST, REVIEW, PAUSED, DONE }

/** 상태별 화면 규칙: docs/design/drive-phase-rules.md */
private fun DrivePhase.toScreen(): Screen = when (this) {
    DrivePhase.MOVING -> Screen.LISTEN
    DrivePhase.ABOUT_TO_MOVE -> Screen.HIDDEN
    DrivePhase.TURN_WAIT -> Screen.REST
    DrivePhase.STOPPED_SHORT, DrivePhase.STOPPED_LONG -> Screen.SPEAK
    DrivePhase.PARKED, DrivePhase.SESSION_END -> Screen.REVIEW
}

@Composable
private fun Header(state: DriveState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Palette.red))
        Spacer(Modifier.width(16.dp))
        Text(stringResource(R.string.app_name), color = Palette.text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(
            "연속 ${state.streak.days}일 · 오늘 ${state.successCount}/${state.results.size}",
            color = Palette.subtle,
            fontSize = 26.sp,
        )
    }
}

/** 주행 중: 글자를 보여 주지 않는다. 마이크는 꺼져 있고 소리로만 미리 들려준다 */
@Composable
private fun ListenScreen(state: DriveState) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("주행 중", color = Palette.text, fontSize = 64.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                state.allDone -> "오늘 표현을 모두 마쳤어요"
                state.paused -> "연습을 멈췄어요. 정차하면 다시 시작할 수 있어요"
                state.isJam -> "정체 구간이에요. 조금 더 오래 서면 말해요"
                else -> "다음 빨간불에서 따라 말해요"
            },
            color = Palette.subtle,
            fontSize = 30.sp,
        )
        Spacer(Modifier.height(48.dp))
        // 글자 대신 점으로 오늘 진행만 보여 준다. 한눈에 보고 다시 운전에 집중할 수 있게
        ProgressDots(state, dotSize = 22.dp)
    }
}

/** 우회전 대기: 말하기를 쉰다는 것만 크게 알린다 */
@Composable
private fun TurnWaitScreen() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("우회전 대기", color = Palette.amber, fontSize = 64.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("보행자를 살필 땐 쉬어요. 깜빡이를 끄면 이어서 해요", color = Palette.subtle, fontSize = 30.sp)
    }
}

/** 오늘 표현마다 점 하나: 초록 = 성공, 주황 = 실패, 회색 = 아직. 지금 할 표현은 테두리 */
@Composable
private fun ProgressDots(state: DriveState, dotSize: Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(dotSize / 2)) {
        state.phrases.forEachIndexed { index, phrase ->
            val color = when (state.statusOf(phrase)) {
                true -> Palette.green
                false -> Palette.amber
                null -> Palette.pending
            }
            Box(
                Modifier.size(dotSize).clip(CircleShape).background(color)
                    .then(if (index == state.currentIndex) Modifier.border(3.dp, Palette.text, CircleShape) else Modifier),
            )
        }
    }
}

/** 신호 대기: 표현 하나만 크게, 아래에 뜻을 작게 */
@Composable
private fun SpeakScreen(state: DriveState, onIntent: (DriveIntent) -> Unit) {
    val phrase = state.stop.phrase ?: state.current ?: return
    val number = state.phrases.indexOf(phrase) + 1
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            state.measureLabel ?: buildString {
                append("표현 $number/${state.phrases.size}")
                if (state.stop.maxAttempts > 1) append(" · ${state.stop.attempt}번째 시도")
            },
            color = Palette.subtle,
            fontSize = 26.sp,
        )
        Spacer(Modifier.height(24.dp))
        Text(phrase.keyChunk, color = Palette.text, fontSize = 120.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        // 뜻은 한눈에 읽히는 모국어로, 표현보다 작고 흐리게 둔다
        Text(phrase.keyChunkKorean, color = Palette.subtle, fontSize = 40.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(40.dp))
        Box(Modifier.height(140.dp), contentAlignment = Alignment.Center) {
            when (state.stop.step) {
                StopUi.Step.IDLE, StopUi.Step.PROMPTING -> Status("잘 들어 보세요", Palette.subtle)
                StopUi.Step.LISTENING -> MicLevel(state.stop.rms)
                StopUi.Step.DONE -> JudgementBadge(state.stop, combo = state.combo)
            }
        }
        if (state.measureLabel == null) {
            Spacer(Modifier.height(32.dp))
            // 서 있을 때만 보이는 버튼이라 운전 중 조작은 없다. 장갑 낀 손으로도 누르게 크게 둔다
            BigButton("연습 잠깐 멈춤", Palette.surface) { onIntent(DriveIntent.TogglePause) }
        }
    }
}

/** 정차 화면 아래의 큰 버튼 */
@Composable
private fun BigButton(label: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        modifier = Modifier.height(96.dp).width(420.dp),
    ) {
        Text(label, color = Palette.text, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 연습을 멈춘 동안: 마이크와 소리는 꺼져 있다. 다시 시작하면 지금 표현부터 이어 간다 */
@Composable
private fun PausedScreen(onIntent: (DriveIntent) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("연습을 멈췄어요", color = Palette.text, fontSize = 64.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("마이크와 소리를 껐어요", color = Palette.subtle, fontSize = 30.sp)
        Spacer(Modifier.height(48.dp))
        BigButton("다시 시작", Palette.red) { onIntent(DriveIntent.TogglePause) }
    }
}

/** 오늘 표현을 모두 끝냈을 때: 처음으로 돌아가지 않고 오늘 결과를 보여 준다 */
@Composable
private fun AllDoneScreen(state: DriveState) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("오늘 ${state.phrases.size}개를 모두 했어요", color = Palette.green, fontSize = 64.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("주차하면 오늘 표현을 다시 보고 말해 볼 수 있어요", color = Palette.subtle, fontSize = 28.sp)
        Spacer(Modifier.height(40.dp))
        ResultCard(state)
    }
}

@Composable
private fun Status(text: String, color: Color) {
    Text(text, color = color, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun MicLevel(rms: Double) {
    // 엔진이 주는 입력 크기는 말할 때 최대 2000~2500쯤이다(에뮬레이터 실측)
    val level by animateFloatAsState((rms / FULL_RMS).coerceIn(0.05, 1.0).toFloat(), label = "rms")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Status("지금 말하세요", Palette.red)
        Spacer(Modifier.height(20.dp))
        Box(Modifier.width(600.dp).height(16.dp).clip(RoundedCornerShape(8.dp)).background(Palette.surface)) {
            Box(Modifier.fillMaxWidth(level).height(16.dp).clip(RoundedCornerShape(8.dp)).background(Palette.red))
        }
    }
}

/** 판정 결과. 맞히면 초록 원이 톡 튀어나오고, 틀리면 못 들은 말과 다음 할 일을 알려 준다 */
@Composable
private fun JudgementBadge(stop: StopUi, combo: Int) {
    val judgement = stop.lastJudgement ?: return
    // 결과가 바뀔 때마다 0에서 1로 커지는 효과
    val scale = remember(judgement) { Animatable(0.4f) }
    LaunchedEffect(judgement) { scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }

    if (judgement.success) {
        Row(Modifier.scale(scale.value), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(88.dp).clip(CircleShape).background(Palette.green), contentAlignment = Alignment.Center) {
                Text("✓", color = Palette.background, fontSize = 56.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(24.dp))
            Text(
                if (combo >= 3) "${combo}연속 성공!" else "잘했어요!",
                color = Palette.green,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    } else {
        Column(Modifier.scale(scale.value), horizontalAlignment = Alignment.CenterHorizontally) {
            // 무엇으로 들렸는지 알면 "meaning → meeting", 아니면 못 들은 단어
            val pair = judgement.misheard.firstOrNull()
            Status(
                if (pair != null) "${pair.second} → ${pair.first}" else "못 들은 말: ${judgement.missingWords.take(2).joinToString(" ")}",
                Palette.amber,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (stop.willRetry) "한 번 더 해 봐요" else "다음 표현으로 넘어가요",
                color = Palette.subtle,
                fontSize = 28.sp,
            )
        }
    }
}

/** 주차: 오늘 결과를 한 장으로 보여 주고, 오늘 표현을 보며 다시 말해 본다 */
@Composable
private fun ReviewScreen(state: DriveState, onIntent: (DriveIntent) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ResultCard(state) }
        item {
            Text(
                "오늘의 표현이에요. 주차 중에는 눌러서 다시 말해 볼 수 있어요.",
                color = Palette.subtle,
                fontSize = 24.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
        }
        items(state.phrases, key = { it.id }) { phrase ->
            val judgement = state.reviewJudgements[phrase.id]
                ?: state.results.lastOrNull { it.phrase.id == phrase.id }?.judgement
            ReviewRow(phrase, judgement, reading = state.reviewingId == phrase.id) {
                onIntent(DriveIntent.SayAgain(phrase.id))
            }
        }
    }
}

/** 오늘의 결과 카드: 성공 수, 연속 일수, 어려웠던 표현 */
@Composable
private fun ResultCard(state: DriveState) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Palette.surface).padding(32.dp),
    ) {
        if (state.results.isEmpty()) {
            Text("오늘의 ${state.phraseSet.label} ${state.phrases.size}개", color = Palette.text, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("출발하면 빨간불마다 하나씩 말해요", color = Palette.subtle, fontSize = 26.sp)
            return@Column
        }
        Row(verticalAlignment = Alignment.Top) {
            Stat("오늘 성공", "${state.successCount}/${state.results.size}", if (state.successCount > 0) Palette.green else Palette.text)
            Spacer(Modifier.width(56.dp))
            Stat("연속", "${state.streak.days}일", Palette.text)
            Spacer(Modifier.width(56.dp))
            Column(Modifier.weight(1f)) {
                Text("어려웠던 표현", color = Palette.subtle, fontSize = 24.sp)
                Spacer(Modifier.height(8.dp))
                val hard = state.hardPhrases.take(3)
                if (hard.isEmpty()) {
                    Text("모두 성공했어요!", color = Palette.green, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    hard.forEach { phrase ->
                        Text("${phrase.keyChunk}  ·  ${phrase.keyChunkKorean}", color = Palette.amber, fontSize = 28.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        ProgressDots(state, dotSize = 18.dp)
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color) {
    Column {
        Text(label, color = Palette.subtle, fontSize = 24.sp)
        Text(value, color = color, fontSize = 72.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ReviewRow(phrase: Phrase, judgement: Judgement?, reading: Boolean, onRead: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.surface).padding(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // 빨간불에서 말하는 표현 그대로 보여 준다. 예문을 보여 주면 문장을 말해야 하는 것처럼 보였다(2026-11-01 사용자 결정)
            Text(phrase.keyChunk, color = Palette.text, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
            Text(phrase.keyChunkKorean, color = Palette.subtle, fontSize = 24.sp)
        }
        val (mark, color) = when (judgement?.success) {
            true -> "성공" to Palette.green
            false -> "다시" to Palette.amber
            null -> "" to Palette.subtle
        }
        Text(mark, color = color, fontSize = 26.sp, modifier = Modifier.padding(horizontal = 24.dp))
        Button(
            onClick = onRead,
            enabled = !reading,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.red),
            modifier = Modifier.height(72.dp).width(180.dp),
        ) {
            Text(if (reading) "듣는 중" else "말하기", fontSize = 26.sp)
        }
    }
}

/** 가짜 차량에서만 보이는 조작 패널 (Pleos 에뮬레이터가 아닐 때 개발용) */
@Composable
private fun DebugPanel(signal: DriveSignal, onIntent: (DriveIntent) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(12.dp)).background(Palette.surface).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("DEBUG ${signal.gear} 브레이크=${signal.brakePressed} 속도=${signal.speedKmh}", color = Palette.subtle, fontSize = 18.sp)
        listOf(Gear.PARK, Gear.DRIVE, Gear.NEUTRAL).forEach { gear ->
            OutlinedButton(onClick = { onIntent(DriveIntent.Debug { copy(gear = gear) }) }) { Text(gear.name.take(1)) }
        }
        OutlinedButton(onClick = { onIntent(DriveIntent.Debug { copy(brakePressed = !brakePressed) }) }) { Text("브레이크") }
        OutlinedButton(onClick = { onIntent(DriveIntent.Debug { copy(speedKmh = 0f) }) }) { Text("속도 0") }
        OutlinedButton(onClick = { onIntent(DriveIntent.Debug { copy(speedKmh = 40f) }) }) { Text("속도 40") }
        OutlinedButton(onClick = { onIntent(DriveIntent.Debug { copy(driverDoorOpen = !driverDoorOpen) }) }) { Text("문") }
        OutlinedButton(onClick = { onIntent(DriveIntent.Debug { copy(turnSignal = TurnSignal.entries[(turnSignal.ordinal + 1) % TurnSignal.entries.size]) }) }) { Text("깜빡이 ${signal.turnSignal}") }
    }
}
