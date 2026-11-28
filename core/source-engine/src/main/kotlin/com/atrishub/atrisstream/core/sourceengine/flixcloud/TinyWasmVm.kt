package com.atrishub.atrisstream.core.sourceengine.flixcloud

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal pure-Kotlin WebAssembly i32 stack VM for executing FlixCloud `w_payload` functions.
 */
class TinyWasmVm(bytes: ByteArray) {
    private data class FuncType(val paramCount: Int, val resultCount: Int)
    private data class FuncBody(val typeIdx: Int, val localsCount: Int, val code: ByteArray)
    private data class Frame(
        val type: FuncType,
        val locals: IntArray,
        val code: ByteArray,
        var pc: Int = 0
    )

    private val types = mutableListOf<FuncType>()
    private val funcTypeIndices = mutableListOf<Int>()
    private val funcBodies = mutableListOf<FuncBody>()
    private val globals = mutableListOf<Int>()
    private val exports = mutableMapOf<String, Int>()
    private var memory = ByteArray(65536)

    init {
        parse(bytes)
    }

    fun hasExport(name: String): Boolean = exports.containsKey(name)

    fun writeMemory(offset: Int, data: ByteArray) {
        require(offset >= 0 && offset + data.size <= memory.size) {
            "WASM memory write out of bounds: offset=$offset, size=${data.size}, capacity=${memory.size}"
        }
        data.copyInto(memory, destinationOffset = offset)
    }

    fun readMemory(offset: Int, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= memory.size) {
            "WASM memory read out of bounds: offset=$offset, length=$length, capacity=${memory.size}"
        }
        return memory.copyOfRange(offset, offset + length)
    }

    fun call(exportName: String, vararg args: Int): Int {
        val funcIdx = exports[exportName]
            ?: throw IllegalArgumentException("WASM export not found: $exportName")
        val stack = ArrayDeque<Int>()
        for (arg in args) stack.addLast(arg)
        execFunc(funcIdx, stack)
        return if (stack.isEmpty()) 0 else stack.removeLast()
    }

    private fun execFunc(funcIdx: Int, stack: ArrayDeque<Int>, depth: Int = 0) {
        if (depth > 64) error("WASM call depth exceeded")
        val body = funcBodies.getOrNull(funcIdx) ?: error("Invalid WASM function index: $funcIdx")
        val type = types[body.typeIdx]
        val locals = IntArray(type.paramCount + body.localsCount)
        for (i in type.paramCount - 1 downTo 0) {
            locals[i] = stack.removeLast()
        }
        val code = body.code
        val blocks = mutableListOf<Pair<Int, Int>>() // (startPc, endPc)
        var pc = 0

        while (pc < code.size) {
            val op = code[pc++].toInt() and 0xFF
            when (op) {
                0x00 -> error("WASM unreachable")
                0x01 -> Unit // nop
                0x02, 0x03 -> { // block / loop
                    pc++ // blocktype
                    val endPc = findMatchingEnd(code, pc)
                    blocks.add((if (op == 0x03) pc else endPc + 1) to endPc)
                }
                0x04 -> { // if
                    pc++ // blocktype
                    val cond = stack.removeLast()
                    val endPc = findMatchingEnd(code, pc)
                    if (cond == 0) {
                        val elsePc = findMatchingElse(code, pc, endPc)
                        if (elsePc != -1) {
                            blocks.add((endPc + 1) to endPc)
                            pc = elsePc + 1
                        } else {
                            pc = endPc + 1
                        }
                    } else {
                        blocks.add((endPc + 1) to endPc)
                    }
                }
                0x05 -> { // else
                    val (_, endPc) = blocks.removeAt(blocks.lastIndex)
                    pc = endPc + 1
                }
                0x0B -> { // end
                    if (blocks.isNotEmpty()) blocks.removeAt(blocks.lastIndex)
                }
                0x0C, 0x0D -> { // br / br_if
                    val (label, nextPc) = readVarU32(code, pc)
                    pc = nextPc
                    val cond = if (op == 0x0D) stack.removeLast() != 0 else true
                    if (cond) {
                        if (label >= blocks.size) return
                        repeat(label) { blocks.removeAt(blocks.lastIndex) }
                        val (targetPc, _) = blocks.last()
                        pc = targetPc
                    }
                }
                0x0F -> return
                0x10 -> { // call
                    val (callee, nextPc) = readVarU32(code, pc)
                    pc = nextPc
                    execFunc(callee, stack, depth + 1)
                }
                0x1A -> stack.removeLast()
                0x1B -> { // select
                    val c = stack.removeLast()
                    val v2 = stack.removeLast()
                    val v1 = stack.removeLast()
                    stack.addLast(if (c != 0) v1 else v2)
                }
                0x20 -> { val (i, n) = readVarU32(code, pc); pc = n; stack.addLast(locals[i]) }
                0x21 -> { val (i, n) = readVarU32(code, pc); pc = n; locals[i] = stack.removeLast() }
                0x22 -> { val (i, n) = readVarU32(code, pc); pc = n; locals[i] = stack.last() }
                0x23 -> { val (i, n) = readVarU32(code, pc); pc = n; stack.addLast(globals.getOrElse(i) { 0 }) }
                0x24 -> { val (i, n) = readVarU32(code, pc); pc = n; if (i < globals.size) globals[i] = stack.removeLast() }
                0x28 -> { val (_, n1) = readVarU32(code, pc); val (off, n2) = readVarU32(code, n1); pc = n2; stack.addLast(loadI32(stack.removeLast() + off)) }
                0x2C -> { val (_, n1) = readVarU32(code, pc); val (off, n2) = readVarU32(code, n1); pc = n2; stack.addLast(memory[stack.removeLast() + off].toInt()) }
                0x2D -> { val (_, n1) = readVarU32(code, pc); val (off, n2) = readVarU32(code, n1); pc = n2; stack.addLast(memory[stack.removeLast() + off].toInt() and 0xFF) }
                0x36 -> { val (_, n1) = readVarU32(code, pc); val (off, n2) = readVarU32(code, n1); pc = n2; val v = stack.removeLast(); storeI32(stack.removeLast() + off, v) }
                0x3A -> { val (_, n1) = readVarU32(code, pc); val (off, n2) = readVarU32(code, n1); pc = n2; val v = stack.removeLast(); memory[stack.removeLast() + off] = v.toByte() }
                0x41 -> { val (v, n) = readVarI32(code, pc); pc = n; stack.addLast(v) }
                0x45 -> stack.addLast(if (stack.removeLast() == 0) 1 else 0)
                0x46 -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(if (a == b) 1 else 0) }
                0x47 -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(if (a != b) 1 else 0) }
                0x48 -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(if (a < b) 1 else 0) }
                0x49 -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(if (a.toUInt() < b.toUInt()) 1 else 0) }
                0x4A -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(if (a > b) 1 else 0) }
                0x4B -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(if (a.toUInt() > b.toUInt()) 1 else 0) }
                0x4C -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(if (a <= b) 1 else 0) }
                0x4D -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(if (a.toUInt() <= b.toUInt()) 1 else 0) }
                0x4E -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(if (a >= b) 1 else 0) }
                0x4F -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(if (a.toUInt() >= b.toUInt()) 1 else 0) }
                0x67 -> stack.addLast(Integer.numberOfLeadingZeros(stack.removeLast()))
                0x68 -> stack.addLast(Integer.numberOfTrailingZeros(stack.removeLast()))
                0x69 -> stack.addLast(Integer.bitCount(stack.removeLast()))
                0x6A -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(a + b) }
                0x6B -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(a - b) }
                0x6C -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(a * b) }
                0x6D -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(a / b) }
                0x6E -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast((a.toUInt() / b.toUInt()).toInt()) }
                0x6F -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(a % b) }
                0x70 -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast((a.toUInt() % b.toUInt()).toInt()) }
                0x71 -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(a and b) }
                0x72 -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(a or b) }
                0x73 -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(a xor b) }
                0x74 -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(a shl (b and 31)) }
                0x75 -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(a shr (b and 31)) }
                0x76 -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(a ushr (b and 31)) }
                0x77 -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(Integer.rotateLeft(a, b)) }
                0x78 -> { val b = stack.removeLast(); val a = stack.removeLast(); stack.addLast(Integer.rotateRight(a, b)) }
                else -> error("Unsupported WASM opcode: 0x${op.toString(16)}")
            }
        }
    }

    private fun loadI32(addr: Int): Int =
        ByteBuffer.wrap(memory, addr, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun storeI32(addr: Int, value: Int) {
        ByteBuffer.wrap(memory, addr, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }

    private fun parse(bytes: ByteArray) {
        require(bytes.size >= 8 && bytes[0] == 0.toByte() && bytes[1] == 'a'.code.toByte() &&
            bytes[2] == 's'.code.toByte() && bytes[3] == 'm'.code.toByte()) {
            "Invalid WASM magic header"
        }
        var pos = 8
        while (pos < bytes.size) {
            val sectionId = bytes[pos++].toInt() and 0xFF
            val (sectionSize, nextPos) = readVarU32(bytes, pos)
            val sectionEnd = nextPos + sectionSize
            pos = nextPos
            when (sectionId) {
                1 -> { // Type
                    val (count, p0) = readVarU32(bytes, pos); pos = p0
                    repeat(count) {
                        pos++ // 0x60
                        val (pc, p1) = readVarU32(bytes, pos); pos = p1 + pc
                        val (rc, p2) = readVarU32(bytes, pos); pos = p2 + rc
                        types.add(FuncType(pc, rc))
                    }
                }
                3 -> { // Function
                    val (count, p0) = readVarU32(bytes, pos); pos = p0
                    repeat(count) {
                        val (typeIdx, p1) = readVarU32(bytes, pos); pos = p1
                        funcTypeIndices.add(typeIdx)
                    }
                }
                5 -> { // Memory
                    val (count, p0) = readVarU32(bytes, pos); pos = p0
                    if (count > 0) {
                        val (flags, p1) = readVarU32(bytes, pos); pos = p1
                        val (initPages, p2) = readVarU32(bytes, pos); pos = p2
                        if ((flags and 1) != 0) { val (_, p3) = readVarU32(bytes, pos); pos = p3 }
                        if (initPages > 1) memory = ByteArray(initPages * 65536)
                    }
                }
                6 -> { // Global
                    val (count, p0) = readVarU32(bytes, pos); pos = p0
                    repeat(count) {
                        pos += 2 // valtype + mut
                        var initVal = 0
                        if ((bytes[pos].toInt() and 0xFF) == 0x41) {
                            val (v, p1) = readVarI32(bytes, pos + 1)
                            initVal = v
                            pos = p1
                        }
                        while (pos < sectionEnd && (bytes[pos++].toInt() and 0xFF) != 0x0B) Unit
                        globals.add(initVal)
                    }
                }
                7 -> { // Export
                    val (count, p0) = readVarU32(bytes, pos); pos = p0
                    repeat(count) {
                        val (len, p1) = readVarU32(bytes, pos); pos = p1
                        val name = bytes.copyOfRange(pos, pos + len).decodeToString(); pos += len
                        val kind = bytes[pos++].toInt() and 0xFF
                        val (idx, p2) = readVarU32(bytes, pos); pos = p2
                        if (kind == 0) exports[name] = idx
                    }
                }
                10 -> { // Code
                    val (count, p0) = readVarU32(bytes, pos); pos = p0
                    repeat(count) { i ->
                        val (bodySize, p1) = readVarU32(bytes, pos); pos = p1
                        val bodyEnd = pos + bodySize
                        val (localDeclCount, p2) = readVarU32(bytes, pos); pos = p2
                        var totalLocals = 0
                        repeat(localDeclCount) {
                            val (n, p3) = readVarU32(bytes, pos); pos = p3 + 1
                            totalLocals += n
                        }
                        val codeBytes = bytes.copyOfRange(pos, bodyEnd)
                        funcBodies.add(FuncBody(funcTypeIndices[i], totalLocals, codeBytes))
                        pos = bodyEnd
                    }
                }
                11 -> { // Data
                    val (count, p0) = readVarU32(bytes, pos); pos = p0
                    repeat(count) {
                        val (mode, p1) = readVarU32(bytes, pos); pos = p1
                        var offset = 0
                        if (mode == 0) {
                            if ((bytes[pos].toInt() and 0xFF) == 0x41) {
                                val (v, p2) = readVarI32(bytes, pos + 1); offset = v; pos = p2
                            }
                            while (pos < sectionEnd && (bytes[pos++].toInt() and 0xFF) != 0x0B) Unit
                        }
                        val (size, p3) = readVarU32(bytes, pos); pos = p3
                        bytes.copyInto(memory, destinationOffset = offset, startIndex = pos, endIndex = pos + size)
                        pos += size
                    }
                }
            }
            pos = sectionEnd
        }
    }

    private fun findMatchingEnd(code: ByteArray, startPc: Int): Int {
        var depth = 1
        var pc = startPc
        while (pc < code.size) {
            val op = code[pc++].toInt() and 0xFF
            when (op) {
                0x02, 0x03, 0x04 -> { depth++; pc++ }
                0x0B -> if (--depth == 0) return pc - 1
                0x0C, 0x0D, 0x10, 0x20, 0x21, 0x22, 0x23, 0x24 -> { val (_, n) = readVarU32(code, pc); pc = n }
                0x28, 0x2C, 0x2D, 0x36, 0x3A -> { val (_, n1) = readVarU32(code, pc); val (_, n2) = readVarU32(code, n1); pc = n2 }
                0x41 -> { val (_, n) = readVarI32(code, pc); pc = n }
            }
        }
        return code.size - 1
    }

    private fun findMatchingElse(code: ByteArray, startPc: Int, endPc: Int): Int {
        var depth = 1
        var pc = startPc
        while (pc < endPc) {
            val op = code[pc++].toInt() and 0xFF
            when (op) {
                0x02, 0x03, 0x04 -> { depth++; pc++ }
                0x05 -> if (depth == 1) return pc - 1
                0x0B -> depth--
                0x0C, 0x0D, 0x10, 0x20, 0x21, 0x22, 0x23, 0x24 -> { val (_, n) = readVarU32(code, pc); pc = n }
                0x28, 0x2C, 0x2D, 0x36, 0x3A -> { val (_, n1) = readVarU32(code, pc); val (_, n2) = readVarU32(code, n1); pc = n2 }
                0x41 -> { val (_, n) = readVarI32(code, pc); pc = n }
            }
        }
        return -1
    }

    private fun readVarU32(bytes: ByteArray, start: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var pos = start
        while (pos < bytes.size) {
            val b = bytes[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return result to pos
    }

    private fun readVarI32(bytes: ByteArray, start: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var pos = start
        var b: Int
        do {
            b = bytes[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            shift += 7
        } while ((b and 0x80) != 0)
        if (shift < 32 && (b and 0x40) != 0) result = result or ((-1) shl shift)
        return result to pos
    }
}
