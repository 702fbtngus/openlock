package dev.abdullah.openlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardTreeWalkTest {
    private class Node(val name: String, vararg val children: Node?)

    @Test fun anEarlyHeaderMatchDoesNotFetchAnyUnneededSibling() {
        val header = Node("header")
        val section = Node("section", header, Node("slow-permission-row"))
        val root = Node("root", section, Node("slow-other-section"))
        val visited = mutableListOf<String>()
        val reads = mutableListOf<String>()
        val released = mutableListOf<String>()
        walkGuardTree(root, 600,
            visit = { node, _ -> visited.add(node.name); node === header },
            childCount = { it.children.size },
            childAt = { node, index, _ ->
                reads.add("${node.name}[$index]")
                check(index == 0) { "Must not wait for an unused sibling" }
                node.children[index]
            },
            release = { released.add(it.name) },
        )
        assertEquals(listOf("root", "section", "header"), visited)
        assertEquals(listOf("root[0]", "section[0]"), reads)
        assertEquals(listOf("header", "section", "root"), released)
    }

    @Test fun aNegativeScreenStillVisitsEveryNodeInForwardDepthFirstOrder() {
        val root = Node("root", Node("a", Node("a0"), Node("a1")), Node("b"))
        val visited = mutableListOf<String>()
        val released = mutableListOf<String>()
        walkGuardTree(root, 600,
            visit = { node, _ -> visited.add(node.name); false },
            childCount = { it.children.size },
            childAt = { node, i, _ -> node.children[i] },
            release = { released.add(it.name) },
        )
        assertEquals(listOf("root", "a", "a0", "a1", "b"), visited)
        assertEquals(listOf("a0", "a1", "a", "b", "root"), released)
    }

    @Test fun budgetExhaustionDoesNotStartAnotherChildQuery() {
        val root = Node("root", Node("a", Node("unused-child")), Node("unused-sibling"))
        val visited = mutableListOf<String>()
        val reads = mutableListOf<String>()
        val released = mutableListOf<String>()
        walkGuardTree(root, 2,
            visit = { node, _ -> visited.add(node.name); false },
            childCount = { it.children.size },
            childAt = { node, i, _ -> reads.add("${node.name}[$i]"); node.children[i] },
            release = { released.add(it.name) },
        )
        assertEquals(listOf("root", "a"), visited)
        assertEquals(listOf("root[0]"), reads)
        assertEquals(listOf("a", "root"), released)
    }

    @Test fun missingChildrenDoNotSkipLaterSiblings() {
        val root = Node("root", null, Node("a"))
        val visited = mutableListOf<String>()
        walkGuardTree(root, 600,
            visit = { node, _ -> visited.add(node.name); false },
            childCount = { it.children.size },
            childAt = { node, i, _ -> node.children[i] },
            release = {},
        )
        assertEquals(listOf("root", "a"), visited)
    }

    @Test fun aFailedRemoteChildQueryReleasesTheAcquiredAncestorChain() {
        val root = Node("root", Node("a", Node("unavailable")))
        val released = mutableListOf<String>()
        var failed = false
        try {
            walkGuardTree(root, 600,
                visit = { _, _ -> false },
                childCount = { it.children.size },
                childAt = { node, i, _ ->
                    if (node.name == "a") error("Remote view went away")
                    node.children[i]
                },
                release = { released.add(it.name) },
            )
        } catch (_: IllegalStateException) { failed = true }
        assertTrue(failed)
        assertEquals(listOf("a", "root"), released)
    }

    @Test fun aFailedMatcherReleasesEachAcquiredNodeOnce() {
        val root = Node("root", Node("header"))
        val released = mutableListOf<String>()
        var failed = false
        try {
            walkGuardTree(root, 600,
                visit = { node, _ -> if (node.name == "header") error("Invalid view") else false },
                childCount = { it.children.size },
                childAt = { node, i, _ -> node.children[i] },
                release = { released.add(it.name) },
            )
        } catch (_: IllegalStateException) { failed = true }
        assertTrue(failed)
        assertEquals(listOf("header", "root"), released)
    }
}
