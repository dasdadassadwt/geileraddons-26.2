package geiler.addons.client.chat

import java.util.ArrayDeque
import java.util.ArrayList
import java.util.BitSet
import java.util.LinkedHashMap
import java.util.Optional

/**
 * Bounded regular-expression subset for user chat routing rules.
 *
 * The matcher uses a Thompson NFA instead of Java's backtracking engine. A match is bounded by
 * the input and automaton limits, so nested groups and repeated alternatives cannot stall the
 * client thread. Backreferences and lookarounds are intentionally unsupported.
 */
object ChatChannelRegex {
    const val MAX_PATTERN_CHARACTERS = 96
    const val MAX_INPUT_CHARACTERS = 2048
    private const val MAX_STATES = 1024
    private const val MAX_REPEAT_BOUND = 64
    private const val MAX_CACHED_PATTERNS = 64

    private val cache = LinkedHashMap<String, Automaton>(16, 0.75f, true)

    /** Returns an explanatory validation error, or an empty Optional for a valid pattern. */
    @JvmStatic
    fun validationError(expression: String?): Optional<String> {
        if (expression == null || ChatStringSemantics.isBlank(expression)) return Optional.of("Enter a regex pattern.")
        if (expression.length > MAX_PATTERN_CHARACTERS) {
            return Optional.of("Regex patterns are limited to 96 characters.")
        }
        return try {
            compiled(expression)
            Optional.empty()
        } catch (invalid: RegexException) {
            Optional.of(invalid.message ?: "Regex pattern is invalid.")
        } catch (invalid: RuntimeException) {
            Optional.of("Regex pattern is invalid.")
        }
    }

    /** Uses find semantics: an unanchored rule can match any part of the incoming line. */
    @JvmStatic
    fun matches(expression: String?, input: String?): Boolean {
        if (input == null || input.length > MAX_INPUT_CHARACTERS) return false
        return try {
            compiled(expression).matches(input)
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun compiled(expression: String?): Automaton {
        if (expression == null || ChatStringSemantics.isBlank(expression) ||
            expression.length > MAX_PATTERN_CHARACTERS
        ) {
            throw RegexException("Regex pattern is empty or too long.")
        }
        val pattern = expression
        synchronized(cache) {
            cache[pattern]?.let { return it }
        }

        val parsed = Parser(pattern).parse()
        val built = Compiler().compile(parsed)
        synchronized(cache) {
            if (!cache.containsKey(pattern) && cache.size >= MAX_CACHED_PATTERNS) {
                cache.remove(cache.keys.first())
            }
            cache[pattern] = built
        }
        return built
    }

    private sealed interface Node
    private data object EmptyNode : Node
    private data class CharacterNode(val matcher: CharacterMatcher) : Node
    private data class AssertionNode(val beginning: Boolean) : Node
    private data class SequenceNode(val nodes: List<Node>) : Node
    private data class AlternativeNode(val nodes: List<Node>) : Node
    private data class RepeatNode(val node: Node, val minimum: Int, val maximum: Int) : Node

    private fun interface CharacterMatcher {
        fun matches(value: Char): Boolean
    }

    private class Parser(private val source: String) {
        private var index = 0

        fun parse(): Node {
            val result = alternative()
            if (index != source.length) throw invalid("Unexpected closing group.")
            return result
        }

        private fun alternative(): Node {
            val choices = ArrayList<Node>()
            choices.add(sequence())
            while (peek('|')) {
                index++
                choices.add(sequence())
            }
            return if (choices.size == 1) choices.first() else AlternativeNode(choices.toList())
        }

        private fun sequence(): Node {
            val nodes = ArrayList<Node>()
            while (index < source.length && source[index] != ')' && source[index] != '|') {
                val atom = atom()
                if (index < source.length) {
                    when (source[index]) {
                        '*', '+', '?', '{' -> {
                            nodes.add(repeat(atom))
                            continue
                        }
                    }
                }
                nodes.add(atom)
            }
            return when (nodes.size) {
                0 -> EmptyNode
                1 -> nodes.first()
                else -> SequenceNode(nodes.toList())
            }
        }

        private fun repeat(node: Node): Node {
            val quantifier = source[index++]
            val minimum: Int
            val maximum: Int
            when (quantifier) {
                '*' -> {
                    minimum = 0
                    maximum = -1
                }
                '+' -> {
                    minimum = 1
                    maximum = -1
                }
                '?' -> {
                    minimum = 0
                    maximum = 1
                }
                else -> {
                    val close = source.indexOf('}', index)
                    if (close < 0) throw invalid("A bounded repeat must end with '}'.")
                    val bounds = source.substring(index, close)
                    val comma = bounds.indexOf(',')
                    try {
                        if (comma < 0) {
                            minimum = parseBound(bounds)
                            maximum = minimum
                        } else {
                            val low = bounds.substring(0, comma)
                            val high = bounds.substring(comma + 1)
                            minimum = parseBound(low)
                            maximum = if (high.isEmpty()) -1 else parseBound(high)
                        }
                    } catch (_: NumberFormatException) {
                        throw invalid("Repeat bounds must be integers.")
                    }
                    if (minimum < 0 || minimum > MAX_REPEAT_BOUND || maximum > MAX_REPEAT_BOUND ||
                        (maximum >= 0 && minimum > maximum)
                    ) {
                        throw invalid("Repeat bounds must be ordered and no greater than 64.")
                    }
                    index = close + 1
                }
            }
            if (index < source.length) {
                val next = source[index]
                if (next == '*' || next == '+' || next == '?' || next == '{') {
                    throw invalid("A token can have only one quantifier.")
                }
            }
            return RepeatNode(node, minimum, maximum)
        }

        private fun atom(): Node {
            val value = source[index++]
            return when (value) {
                '(' -> group()
                '[' -> CharacterNode(characterClass())
                '.' -> CharacterNode { character -> character != '\n' && character != '\r' }
                '^' -> AssertionNode(true)
                '$' -> AssertionNode(false)
                '\\' -> CharacterNode(escape().matcher)
                ')', '|', '*', '+', '?', ']' -> throw invalid("Unexpected regex token: $value")
                '{', '}' -> throw invalid("A repeat bound must follow a token.")
                else -> CharacterNode { character -> character == value }
            }
        }

        private fun group(): Node {
            if (peek('?')) throw invalid("Lookarounds and special groups are not supported.")
            val nested = alternative()
            if (!peek(')')) throw invalid("A group is missing its closing ')'.")
            index++
            return nested
        }

        private fun characterClass(): CharacterMatcher {
            val negate = peek('^')
            if (negate) index++
            val members = ArrayList<CharacterMatcher>()
            var first = true
            var closed = false
            while (index < source.length) {
                if (source[index] == ']' && !first) {
                    index++
                    closed = true
                    break
                }
                val left = classAtom()
                first = false
                if (peek('-') && index + 1 < source.length && source[index + 1] != ']' && left.literal != null) {
                    index++
                    val right = classAtom()
                    if (right.literal == null || left.literal > right.literal) {
                        throw invalid("Character-class ranges must use ordered literal endpoints.")
                    }
                    val low = left.literal
                    val high = right.literal
                    members.add { character -> character >= low && character <= high }
                } else {
                    members.add(left.matcher)
                }
            }
            if (!closed) throw invalid("A character class is missing its closing ']'.")
            if (members.isEmpty()) throw invalid("Character classes cannot be empty.")
            return CharacterMatcher { character ->
                val found = members.any { member -> member.matches(character) }
                negate != found
            }
        }

        private fun classAtom(): ClassAtom {
            if (index >= source.length) throw invalid("A character class is missing its closing ']'.")
            val value = source[index++]
            if (value == '\\') return escape()
            return ClassAtom(CharacterMatcher { character -> character == value }, value)
        }

        private fun escape(): ClassAtom {
            if (index >= source.length) throw invalid("A trailing escape is not valid.")
            val escaped = source[index++]
            val matcher = when (escaped) {
                'd' -> CharacterMatcher { character -> character in '0'..'9' }
                'D' -> CharacterMatcher { character -> character !in '0'..'9' }
                'w' -> CharacterMatcher { character -> isWord(character) }
                'W' -> CharacterMatcher { character -> !isWord(character) }
                's' -> CharacterMatcher { character -> Character.isWhitespace(character) }
                'S' -> CharacterMatcher { character -> !Character.isWhitespace(character) }
                'n' -> CharacterMatcher { character -> character == '\n' }
                'r' -> CharacterMatcher { character -> character == '\r' }
                't' -> CharacterMatcher { character -> character == '\t' }
                else -> {
                    if (Character.isDigit(escaped)) throw invalid("Backreferences are not supported.")
                    if (Character.isLetter(escaped)) throw invalid("Unsupported regex escape: \\$escaped")
                    CharacterMatcher { character -> character == escaped }
                }
            }
            val literal = if ("\\.^$|?*+{}[]-".indexOf(escaped) >= 0) escaped else null
            return ClassAtom(matcher, literal)
        }

        private fun peek(value: Char): Boolean = index < source.length && source[index] == value

        private fun parseBound(value: String): Int {
            if (value.isEmpty() || value.length > 2 || value.any { it !in '0'..'9' }) {
                throw invalid("Repeat bounds must be decimal integers from 0 to 64.")
            }
            return value.toInt()
        }

        private fun invalid(message: String) = RegexException(message)

        private fun isWord(character: Char): Boolean =
            character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character == '_'
    }

    private data class ClassAtom(val matcher: CharacterMatcher, val literal: Char?)
    private data class Edge(val kind: Int, val target: Int, val matcher: CharacterMatcher?)

    private class State {
        val edges = ArrayList<Edge>()
        var accepting = false
    }

    private data class Fragment(val start: Int, val end: Int)

    private class Compiler {
        private val states = ArrayList<State>()

        fun compile(root: Node): Automaton {
            val fragment = emit(root)
            states[fragment.end].accepting = true
            return Automaton(states.toList(), fragment.start)
        }

        private fun emit(node: Node): Fragment {
            when (node) {
                EmptyNode -> {
                    val start = state()
                    val end = state()
                    edge(start, EPSILON, end, null)
                    return Fragment(start, end)
                }
                is CharacterNode -> {
                    val start = state()
                    val end = state()
                    edge(start, CHARACTER, end, node.matcher)
                    return Fragment(start, end)
                }
                is AssertionNode -> {
                    val start = state()
                    val end = state()
                    edge(start, if (node.beginning) BEGIN else END, end, null)
                    return Fragment(start, end)
                }
                is SequenceNode -> {
                    val parts = node.nodes
                    var result = emit(parts.first())
                    for (index in 1 until parts.size) {
                        val next = emit(parts[index])
                        edge(result.end, EPSILON, next.start, null)
                        result = Fragment(result.start, next.end)
                    }
                    return result
                }
                is AlternativeNode -> {
                    val start = state()
                    val end = state()
                    for (choice in node.nodes) {
                        val branch = emit(choice)
                        edge(start, EPSILON, branch.start, null)
                        edge(branch.end, EPSILON, end, null)
                    }
                    return Fragment(start, end)
                }
                is RepeatNode -> return emitRepeat(node)
            }
        }

        private fun emitRepeat(repeat: RepeatNode): Fragment {
            val start = state()
            var cursor = start
            for (count in 0 until repeat.minimum) {
                val required = emit(repeat.node)
                edge(cursor, EPSILON, required.start, null)
                cursor = required.end
            }
            if (repeat.maximum < 0) {
                val end = state()
                val loop = emit(repeat.node)
                edge(cursor, EPSILON, end, null)
                edge(cursor, EPSILON, loop.start, null)
                edge(loop.end, EPSILON, cursor, null)
                return Fragment(start, end)
            }
            for (count in repeat.minimum until repeat.maximum) {
                val next = state()
                val optional = emit(repeat.node)
                edge(cursor, EPSILON, next, null)
                edge(cursor, EPSILON, optional.start, null)
                edge(optional.end, EPSILON, next, null)
                cursor = next
            }
            val end = state()
            edge(cursor, EPSILON, end, null)
            return Fragment(start, end)
        }

        private fun state(): Int {
            if (states.size >= MAX_STATES) {
                throw RegexException("Regex expands beyond the 1024-state safety limit.")
            }
            states.add(State())
            return states.size - 1
        }

        private fun edge(from: Int, kind: Int, target: Int, matcher: CharacterMatcher?) {
            states[from].edges.add(Edge(kind, target, matcher))
        }
    }

    private class Automaton(private val states: List<State>, private val start: Int) {
        fun matches(input: String): Boolean {
            var active = BitSet(states.size)
            for (offset in 0..input.length) {
                active.set(start)
                close(active, offset, input.length)
                if (containsAccepting(active)) return true
                if (offset == input.length) break
                val value = input[offset]
                val next = BitSet(states.size)
                var index = active.nextSetBit(0)
                while (index >= 0) {
                    for (edge in states[index].edges) {
                        if (edge.kind == CHARACTER && edge.matcher!!.matches(value)) next.set(edge.target)
                    }
                    index = active.nextSetBit(index + 1)
                }
                active = next
            }
            return false
        }

        private fun close(active: BitSet, offset: Int, length: Int) {
            val pending = ArrayDeque<Int>()
            var activeIndex = active.nextSetBit(0)
            while (activeIndex >= 0) {
                pending.addLast(activeIndex)
                activeIndex = active.nextSetBit(activeIndex + 1)
            }
            while (!pending.isEmpty()) {
                val index = pending.removeFirst()
                for (edge in states[index].edges) {
                    val follows = edge.kind == EPSILON || edge.kind == BEGIN && offset == 0 ||
                        edge.kind == END && offset == length
                    if (follows && !active.get(edge.target)) {
                        active.set(edge.target)
                        pending.addLast(edge.target)
                    }
                }
            }
        }

        private fun containsAccepting(active: BitSet): Boolean {
            var index = active.nextSetBit(0)
            while (index >= 0) {
                if (states[index].accepting) return true
                index = active.nextSetBit(index + 1)
            }
            return false
        }
    }

    private class RegexException(message: String) : RuntimeException(message)

    private const val EPSILON = 0
    private const val BEGIN = 1
    private const val END = 2
    private const val CHARACTER = 3
}
