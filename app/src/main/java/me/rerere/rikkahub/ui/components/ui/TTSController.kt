package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Forward02
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus

@Composable
fun TTSController() {
    val context = LocalContext.current
    val ttsState = LocalTTSState.current

    val isSpeaking by ttsState.isSpeaking.collectAsState()
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(isSpeaking) {
        if (isSpeaking) {
            // 如果开启，显示悬浮窗
            isVisible = true
        }
    }

    FloatingWindow(
        tag = "tts_controller",
        visibility = isVisible
    ) {
        val playbackState by ttsState.playbackState.collectAsState()
        var expand by remember { mutableStateOf(false) }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.padding(8.dp),
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayPauseButton(playbackState = playbackState, ttsState = ttsState)

                IconButton(
                    onClick = {
                        ttsState.stop()
                        isVisible = false
                    }
                ) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = null,
                    )
                }

                AnimatedVisibility(expand) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SpeedButton(playbackState, ttsState)

                        FastForwardButton(ttsState = ttsState)

                        // 仅在使用 SystemTTS 时显示"重建引擎"按钮
                        if (ttsState.isSystemTtsActive()) {
                            ResetEngineButton(ttsState = ttsState)
                        }
                    }
                }

                IconButton(
                    onClick = {
                        expand = !expand
                    }
                ) {
                    Icon(
                        imageVector = if (expand) HugeIcons.ArrowLeft01 else HugeIcons.ArrowRight01,
                        contentDescription = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun FastForwardButton(ttsState: CustomTtsState) {
    IconButton(
        onClick = {
            ttsState.fastForward(5000)
        }
    ) {
        Icon(
            imageVector = HugeIcons.Forward02,
            contentDescription = null,
        )
    }
}

@Composable
private fun PlayPauseButton(
    playbackState: PlaybackState,
    ttsState: CustomTtsState
) {
    FilledTonalIconButton(
        onClick = {
            when (playbackState.status) {
                PlaybackStatus.Playing -> {
                    ttsState.pause()
                }

                else -> {
                    ttsState.resume()
                }
            }
        },
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    ) {
        Icon(
            imageVector = if (playbackState.status == PlaybackStatus.Playing) HugeIcons.Pause else HugeIcons.Play,
            contentDescription = null,
        )
        if (playbackState.status == PlaybackStatus.Playing || playbackState.status == PlaybackStatus.Buffering || playbackState.status == PlaybackStatus.Paused) {
            CircularProgressIndicator(
                progress = {
                    if (playbackState.status == PlaybackStatus.Playing) {
                        playbackState.positionMs.toFloat() / playbackState.durationMs
                    } else {
                        0f
                    }
                },
                color = MaterialTheme.colorScheme.tertiary,
                strokeWidth = 2.dp,
                trackColor = Color.Transparent
            )
            CircularProgressIndicator(
                progress = {
                    if (playbackState.status == PlaybackStatus.Playing) {
                        playbackState.currentChunkIndex.toFloat() / playbackState.totalChunks
                    } else {
                        0f
                    }
                },
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(2.dp),
                strokeWidth = 2.dp,
                trackColor = Color.Transparent
            )
        }
    }
}

// 播放速度档位：0.8x ~ 2.0x，每隔 0.1 一档
private val SPEED_STEPS = (8..20).map { it / 10f }

@Composable
private fun SpeedButton(
    playbackState: PlaybackState,
    ttsState: CustomTtsState
) {
    TextButton(
        onClick = {
            // 找到当前速度在档位列表中最近的索引，然后切换到下一档
            val currentIndex = SPEED_STEPS.indexOfFirst {
                kotlin.math.abs(it - playbackState.speed) < 0.01f
            }
            val nextIndex = if (currentIndex < 0) {
                // 当前速度不在列表中，回到默认 1.0
                SPEED_STEPS.indexOf(1.0f)
            } else {
                (currentIndex + 1) % SPEED_STEPS.size
            }
            ttsState.setSpeed(SPEED_STEPS[nextIndex])
        }
    ) {
        Text(text = "x${"%.2f".format(playbackState.speed).trimEnd('0').trimEnd('.')}")
    }
}

/**
 * 重建 SystemTTS 引擎按钮。
 * 用于消除累积的 vocoder 漂移 / 偶发杂音 — 点一下立即 markNeedsRebuild,
 * 当前 chunk 播完后下一次 speak 时自动 recycleEngine。
 *
 * 仅在 SystemTTS provider 激活时显示(由调用方通过 isSystemTtsActive() 控制)。
 */
@Composable
private fun ResetEngineButton(ttsState: CustomTtsState) {
    val context = LocalContext.current
    IconButton(
        onClick = {
            ttsState.resetEngine()
            Toast.makeText(
                context,
                "已请求重建 TTS 引擎,下一段生效",
                Toast.LENGTH_SHORT
            ).show()
        }
    ) {
        Icon(
            imageVector = HugeIcons.Refresh01,
            contentDescription = "重建 TTS 引擎",
        )
    }
}
