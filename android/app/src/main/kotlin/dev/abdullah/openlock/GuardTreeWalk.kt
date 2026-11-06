package dev.abdullah.openlock

private class GuardTreeFrame<N>(val node: N, var childCount: Int = -1, var nextChild: Int = 0)

/** Fetch one child, visit it, then fetch its next sibling. An early identity
 *  match must not wait for all the siblings' remote accessibility queries.
 *  [release] receives root too, so the caller can retain its own root cleanup. */
internal fun <N> walkGuardTree(
    root: N,
    limit: Int,
    visit: (N, Int) -> Boolean,
    childCount: (N) -> Int,
    childAt: (N, Int, Int) -> N?,
    release: (N) -> Unit,
) {
    require(limit > 0)
    val stack = ArrayDeque<GuardTreeFrame<N>>()
    stack.addLast(GuardTreeFrame(root))
    var visited = 0
    try {
        while (stack.isNotEmpty()) {
            val frame = stack.last()
            if (frame.childCount < 0) {
                visited++
                if (visit(frame.node, visited) || visited >= limit) return
                frame.childCount = childCount(frame.node).coerceAtLeast(0)
            }
            if (frame.nextChild >= frame.childCount) {
                stack.removeLast()
                release(frame.node)
            } else {
                val child = childAt(frame.node, frame.nextChild++, visited)
                if (child != null) stack.addLast(GuardTreeFrame(child))
            }
        }
    } finally {
        // Includes the ancestor chain on early match, budget exhaustion/error.
        while (stack.isNotEmpty()) release(stack.removeLast().node)
    }
}
