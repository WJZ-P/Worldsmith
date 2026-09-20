package com.wjz.worldsmith.core.ability

import java.util.Collections

/** Immutable prepared bytecode. No reflection, class loading, engine objects, or I/O instructions. */
class CompiledAbilityProgram internal constructor(
    val definition: AbilityProgramDefinition,
    val usedCapabilities: Map<String, Int>,
    internal val functions: Map<String, AbilityCode>,
    internal val handlers: Map<String, AbilityCode>,
    internal val registry: AbilityCapabilityRegistry,
) {
    fun definition(): AbilityProgramDefinition = definition
    val events: Set<String> = Collections.unmodifiableSet(LinkedHashSet(handlers.keys))
}

internal data class AbilityLocation(val line: Int, val column: Int) {
    fun fail(message: String): Nothing = throw IllegalArgumentException("line $line, column $column: ${message.take(512)}")
}
internal enum class AbilityOp { CONSTANT, LOAD_LOCAL, STORE_LOCAL, LOAD_INPUT, LOAD_STATE, STORE_STATE, LIST, POP, DUP, UNARY, BINARY, JUMP, JUMP_FALSE, JUMP_TRUE, CALL, WAIT, RETURN, COUNT, CHECK }
internal data class AbilityInstruction(val op: AbilityOp, val argument: Any? = null, val at: AbilityLocation)
internal data class AbilityCall(val name: String, val count: Int, val userFunction: Boolean)
internal data class AbilityCode(val name: String, val parameters: List<String>, val localCount: Int, val instructions: List<AbilityInstruction>)

object AbilityCompiler {
    @JvmStatic @JvmOverloads
    fun compile(definition: AbilityProgramDefinition, registry: AbilityCapabilityRegistry = AbilityCapabilities.standard()): CompiledAbilityProgram {
        try { AbilityPrograms.checkDefinition(definition) }
        catch (e: IllegalArgumentException) { AbilityLocation(1, 1).fail(e.message ?: "Invalid program definition.") }
        definition.requires.forEach { (name, version) ->
            val spec = registry.lookup(name) ?: AbilityLocation(1, 1).fail("Unknown required capability '$name'.")
            if (version != spec.version) AbilityLocation(1, 1).fail("Capability '$name' version $version is unavailable; installed version is ${spec.version}.")
        }
        val program = AbilityParser(definition.source).parse()
        val generator = AbilityCodeGenerator(program, definition, registry)
        return generator.compile()
    }
}

private data class Token(val text: String, val at: AbilityLocation, val kind: Int = 0)
private class AbilityLexer(private val source: String) {
    private var offset = 0
    private var line = 1
    private var column = 1
    private fun char(ahead: Int = 0): Char = source.getOrNull(offset + ahead) ?: '\u0000'
    private fun take(): Char = source[offset++].also { if (it == '\n') { line++; column = 1 } else column++ }
    fun lex(): List<Token> {
        val tokens = mutableListOf<Token>()
        while (offset < source.length) {
            if (char().isWhitespace()) { take(); continue }
            val at = AbilityLocation(line, column)
            if (char() == '/' && char(1) == '/') { while (offset < source.length && char() != '\n') take(); continue }
            if (char() == '/' && char(1) == '*') {
                take(); take()
                while (offset < source.length && !(char() == '*' && char(1) == '/')) take()
                if (offset == source.length) at.fail("Unterminated block comment.")
                take(); take(); continue
            }
            val token = when {
                char().isLetter() || char() == '_' -> {
                    val start = offset
                    while (char().isLetterOrDigit() || char() == '_') take()
                    val text = source.substring(start, offset)
                    if (text.length > 128) at.fail("Identifier exceeds 128 characters.")
                    Token(text, at, 1)
                }
                char().isDigit() -> {
                    val start = offset
                    while (char().isDigit()) take()
                    if (char() == '.' && char(1).isDigit()) { take(); while (char().isDigit()) take() }
                    if (char() == 'e' || char() == 'E') {
                        take(); if (char() == '+' || char() == '-') take()
                        if (!char().isDigit()) at.fail("Expected exponent digits.")
                        while (char().isDigit()) take()
                    }
                    Token(source.substring(start, offset), at, 2)
                }
                char() == '"' -> {
                    take(); val value = StringBuilder()
                    while (offset < source.length && char() != '"') {
                        val c = take()
                        if (c == '\n' || c == '\r') at.fail("Text literals must stay on one source line.")
                        if (c != '\\') value.append(c) else {
                            if (offset == source.length) at.fail("Unterminated text escape.")
                            when (val escaped = take()) {
                                '"', '\\' -> value.append(escaped)
                                'n' -> value.append('\n')
                                'r' -> value.append('\r')
                                't' -> value.append('\t')
                                'u' -> {
                                    if (offset + 4 > source.length) at.fail("Incomplete Unicode escape.")
                                    val hex = source.substring(offset, offset + 4).toIntOrNull(16) ?: at.fail("Invalid Unicode escape.")
                                    repeat(4) { take() }; value.append(hex.toChar())
                                }
                                else -> at.fail("Unknown text escape.")
                            }
                        }
                        if (value.length > 4096) at.fail("Text literal exceeds 4096 characters.")
                    }
                    if (offset == source.length) at.fail("Unterminated text literal.")
                    take(); Token(value.toString(), at, 3)
                }
                else -> {
                    val pair = "${char()}${char(1)}"
                    if (pair in setOf("==", "!=", "<=", ">=", "&&", "||")) { take(); take(); Token(pair, at) }
                    else if (char() in "{}()[],;.=+-*/%!<>") Token(take().toString(), at)
                    else at.fail("Unexpected character '${char()}'.")
                }
            }
            tokens += token
            if (tokens.size > 16384) at.fail("Program exceeds 16384 tokens.")
        }
        tokens += Token("<eof>", AbilityLocation(line, column), 4)
        return tokens
    }
}

private sealed class Expr(open val at: AbilityLocation) {
    open val depth: Int = 1
    data class Literal(val value: AbilityValue, override val at: AbilityLocation) : Expr(at)
    data class Variable(val name: String, override val at: AbilityLocation) : Expr(at)
    data class State(val name: String, override val at: AbilityLocation) : Expr(at)
    data class Call(val name: String, val arguments: List<Expr>, override val at: AbilityLocation) : Expr(at) { override val depth = 1 + (arguments.maxOfOrNull { it.depth } ?: 0) }
    data class ListExpr(val values: List<Expr>, override val at: AbilityLocation) : Expr(at) { override val depth = 1 + (values.maxOfOrNull { it.depth } ?: 0) }
    data class Unary(val operator: String, val value: Expr, override val at: AbilityLocation) : Expr(at) { override val depth = value.depth + 1 }
    data class Binary(val operator: String, val left: Expr, val right: Expr, override val at: AbilityLocation) : Expr(at) { override val depth = maxOf(left.depth, right.depth) + 1 }
}
private sealed class Statement(open val at: AbilityLocation) {
    data class Let(val name: String, val value: Expr, override val at: AbilityLocation) : Statement(at)
    data class Assign(val target: Expr, val value: Expr, override val at: AbilityLocation) : Statement(at)
    data class Expression(val value: Expr, override val at: AbilityLocation) : Statement(at)
    data class If(val condition: Expr, val yes: List<Statement>, val no: List<Statement>, override val at: AbilityLocation) : Statement(at)
    data class While(val condition: Expr, val body: List<Statement>, override val at: AbilityLocation) : Statement(at)
    data class Repeat(val count: Expr, val body: List<Statement>, override val at: AbilityLocation) : Statement(at)
    data class For(val name: String, val list: Expr, val body: List<Statement>, override val at: AbilityLocation) : Statement(at)
    data class Wait(val ticks: Expr, override val at: AbilityLocation) : Statement(at)
    data class Return(val value: Expr?, override val at: AbilityLocation) : Statement(at)
}
private data class Declaration(val name: String, val parameters: List<String>, val body: List<Statement>, val at: AbilityLocation)
private data class SourceProgram(val functions: Map<String, Declaration>, val handlers: Map<String, Declaration>)

private class AbilityParser(source: String) {
    private val tokens = AbilityLexer(source).lex()
    private var index = 0
    private var depth = 0
    private val current get() = tokens[index]
    private fun take() = tokens[index++]
    // Match punctuation/keywords, never the contents of a string token.
    private fun matches(text: String) = current.kind != 2 && current.kind != 3 && current.text == text
    private fun accept(text: String): Boolean = matches(text).also { if (it) take() }
    private fun expect(text: String): Token = if (matches(text)) take() else current.at.fail("Expected '$text', found '${current.text.take(32)}'.")
    private fun name(): Token {
        if (current.kind != 1 || current.text in reserved) current.at.fail("Expected an identifier.")
        return take()
    }
    private inline fun <T> nested(block: () -> T): T {
        if (++depth > 96) current.at.fail("Source nesting exceeds 96.")
        try { return block() } finally { depth-- }
    }
    fun parse(): SourceProgram {
        val functions = linkedMapOf<String, Declaration>()
        val handlers = linkedMapOf<String, Declaration>()
        while (current.kind != 4) {
            val at = current.at
            if (accept("on")) {
                val name = name().text
                if (handlers.size >= 16) at.fail("At most 16 event handlers are allowed.")
                if (name in handlers) at.fail("Duplicate event handler '$name'.")
                handlers[name] = Declaration(name, emptyList(), block(), at)
            } else if (accept("fn")) {
                val name = name().text
                if (functions.size >= 32) at.fail("At most 32 functions are allowed.")
                if (name in functions) at.fail("Duplicate function '$name'.")
                expect("("); val parameters = mutableListOf<String>()
                if (!matches(")")) do {
                    val parameter = name().text
                    if (parameter in parameters) at.fail("Duplicate parameter '$parameter'.")
                    if (parameter in inputs || parameter == "state") at.fail("Parameter '$parameter' shadows an input.")
                    parameters += parameter
                    if (parameters.size > 16) at.fail("At most 16 function parameters are allowed.")
                } while (accept(","))
                expect(")")
                functions[name] = Declaration(name, parameters, block(), at)
            } else at.fail("Expected 'on event { ... }' or 'fn name(...) { ... }'.")
        }
        if ("start" !in handlers) current.at.fail("An 'on start' handler is required.")
        return SourceProgram(functions, handlers)
    }
    private fun block(): List<Statement> = nested {
        expect("{"); val result = mutableListOf<Statement>()
        while (!accept("}")) {
            if (current.kind == 4) current.at.fail("Unterminated block.")
            result += statement()
        }
        result
    }
    private fun statement(): Statement = nested {
        val at = current.at
        when {
            accept("let") -> { val name = name().text; expect("="); val value = expression(); expect(";"); Statement.Let(name, value, at) }
            accept("if") -> parseIf(at)
            accept("while") -> { expect("("); val condition = expression(); expect(")"); Statement.While(condition, block(), at) }
            accept("repeat") -> { val count = expression(); Statement.Repeat(count, block(), at) }
            accept("for") -> { val name = name().text; expect("in"); val list = expression(); Statement.For(name, list, block(), at) }
            accept("wait") -> { val ticks = expression(); expect(";"); Statement.Wait(ticks, at) }
            accept("return") -> { val value = if (matches(";")) null else expression(); expect(";"); Statement.Return(value, at) }
            else -> {
                val left = expression()
                if (accept("=")) {
                    if (left !is Expr.Variable && left !is Expr.State) at.fail("Assignment needs a local variable or state.name.")
                    val value = expression(); expect(";"); Statement.Assign(left, value, at)
                } else { expect(";"); Statement.Expression(left, at) }
            }
        }
    }
    private fun parseIf(at: AbilityLocation): Statement.If = nested {
        expect("("); val condition = expression(); expect(")"); val yes = block()
        val no = if (accept("else")) {
            if (accept("if")) listOf(parseIf(tokens[index-1].at)) else block()
        } else emptyList()
        Statement.If(condition, yes, no, at)
    }
    private fun bounded(expression: Expr): Expr {
        if (expression.depth > 96) expression.at.fail("Expression nesting exceeds 96.")
        return expression
    }
    private fun expression(minPrecedence: Int = 0): Expr = nested {
        var left = unary()
        while (current.kind == 0 && (precedence[current.text] ?: -1) >= minPrecedence) {
            val operator = take(); val right = expression(precedence.getValue(operator.text) + 1)
            left = bounded(Expr.Binary(operator.text, left, right, operator.at))
        }
        left
    }
    private fun unary(): Expr = nested {
        if (matches("!") || matches("-") || matches("+")) {
            val operator = take(); bounded(Expr.Unary(operator.text, unary(), operator.at))
        } else primary()
    }
    private fun primary(): Expr {
        val token = take()
        if (token.kind == 2) {
            val value = token.text.toDoubleOrNull() ?: token.at.fail("Invalid number.")
            try { return Expr.Literal(AbilityValues.number(value), token.at) }
            catch (e: IllegalArgumentException) { token.at.fail(e.message ?: "Invalid number.") }
        }
        if (token.kind == 3) return Expr.Literal(AbilityValues.text(token.text), token.at)
        when (token.text) {
            "true" -> return Expr.Literal(AbilityValues.bool(true), token.at)
            "false" -> return Expr.Literal(AbilityValues.bool(false), token.at)
            "null" -> return Expr.Literal(AbilityValues.none(), token.at)
            "(" -> { val expression = expression(); expect(")"); return expression }
            "[" -> {
                val values = mutableListOf<Expr>()
                if (!matches("]")) do {
                    values += expression()
                    if (values.size > 64) token.at.fail("List literal exceeds 64 entries.")
                } while (accept(","))
                expect("]"); return bounded(Expr.ListExpr(values, token.at))
            }
        }
        if (token.kind != 1 || token.text in reserved) token.at.fail("Expected an expression.")
        var name = token.text
        while (accept(".")) name += "." + name().text
        if (accept("(")) {
            val arguments = mutableListOf<Expr>()
            if (!matches(")")) do {
                arguments += expression()
                if (arguments.size > 16) token.at.fail("A call accepts at most 16 arguments.")
            } while (accept(","))
            expect(")"); return bounded(Expr.Call(name, arguments, token.at))
        }
        if (name.startsWith("state.") && name.count { it == '.' } == 1) return Expr.State(name.substringAfter('.'), token.at)
        if ('.' in name || name == "state") token.at.fail("Use state.name for state; other members are functions.")
        return Expr.Variable(name, token.at)
    }
    companion object {
        private val precedence = mapOf("||" to 0, "&&" to 1, "==" to 2, "!=" to 2, "<" to 3, "<=" to 3, ">" to 3, ">=" to 3, "+" to 4, "-" to 4, "*" to 5, "/" to 5, "%" to 5)
        private val reserved = setOf("on", "fn", "let", "if", "else", "while", "repeat", "for", "in", "wait", "return", "true", "false", "null")
        val inputs = setOf("self", "target", "origin", "args", "event_name", "event_entity", "event_position", "event_amount", "event_tag", "event_data")
    }
}

private class AbilityCodeGenerator(private val source: SourceProgram, private val definition: AbilityProgramDefinition, private val registry: AbilityCapabilityRegistry) {
    private val used = linkedMapOf<String, Int>()
    private var totalInstructions = 0
    fun compile(): CompiledAbilityProgram {
        source.functions.values.forEach { if (registry.lookup(it.name) != null) it.at.fail("Function '${it.name}' shadows a capability.") }
        val functions = source.functions.mapValues { FunctionGenerator(it.value).compile() }
        val handlers = source.handlers.mapValues { FunctionGenerator(it.value).compile() }
        return CompiledAbilityProgram(AbilityPrograms.freezeDefinition(definition), Collections.unmodifiableMap(LinkedHashMap(used)), Collections.unmodifiableMap(functions), Collections.unmodifiableMap(handlers), registry)
    }
    private inner class FunctionGenerator(private val declaration: Declaration) {
        private val code = mutableListOf<AbilityInstruction>()
        private val scopes = mutableListOf<MutableMap<String, Int>>()
        private var slots = 0
        private fun emit(op: AbilityOp, value: Any? = null, at: AbilityLocation = declaration.at): Int {
            if (++totalInstructions > 8192) at.fail("Program exceeds 8192 instructions.")
            code += AbilityInstruction(op, value, at); return code.lastIndex
        }
        private fun patch(index: Int, target: Int = code.size) { code[index] = code[index].copy(argument = target) }
        private fun local(name: String): Int? = scopes.asReversed().firstNotNullOfOrNull { it[name] }
        private fun allocate(name: String?, at: AbilityLocation): Int {
            if (slots >= 256) at.fail("A function uses at most 256 local slots.")
            if (name != null) {
                if (name in AbilityParser.inputs || name == "state") at.fail("Local '$name' shadows an input.")
                if (name in scopes.last()) at.fail("Duplicate local '$name'.")
            }
            val slot = slots++
            if (name != null) scopes.last()[name] = slot
            return slot
        }
        fun compile(): AbilityCode {
            scopes += linkedMapOf<String, Int>()
            declaration.parameters.forEach { allocate(it, declaration.at) }
            block(declaration.body)
            emit(AbilityOp.CONSTANT, AbilityValues.none()); emit(AbilityOp.RETURN)
            return AbilityCode(declaration.name, Collections.unmodifiableList(ArrayList(declaration.parameters)), slots, Collections.unmodifiableList(ArrayList(code)))
        }
        private fun block(statements: List<Statement>) {
            scopes += linkedMapOf<String, Int>()
            statements.forEach(::statement)
            scopes.removeAt(scopes.lastIndex)
        }
        private fun expected(actual: AbilityType, expected: AbilityType, at: AbilityLocation) {
            if (actual != AbilityType.ANY && expected != AbilityType.ANY && actual != expected) at.fail("Expected $expected, found $actual.")
        }
        private fun statement(statement: Statement) {
            val at = statement.at
            when (statement) {
                is Statement.Let -> { expression(statement.value); emit(AbilityOp.STORE_LOCAL, allocate(statement.name, at), at) }
                is Statement.Assign -> {
                    expression(statement.value)
                    when (val target = statement.target) {
                        is Expr.Variable -> emit(AbilityOp.STORE_LOCAL, local(target.name) ?: at.fail("Unknown or read-only local '${target.name}'."), at)
                        is Expr.State -> emit(AbilityOp.STORE_STATE, target.name, at)
                        else -> at.fail("Invalid assignment.")
                    }
                }
                is Statement.Expression -> { expression(statement.value); emit(AbilityOp.POP, at = at) }
                is Statement.Wait -> { expected(expression(statement.ticks), AbilityType.NUMBER, at); emit(AbilityOp.WAIT, at = at) }
                is Statement.Return -> { if (statement.value == null) emit(AbilityOp.CONSTANT, AbilityValues.none(), at) else expression(statement.value); emit(AbilityOp.RETURN, at = at) }
                is Statement.If -> {
                    expected(expression(statement.condition), AbilityType.BOOL, at)
                    val no = emit(AbilityOp.JUMP_FALSE, -1, at); block(statement.yes)
                    val end = emit(AbilityOp.JUMP, -1, at); patch(no); block(statement.no); patch(end)
                }
                is Statement.While -> {
                    val start = code.size; expected(expression(statement.condition), AbilityType.BOOL, at)
                    val end = emit(AbilityOp.JUMP_FALSE, -1, at); block(statement.body); emit(AbilityOp.JUMP, start, at); patch(end)
                }
                is Statement.Repeat -> {
                    expected(expression(statement.count), AbilityType.NUMBER, at); emit(AbilityOp.COUNT, at = at)
                    val slot = allocate(null, at); emit(AbilityOp.STORE_LOCAL, slot, at)
                    val start = code.size
                    emit(AbilityOp.LOAD_LOCAL, slot, at); emit(AbilityOp.CONSTANT, AbilityValues.number(0.0), at); emit(AbilityOp.BINARY, ">", at)
                    val end = emit(AbilityOp.JUMP_FALSE, -1, at); block(statement.body)
                    emit(AbilityOp.LOAD_LOCAL, slot, at); emit(AbilityOp.CONSTANT, AbilityValues.number(1.0), at); emit(AbilityOp.BINARY, "-", at); emit(AbilityOp.STORE_LOCAL, slot, at)
                    emit(AbilityOp.JUMP, start, at); patch(end)
                }
                is Statement.For -> {
                    expected(expression(statement.list), AbilityType.LIST, at)
                    val list = allocate(null, at); emit(AbilityOp.STORE_LOCAL, list, at)
                    val index = allocate(null, at); emit(AbilityOp.CONSTANT, AbilityValues.number(0.0), at); emit(AbilityOp.STORE_LOCAL, index, at)
                    scopes += linkedMapOf<String, Int>(); val item = allocate(statement.name, at)
                    val start = code.size
                    emit(AbilityOp.LOAD_LOCAL, index, at); emit(AbilityOp.LOAD_LOCAL, list, at); capability("list.size", listOf(AbilityType.LIST), at); emit(AbilityOp.BINARY, "<", at)
                    val end = emit(AbilityOp.JUMP_FALSE, -1, at)
                    emit(AbilityOp.LOAD_LOCAL, list, at); emit(AbilityOp.LOAD_LOCAL, index, at); capability("list.get", listOf(AbilityType.LIST, AbilityType.NUMBER), at); emit(AbilityOp.STORE_LOCAL, item, at)
                    block(statement.body)
                    emit(AbilityOp.LOAD_LOCAL, index, at); emit(AbilityOp.CONSTANT, AbilityValues.number(1.0), at); emit(AbilityOp.BINARY, "+", at); emit(AbilityOp.STORE_LOCAL, index, at)
                    emit(AbilityOp.JUMP, start, at); patch(end); scopes.removeAt(scopes.lastIndex)
                }
            }
        }
        private fun expression(expression: Expr): AbilityType {
            val at = expression.at
            return when (expression) {
                is Expr.Literal -> {
                    emit(AbilityOp.CONSTANT, expression.value, at)
                    when (expression.value) {
                        is AbilityValue.NumberValue -> AbilityType.NUMBER
                        is AbilityValue.BoolValue -> AbilityType.BOOL
                        is AbilityValue.TextValue -> AbilityType.TEXT
                        else -> AbilityType.ANY
                    }
                }
                is Expr.Variable -> {
                    val slot = local(expression.name)
                    if (slot != null) emit(AbilityOp.LOAD_LOCAL, slot, at)
                    else if (expression.name in AbilityParser.inputs) emit(AbilityOp.LOAD_INPUT, expression.name, at)
                    else at.fail("Unknown variable '${expression.name}'.")
                    AbilityType.ANY
                }
                is Expr.State -> { emit(AbilityOp.LOAD_STATE, expression.name, at); AbilityType.ANY }
                is Expr.ListExpr -> { expression.values.forEach(::expression); emit(AbilityOp.LIST, expression.values.size, at); AbilityType.LIST }
                is Expr.Call -> {
                    val types = expression.arguments.map(::expression)
                    val function = source.functions[expression.name]
                    if (function != null) {
                        if (types.size != function.parameters.size) at.fail("Function '${function.name}' expects ${function.parameters.size} arguments.")
                        emit(AbilityOp.CALL, AbilityCall(function.name, types.size, true), at); AbilityType.ANY
                    } else capability(expression.name, types, at)
                }
                is Expr.Unary -> {
                    val type = if (expression.operator == "!") AbilityType.BOOL else AbilityType.NUMBER
                    expected(expression(expression.value), type, at); emit(AbilityOp.UNARY, expression.operator, at); type
                }
                is Expr.Binary -> {
                    val left = expression(expression.left)
                    if (expression.operator == "&&" || expression.operator == "||") {
                        expected(left, AbilityType.BOOL, at); emit(AbilityOp.DUP, at = at)
                        val done = emit(if (expression.operator == "&&") AbilityOp.JUMP_FALSE else AbilityOp.JUMP_TRUE, -1, at)
                        emit(AbilityOp.POP, at = at)
                        val right = expression(expression.right)
                        expected(right, AbilityType.BOOL, at)
                        if (right == AbilityType.ANY) emit(AbilityOp.CHECK, AbilityType.BOOL, at)
                        patch(done); AbilityType.BOOL
                    } else {
                        val right = expression(expression.right)
                        if (expression.operator !in setOf("==", "!=")) {
                            expected(left, AbilityType.NUMBER, at); expected(right, AbilityType.NUMBER, at)
                        }
                        emit(AbilityOp.BINARY, expression.operator, at)
                        if (expression.operator in setOf("+", "-", "*", "/", "%")) AbilityType.NUMBER else AbilityType.BOOL
                    }
                }
            }
        }
        private fun capability(name: String, arguments: List<AbilityType>, at: AbilityLocation): AbilityType {
            val spec = registry.lookup(name) ?: at.fail("Unknown function or capability '$name'.")
            val version = definition.requires[name] ?: 1
            if (spec.version != version) at.fail("Capability '$name' needs explicit compatible version ${spec.version} in requires.")
            if (arguments.size != spec.arguments.size) at.fail("Capability '$name' expects ${spec.arguments.size} arguments.")
            arguments.forEachIndexed { i, type -> expected(type, spec.arguments[i], at) }
            used[name] = version
            emit(AbilityOp.CALL, AbilityCall(name, arguments.size, false), at)
            return spec.result
        }
    }
}
