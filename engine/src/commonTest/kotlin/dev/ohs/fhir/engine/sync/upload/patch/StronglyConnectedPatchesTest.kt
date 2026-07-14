/*
 * Copyright 2024-2026 Open Health Stack Foundation
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
package dev.ohs.fhir.engine.sync.upload.patch

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainOnly
import kotlin.collections.mutableListOf
import kotlin.test.Test

class StronglyConnectedPatchesTest {

  @Test
  fun ssc_ordered_should_return_strongly_connected_components_in_order() {
    val graph = mutableMapOf<String, MutableList<String>>()

    graph.addEdge("0", "1")
    graph.addEdge("1", "2")
    graph.addEdge("2", "1")

    graph.addEdge("3", "4")
    graph.addEdge("4", "5")
    graph.addEdge("5", "3")

    graph.addEdge("6", "7")
    graph.addEdge("7", "8")

    val result = StronglyConnectedPatches.scc(graph)

    result.shouldContainExactly(
      listOf("1", "2"),
      listOf("0"),
      listOf("3", "4", "5"),
      listOf("8"),
      listOf("7"),
      listOf("6"),
    )
  }

  @Test
  fun ssc_ordered_empty_graph_should_return_empty_result() {
    val graph = mutableMapOf<String, MutableList<String>>()
    val result = StronglyConnectedPatches.scc(graph)
    result.shouldBeEmpty()
  }

  @Test
  fun ssc_ordered_graph_with_single_node_should_return_single_scc() {
    val graph = mutableMapOf<String, MutableList<String>>()
    graph.addNode("0")
    val result = StronglyConnectedPatches.scc(graph)
    result.shouldContainExactly(listOf(listOf("0")))
  }

  @Test
  fun ssc_ordered_graph_with_two_node_should_return_two_scc() {
    val graph = mutableMapOf<String, MutableList<String>>()
    graph.addNode("0")
    graph.addNode("1")
    val result = StronglyConnectedPatches.scc(graph)
    result.shouldContainOnly(listOf("0"), listOf("1"))
  }

  @Test
  fun ssc_ordered_graph_with_two_acyclic_node_should_return_two_scc_in_order() {
    val graph = mutableMapOf<String, MutableList<String>>()
    graph.addEdge("1", "0")
    val result = StronglyConnectedPatches.scc(graph)
    result.shouldContainExactly(listOf("0"), listOf("1"))
  }

  @Test
  fun ssc_ordered_graph_with_two_cyclic_node_should_return_single_scc() {
    val graph = mutableMapOf<String, MutableList<String>>()
    graph.addEdge("0", "1")
    graph.addEdge("1", "0")
    val result = StronglyConnectedPatches.scc(graph)
    result.shouldContainExactly(listOf(listOf("0", "1")))
  }
}

private fun Graph.addEdge(node: Node, dependsOn: Node) {
  (this as MutableMap).getOrPut(node) { mutableListOf() }.let { (it as MutableList).add(dependsOn) }
}

private fun Graph.addNode(node: Node) {
  (this as MutableMap)[node] = mutableListOf()
}
