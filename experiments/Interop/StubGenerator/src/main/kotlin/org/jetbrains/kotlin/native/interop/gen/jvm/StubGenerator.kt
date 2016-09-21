package org.jetbrains.kotlin.native.interop.gen.jvm

import org.jetbrains.kotlin.native.interop.indexer.*

class StubGenerator(
        val nativeIndex: NativeIndex,
        val pkgName: String,
        val libName: String,
        val excludedFunctions: Set<String>) {

    val forbiddenStructNames = run {
        val functionNames = nativeIndex.functions.map { it.name }
        val fieldNames = nativeIndex.structs.mapNotNull { it.def }.flatMap { it.fields }.map { it.name }
        (functionNames + fieldNames).toSet()
    }

    val StructDecl.kotlinName: String
        get() = if (spelling.startsWith("struct ") || spelling.startsWith("union ")) {
            spelling.substringAfter(' ')
        } else {
            spelling
        }

    val EnumDef.kotlinName: String
        get() = if (spelling.startsWith("enum ")) {
            spelling.substringAfter(' ')
        } else {
            spelling
        }

    val RecordType.kotlinName: String
        get() = decl.kotlinName

    val EnumType.kotlinName: String
        get() = def.kotlinName


    val functionsToBind = nativeIndex.functions.filter { it.name !in excludedFunctions }

    private fun mangleStructName(name: String) = if (name !in forbiddenStructNames) name else (name + "Struct")

    val StructDecl.mangledName: String
        get() = mangleStructName(kotlinName)

    private var out: (String) -> Unit = {
        throw IllegalStateException()
    }

    fun <R> withOutput(output: (String) -> Unit, action: () -> R): R {
        val oldOut = out
        out = output
        try {
            return action()
        } finally {
            out = oldOut
        }
    }

    private fun <R> transaction(action: () -> R): R {
        val lines = mutableListOf<String>()
        val res = withOutput({ lines.add(it) }, action)
        // if action is completed successfully:
        lines.forEach(out)
        return res
    }

    private fun <R> indent(action: () -> R): R {
        val oldOut = out
        return withOutput({ oldOut("    $it") }, action)
    }

    // TODO: use libclang to implement:
    fun Type.getStringRepresentation(): String {
        return when (this) {

            is VoidType -> "void"
            is Int8Type -> "char"
            is UInt8Type -> "unsigned char"
            is Int16Type -> "short"
            is UInt16Type -> "unsigned short"
            is Int32Type -> "int"
            is UInt32Type -> "unsigned int"
            is IntPtrType -> "intptr_t"
            is UIntPtrType -> "uintptr_t"
            is Int64Type -> "int64_t"
            is UInt64Type -> "uint64_t"

            is PointerType -> {
                val pointeeType = this.pointeeType
                if (pointeeType is FunctionType) {
                    pointeeType.returnType.getStringRepresentation() + " (*)(" +
                            pointeeType.parameterTypes.map { it.getStringRepresentation() }.joinToString(", ") + ")"
                } else {
                    pointeeType.getStringRepresentation() + "*"
                }
            }

            is RecordType -> this.decl.spelling

            is ArrayType -> "void*" // TODO

            is EnumType -> this.def.spelling

            else -> throw kotlin.NotImplementedError()
        }
    }

    val PrimitiveType.kotlinType: String
        get() = when (this) {

            is Int8Type, is UInt8Type -> "Byte"
            is Int16Type, is UInt16Type -> "Short"
            is Int32Type, is UInt32Type -> "Int"
            is IntPtrType, is UIntPtrType, // TODO: 64-bit specific
            is Int64Type, is UInt64Type -> "Long"

            else -> throw NotImplementedError()
        }

    class NativeRefType(val typeName: String, val typeExpr: String = typeName)

    fun getKotlinTypeForRefTo(type: Type): NativeRefType = when (type) {
        is Int8Type, is UInt8Type -> NativeRefType("Int8Box")
        is Int16Type, is UInt16Type -> NativeRefType("Int16Box")
        is Int32Type, is UInt32Type -> NativeRefType("Int32Box")
        is IntPtrType, is UIntPtrType, // TODO: 64-bit specific
        is Int64Type, is UInt64Type -> NativeRefType("Int64Box")

        is RecordType -> NativeRefType("${mangleStructName(type.kotlinName)}")

        is EnumType -> NativeRefType("${type.kotlinName}.ref")

        is PointerType -> {
            if (type.pointeeType is VoidType || type.pointeeType is FunctionType) {
                NativeRefType("NativePtrBox")
            } else {
                val pointeeRefType = getKotlinTypeForRefTo(type.pointeeType)
                NativeRefType("RefBox<${pointeeRefType.typeName}>", "${pointeeRefType.typeExpr}.ref")
            }
        }

        is ConstArrayType -> {
            val elemRefType = getKotlinTypeForRefTo(type.elemType)
            NativeRefType("NativeArray<${elemRefType.typeName}>", "array[${type.length}](${elemRefType.typeExpr})")
        }

        is IncompleteArrayType -> {
            val elemRefType = getKotlinTypeForRefTo(type.elemType)
            NativeRefType("NativeArray<${elemRefType.typeName}>", "array(${elemRefType.typeExpr})")
        }

        else -> throw NotImplementedError()
    }

    class OutValueBinding(val kotlinType: String,
                          val kotlinConv: ((String) -> String)? = null,
                          val convFree: ((String) -> String)? = null,
                          val kotlinJniBridgeType: String = kotlinType)

    class InValueBinding(val kotlinJniBridgeType: String,
                         val conv: ((String) -> String) = { it },
                         val kotlinType: String = kotlinJniBridgeType)

    fun outValueRefBinding(refType: NativeRefType) = OutValueBinding(
            kotlinType = refType.typeName + "?",
            kotlinConv = { "$it.getNativePtr().asLong()" },
            kotlinJniBridgeType = "Long"
    )

    fun inValueRefBinding(refType: NativeRefType) = InValueBinding(
            kotlinJniBridgeType = "Long",
            conv = { "NativePtr.byValue($it).asRef(${refType.typeExpr})" },
            kotlinType = refType.typeName + "?"
    )

    fun getOutValueBinding(type: Type): OutValueBinding = when (type) {

        is PrimitiveType -> OutValueBinding(type.kotlinType)

        is PointerType -> {
            if (type.pointeeType is VoidType || type.pointeeType is FunctionType) {
                OutValueBinding(
                        kotlinType = "NativePtr?",
                        kotlinConv = { "$it.asLong()" },
                        kotlinJniBridgeType = "Long"
                )
            } else if (type.pointeeType is Int8Type) {
                OutValueBinding(
                        kotlinType = "String?",
                        kotlinConv = { name -> "CString.fromString($name).getNativePtr().asLong()" },
                        convFree = { name -> "free(NativePtr.byValue($name))" },
                        kotlinJniBridgeType = "Long"
                )
            } else {
                outValueRefBinding(getKotlinTypeForRefTo(type.pointeeType))
            }
        }

        is EnumType -> OutValueBinding(
                kotlinType = type.kotlinName,
                kotlinConv = { "$it.value" },
                kotlinJniBridgeType = type.def.baseType.kotlinType
        )

        is ArrayType -> outValueRefBinding(getKotlinTypeForRefTo(type))

        is RecordType -> {
            val refType = getKotlinTypeForRefTo(type)
            // pointer will be converted to value in C code
            OutValueBinding(
                    kotlinType = refType.typeName,
                    kotlinConv = { "$it.getNativePtr().asLong()" },
                    kotlinJniBridgeType = "Long"
            )
        }

        else -> throw NotImplementedError()
    }

    fun getInValueBinding(type: Type): InValueBinding = when (type) {

        is PrimitiveType -> InValueBinding(type.kotlinType)

        is VoidType -> InValueBinding("Unit")

        is PointerType -> {
            if (type.pointeeType is VoidType || type.pointeeType is FunctionType) {
                InValueBinding(
                        kotlinJniBridgeType = "Long",
                        conv = { "NativePtr.byValue($it)" },
                        kotlinType = "NativePtr?"
                )
            } else {
                inValueRefBinding(getKotlinTypeForRefTo(type.pointeeType))
            }
        }

        is EnumType -> InValueBinding(
                kotlinJniBridgeType = type.def.baseType.kotlinType,
                conv = { "${type.kotlinName}.byValue($it)" },
                kotlinType = type.kotlinName
        )

        is ArrayType -> inValueRefBinding(getKotlinTypeForRefTo(type))

        is RecordType -> {
            // TODO: valid only for return values
            val refType = getKotlinTypeForRefTo(type)
            InValueBinding(
                    kotlinJniBridgeType = "Long",
                    conv = { "NativePtr.byValue($it).asRef(${refType.typeExpr})!!" },
                    kotlinType = refType.typeName
            )
        }

        else -> throw NotImplementedError()
    }


    private fun generateKotlinStruct(decl: StructDecl) {
        val def = decl.def
        if (def == null) {
            generateKotlinForwardStruct(decl)
            return
        }

        val className = decl.mangledName
        out("class $className(ptr: NativePtr) : NativeStruct(ptr) {")
        indent {
            out("")
            out("companion object : Type<$className>(${def.size}, ::$className)")
            out("")
            def.fields.forEach { field ->
                try {
                    if (field.offset < 0) throw NotImplementedError();
                    assert(field.offset % 8 == 0L)
                    val offset = field.offset / 8
                    val fieldRefType = getKotlinTypeForRefTo(field.type)
                    out("val ${field.name} by ${fieldRefType.typeExpr} at $offset")
                } catch (e: Throwable) {
                    println("Warning: cannot generate definition for field $className.${field.name}")
                }
            }
        }
        out("}")
    }

    private fun generateKotlinForwardStruct(s: StructDecl) {
        val className = s.mangledName
        out("class $className(ptr: NativePtr) : NativeRef(ptr) {")
        out("    companion object : Type<$className>(::$className)")
        out("}")
    }

    private fun generateKotlinEnum(e: EnumDef) {
        val baseRefType = getKotlinTypeForRefTo(e.baseType)

        out("enum class ${e.kotlinName}(val value: ${e.baseType.kotlinType}) {")
        indent {
            e.values.forEach {
                out("${it.name}(${it.value}),")
            }
            out(";")
            out("")
            out("companion object {")
            out("    fun byValue(value: ${e.baseType.kotlinType}) = ${e.kotlinName}.values().find { it.value == value }!!")
            out("}")
            out("")
            out("class ref(ptr: NativePtr) : NativeRef(ptr) {")
            out("    companion object : TypeWithSize<ref>(${baseRefType.typeExpr}.size, ::ref)")
            out("    var value: ${e.kotlinName}")
            out("        get() = byValue(${baseRefType.typeExpr}.byPtr(ptr).value)")
            out("        set(value) { ${baseRefType.typeExpr}.byPtr(ptr).value = value.value }")
            out("}")

        }
        out("}")
    }

    private fun retValBinding(func: FunctionDecl) = getInValueBinding(func.returnType)

    private fun paramBindings(func: FunctionDecl): Array<OutValueBinding> {
        val paramBindings = func.parameters.map { param ->
            getOutValueBinding(param.type)
        }.toMutableList()

        val retValType = func.returnType
        if (retValType is RecordType) {
            val retValRefType = getKotlinTypeForRefTo(retValType)
            val typeExpr = retValRefType.typeExpr

            paramBindings.add(OutValueBinding(
                    kotlinType = "Placement",
                    kotlinConv = { name -> "$name.alloc($typeExpr.size).asLong()" },
                    kotlinJniBridgeType = "Long"
            ))
        }

        return paramBindings.toTypedArray()
    }

    private fun paramNames(func: FunctionDecl): Array<String> {
        val paramNames = func.parameters.mapIndexed { i: Int, parameter: Parameter ->
            val name = parameter.name
            if (name != null && name != "") {
                name
            } else {
                "arg$i"
            }
        }.toMutableList()

        if (func.returnType is RecordType) {
            paramNames.add("retValPlacement")
        }

        return paramNames.toTypedArray()
    }

    private fun generateKotlinBindingMethod(func: FunctionDecl) {
        val paramNames = paramNames(func)
        val paramBindings = paramBindings(func)
        val retValBinding = retValBinding(func)

        val args = paramNames.mapIndexed { i: Int, name: String ->
            "$name: " + paramBindings[i].kotlinType
        }.joinToString(", ")

        out("fun ${func.name}($args): ${retValBinding.kotlinType} {")
        indent {
            val externalParamNames = paramNames.mapIndexed { i: Int, name: String ->
                val binding = paramBindings[i]
                val externalParamName: String

                if (binding.kotlinConv != null) {
                    externalParamName = "_$name"
                    out("val $externalParamName = " + binding.kotlinConv.invoke(name))
                } else {
                    externalParamName = name
                }

                externalParamName

            }
            out("val res = externals.${func.name}(" + externalParamNames.joinToString(", ") + ")")
            paramNames.forEachIndexed { i, name ->
                val binding = paramBindings[i]
                if (binding.convFree != null) {
                    assert(binding.kotlinConv != null)
                    out(binding.convFree.invoke(externalParamNames[i]))
                }
            }
            out("return " + retValBinding.conv("res"))
        }
        out("}")
    }

    private fun generateKotlinExternalMethod(func: FunctionDecl) {
        val paramNames = paramNames(func)
        val paramBindings = paramBindings(func)
        val retValBinding = retValBinding(func)

        val args = paramNames.mapIndexed { i: Int, name: String ->
            "$name: " + paramBindings[i].kotlinJniBridgeType
        }.joinToString(", ")

        out("external fun ${func.name}($args): ${retValBinding.kotlinJniBridgeType}")
    }

    fun generateKotlinFile() {
        if (pkgName != "") {
            out("package $pkgName")
            out("")
        }
        out("import kotlin_native.interop.*")
        out("")

        functionsToBind.forEach {
            try {
                transaction {
                    generateKotlinBindingMethod(it)
                    out("")
                }
            } catch (e: Throwable) {
                println("Warning: cannot generate binding definition for function ${it.name}")
            }
        }

        nativeIndex.structs.forEach { s ->
            try {
                transaction {
                    generateKotlinStruct(s)
                    out("")
                }
            } catch (e: Throwable) {
                println("Warning: cannot generate definition for struct ${s.kotlinName}")
            }
        }

        nativeIndex.enums.forEach { e ->
            generateKotlinEnum(e)
            out("")
        }

        out("object externals {")
        indent {
            out("init { System.loadLibrary(\"$libName\") }")
            functionsToBind.forEach {
                try {
                    transaction {
                        generateKotlinExternalMethod(it)
                        out("")
                    }
                } catch (e: Throwable) {
                    println("Warning: cannot generate external definition for function ${it.name}")
                }
            }
        }
        out("}")
    }

    fun getCJniBridgeType(kotlinJniBridgeType: String) = when (kotlinJniBridgeType) {
        "Unit" -> "void"
        "Byte" -> "jbyte"
        "Short" -> "jshort"
        "Int" -> "jint"
        "Long" -> "jlong"
        else -> throw NotImplementedError(kotlinJniBridgeType)
    }

    fun generateCFile(headerFiles: List<String>) {
        out("#include <stdint.h>")
        out("#include <jni.h>")
        headerFiles.forEach {
            out("#include <$it>")
        }
        out("")

        functionsToBind.forEach { func ->
            try {
                generateCJniFunction(func)
            } catch (e: Throwable) {
                System.err.println("Warning: cannot generate C JNI function definition ${func.name}")
            }
        }
    }

    private fun generateCJniFunction(func: FunctionDecl) {
        val funcReturnType = func.returnType
        val paramNames = paramNames(func)
        val paramBindings = paramBindings(func)
        val retValBinding = retValBinding(func)

        val args =
                if (paramBindings.isEmpty())
                    ""
                else paramBindings
                        .map { getCJniBridgeType(it.kotlinJniBridgeType) }
                        .mapIndexed { i, type -> "$type ${paramNames[i]}" }
                        .joinToString(separator = ", ", prefix = ", ")

        val cReturnType = getCJniBridgeType(retValBinding.kotlinJniBridgeType)

        val params = func.parameters.mapIndexed { i, parameter ->
            val cType = parameter.type.getStringRepresentation()
            if (parameter.type is RecordType) {
                "*($cType*)${paramNames[i]}"
            } else {
                "($cType)${paramNames[i]}"
            }
        }.joinToString(", ")

        val callExpr = "${func.name}($params)"
        val funcFullName = if (pkgName.isEmpty()) {
            "externals.${func.name}"
        } else {
            "$pkgName.externals.${func.name}"
        }
        val jniFuncName = "Java_" + funcFullName.replace("_", "_1").replace('.', '_').replace("$", "_00024")

        out("JNIEXPORT $cReturnType JNICALL $jniFuncName (JNIEnv *env, jobject obj$args) {")

        if (cReturnType == "void") {
            out("    $callExpr;")
        } else if (funcReturnType is RecordType) {
            out("    *(${funcReturnType.decl.spelling}*)retValPlacement = $callExpr;")
            out("    return ($cReturnType) retValPlacement;")
        } else {
            out("    return ($cReturnType) ($callExpr);")
        }
        out("}")
    }
}