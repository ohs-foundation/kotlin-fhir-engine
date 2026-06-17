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

package dev.ohs.fhirdemo.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface Screen {
  data object Home : Screen

  data object List : Screen

  data class Detail(val patientId: String) : Screen

  data object NewPatient : Screen

  data class EditPatient(val patientId: String) : Screen

  data object Sync : Screen

  data object PeriodicSync : Screen

  data object Crud : Screen
}

class Navigator {
  private val backStack = ArrayDeque<Screen>().apply { addLast(Screen.Home) }

  private val _current = MutableStateFlow<Screen>(Screen.Home)
  val current: StateFlow<Screen> = _current

  /** Navigates to [screen], pushing it onto the back stack. */
  fun go(screen: Screen) {
    backStack.addLast(screen)
    _current.value = screen
  }

  /** Pops the current screen, returning to the previous one. No-op at the root. */
  fun back() {
    if (backStack.size > 1) {
      backStack.removeLast()
      _current.value = backStack.last()
    }
  }

  /** Replaces the current screen in place (does not grow the back stack). */
  fun replace(screen: Screen) {
    if (backStack.isNotEmpty()) backStack.removeLast()
    backStack.addLast(screen)
    _current.value = screen
  }
}
