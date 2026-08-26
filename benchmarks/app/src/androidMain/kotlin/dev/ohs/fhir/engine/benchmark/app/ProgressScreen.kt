/*
 * Copyright 2026 Open Health Stack Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ohs.fhir.engine.benchmark.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ohs.fhir.engine.benchmark.CurrentWorkload
import dev.ohs.fhir.engine.benchmark.FinishedWorkload
import dev.ohs.fhir.engine.benchmark.RunState

/**
 * The group-run screen. A Synthea run takes hours, and without this a working run and a hung one
 * look identical.
 *
 * Nothing here may animate. Recomposition during a measured span perturbs the number being taken,
 * so the progress bar is determinate and the list scrolls instantly. The one exception is
 * [elapsedSeconds], which the caller ticks once a second — a frozen clock during a 100-second
 * iteration reads as the very hang this screen exists to rule out.
 */
@Composable
fun ProgressScreen(state: RunState, elapsedSeconds: Long) {
  MaterialTheme {
    Surface(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Header(state, elapsedSeconds)
        state.current?.let {
          Spacer(Modifier.height(8.dp))
          CurrentRow(it)
        }
        state.failure?.let {
          Spacer(Modifier.height(8.dp))
          Text("failed: $it", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        FinishedList(state)
      }
    }
  }
}

@Composable
private fun Header(state: RunState, elapsedSeconds: Long) {
  val label =
    listOfNotNull(
        state.phase.name.lowercase().replace('_', ' '),
        state.datasetLabel,
        if (state.totalWorkloads > 0) {
          "${state.completedWorkloads}/${state.totalWorkloads}"
        } else {
          null
        },
      )
      .joinToString(" · ")

  Column {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(label, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
      Text(formatElapsed(elapsedSeconds), fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
    Spacer(Modifier.height(6.dp))
    // Determinate: the indeterminate overload animates forever, including during a measured span.
    LinearProgressIndicator(
      progress = {
        if (state.totalWorkloads == 0) {
          0f
        } else {
          state.completedWorkloads.toFloat() / state.totalWorkloads
        }
      },
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun CurrentRow(current: CurrentWorkload) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(
      "▸ ${current.id}",
      fontSize = 13.sp,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.weight(1f),
    )
    Text(current.iterationLabel ?: "preparing", fontSize = 13.sp, fontFamily = FontFamily.Monospace)
  }
}

@Composable
private fun FinishedList(state: RunState) {
  val listState = rememberLazyListState()
  LaunchedEffect(state.finished.size) {
    // scrollToItem, not animateScrollToItem: an animation keeps drawing frames after the next
    // span has already opened.
    if (state.finished.isNotEmpty()) listState.scrollToItem(state.finished.size - 1)
  }
  LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
    items(state.finished, key = { it.id }) { FinishedRow(it) }
  }
}

@Composable
private fun FinishedRow(workload: FinishedWorkload) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      "${if (workload.succeeded) "✓" else "✗"} ${workload.id}",
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace,
      color =
        if (workload.succeeded) {
          MaterialTheme.colorScheme.onSurface
        } else {
          MaterialTheme.colorScheme.error
        },
      modifier = Modifier.weight(1f),
    )
    Text(
      if (workload.succeeded) formatMillis(workload.medianMillis) else "ERROR",
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace,
    )
  }
}

/** `mm:ss`. Runs long enough that hours are possible, so minutes are not capped at 60. */
private fun formatElapsed(totalSeconds: Long): String {
  val minutes = totalSeconds / 60
  val seconds = totalSeconds % 60
  return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}

/** Common Kotlin has no `String.format`, and the spread here runs from 0.5 ms to minutes. */
private fun formatMillis(value: Double): String =
  if (value >= 1000.0) "${oneDecimal(value / 1000.0)} s" else "${oneDecimal(value)} ms"

private fun oneDecimal(value: Double): String {
  val scaled = (value * 10).toLong()
  return "${scaled / 10}.${scaled % 10}"
}
