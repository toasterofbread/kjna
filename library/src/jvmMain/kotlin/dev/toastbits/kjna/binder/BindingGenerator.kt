package dev.toastbits.kjna.binder

import dev.toastbits.kjna.c.*
import dev.toastbits.kjna.runtime.RuntimeType
import dev.toastbits.kjna.binder.target.KJnaBinderTarget
import dev.toastbits.kjna.binder.target.BinderTargetDisabled
import dev.toastbits.kjna.binder.target.BinderTargetShared
import kotlin.reflect.KClass
import kotlin.contracts.contract
import kotlin.contracts.InvocationKind

class BindingGenerator(
    val binder: KJnaBinder,
    var getStructImport: (String) -> String,
    var getEnumImport: (String) -> String
) {
    fun buildKotlinFile(target: KJnaBinderTarget, pkg: String, buildContent: StringBuilderGenerationScope.() -> Unit) {
        val imports: MutableList<Pair<String, String?>> = mutableListOf()
        val unions: MutableMap<String, String> = mutableMapOf()
        val annotations: MutableList<String> = mutableListOf()

        val scope: StringBuilderGenerationScope =
            object : StringBuilderGenerationScope(target, imports, unions, annotations) {
                override fun build(): String {
                    val content: String = this.toString()
                    return buildString {
                        if (annotations.isNotEmpty()) {
                            for (annotation in annotations) {
                                append(annotation)
                            }
                            appendLine()
                        }
                        appendLine("package $pkg")

                        appendLine()
                        append(generateImportBlock(imports))

                        append(content)

                        appendLine()

                        if (unions.isNotEmpty()) {
                            appendLine()
                            for ((_, union_content) in unions) {
                                appendLine(union_content)
                            }
                        }
                    }
                }
            }

        buildContent(scope)
    }

    inner abstract class StringBuilderGenerationScope(
        target: KJnaBinderTarget,
        imports: MutableList<Pair<String, String?>>,
        unions: MutableMap<String, String>,
        annotations: MutableList<String>
    ): GenerationScope(target, imports, unions, annotations) {
        private val string: StringBuilder = StringBuilder()

        override fun toString(): String = string.toString()

        fun append(content: Any) { string.append(content) }
        fun appendLine() { string.appendLine() }
        fun appendLine(content: Any) { string.appendLine(content) }

        abstract fun build(): String
    }

    inner open class GenerationScope(
        val target: KJnaBinderTarget,
        private val imports: MutableList<Pair<String, String?>>,
        private val unions: MutableMap<String, String>,
        private val annotations: MutableList<String>
    ) {
        val binder: KJnaBinder get() = this@BindingGenerator.binder

        fun importFromCoordinates(coordinates: String, alias: String? = null) {
            check(!coordinates.contains(" ")) { coordinates }

            if (imports.none { it.first == coordinates }) {
                imports.add(Pair(coordinates, alias))
            }
        }

        fun addContainerAnnotation(annotation: String) {
            if (!annotations.contains(annotation)) {
                annotations.add(annotation)
            }
        }

        fun getFunctionParameterName(name: String?, index: Int, used_names: List<String>): String {
            if (name != null && !used_names.contains(name)) {
                return name
            }

            val base: String = name ?: "p"

            var offset: Int = 0
            while (used_names.contains(base + (index + offset).toString())) {
                offset++
            }

            return base + (index + offset).toString()
        }

        fun generateHeaderFileContent(header: KJnaBinder.Header, all_structs: List<CType.Struct>?): String =
            buildString {
                val class_modifiers: List<String> = target.getClassModifiers()
                for (modifier in class_modifiers) {
                    append(modifier)
                    append(' ')
                }

                val header_constructor: String = target.implementHeaderConstructor(this@GenerationScope) ?: ""
                appendLine("class ${header.class_name}$header_constructor {")

                val initialiser: String? = target.implementHeaderInitialiser(all_structs, this@GenerationScope)
                if (initialiser != null) {
                    appendLine(initialiser.prependIndent("    "))
                    appendLine()
                }

                for (function in header.info.functions) {
                    try {
                        val function_header: String = getKotlinFunctionHeader(function)
                        appendLine(target.implementFunction(function, function_header, this@GenerationScope).prependIndent("    "))
                    }
                    catch (e: Throwable) {
                        throw RuntimeException("Generating function $function in $header failed", e)
                    }
                }

                appendLine(
                    buildString {
                        appendLine()

                        for (modifier in class_modifiers) {
                            if (modifier == "expect") {
                                continue
                            }
                            append(modifier)
                            append(' ')
                        }
                        appendLine("companion object {")

                        appendLine(
                            when (target) {
                                is BinderTargetShared -> "fun isAvailable(): Boolean"
                                is BinderTargetDisabled -> "actual fun isAvailable(): Boolean = false"
                                else -> "actual fun isAvailable(): Boolean = true"
                            }.prependIndent("    ")
                        )
                        append("}")
                    }.prependIndent("    ")
                )

                append("}")
            }

        fun getKotlinFunctionHeader(function: CFunctionDeclaration): String {
            val header: StringBuilder = StringBuilder("fun ${function.name}(")

            val used_param_names: MutableList<String> = mutableListOf()
            val function_data_param_indices: List<Int> = function.parameters.mapNotNull { param ->
                if (param.type.type is CType.Function) {
                    return@mapNotNull param.type.type.data_send_param
                }
                return@mapNotNull null
            }

            val params: List<CFunctionParameter> = function.parameters.filterIndexed { index, _ -> !function_data_param_indices.contains(index) }

            for ((index, param) in params.withIndex()) {
                val param_name: String = getFunctionParameterName(param.name, index, used_param_names)
                used_param_names.add(param_name)

                val param_type: String? = param.type.toKotlinTypeName(false) { createUnion(function.name, index, it) }
                if (param_type == null) {
                    if (params.size == 1) {
                        break
                    }

                    throw RuntimeException("void used on its own $function")
                }

                header.append(param_name)
                header.append(": ")
                header.append(param_type)

                if (index + 1 != params.size) {
                    header.append(", ")
                }
            }

            header.append(")")

            val return_type: String = function.return_type.toKotlinTypeName(true) { createUnion(function.name, null, it) }!!
            if (return_type != "Unit") {
                header.append(": ")
                header.append(return_type)

                if (function.return_type?.type == CType.Primitive.CHAR) {
                    header.append('?')
                }
            }

            return header.toString()
        }

        fun CValueType?.toKotlinTypeName(
            is_return_type: Boolean,
            createUnion: (CType.Union) -> String
        ): String? {
            if (this == null) {
                return "Unit"
            }

            val actual_type: CValueType =
                if (type is CType.TypeDef) type.resolve(binder.typedefs).let { it.copy(pointer_depth = pointer_depth + it.pointer_depth) }
                else this

            var (type: String?, pointer_depth: Int) =
                type.toKotlinType(pointer_depth, createUnion = createUnion)

            if (actual_type.type is CType.Function) {
                if (actual_type.pointer_depth != 1) {
                    TODO(actual_type.toString())
                }

                return type!!
            }

            if (type == null) {
                if (is_return_type) {
                    return "Unit"
                }

                if (pointer_depth == 0) {
                    return null
                }
            }

            while (pointer_depth > 0) {
                pointer_depth--

                if (type == null) {
                    type = importRuntimeType(RuntimeType.KJnaPointer) + "?"
                }
                else {
                    type = importRuntimeType(RuntimeType.KJnaTypedPointer) + "<$type>?"
                }
            }

            if (type == "String") {
                type = "String?"
            }

            check(pointer_depth == 0) { this }
            return type!!
        }

        private fun CType.toKotlinType(pointer_depth: Int, createUnion: (CType.Union) -> String): Pair<String?, Int> {
            val kotlin_type: String?
            var pointer_depth: Int = pointer_depth

            when (this) {
                is CType.Primitive ->
                    when (this) {
                        CType.Primitive.VOID -> kotlin_type = null
                        CType.Primitive.CHAR,
                        CType.Primitive.U_CHAR -> {
                            if (pointer_depth > 0) {
                                kotlin_type = "String"
                                pointer_depth--
                            }
                            else {
                                kotlin_type = "Char"
                            }
                        }
                        CType.Primitive.SHORT -> kotlin_type = "Short"
                        CType.Primitive.U_SHORT -> kotlin_type = "UShort"
                        CType.Primitive.INT -> kotlin_type = "Int"
                        CType.Primitive.U_INT -> kotlin_type = "UInt"
                        CType.Primitive.LONG,
                        CType.Primitive.LONG_LONG -> kotlin_type = "Long"
                        CType.Primitive.U_LONG,
                        CType.Primitive.U_LONG_LONG -> kotlin_type = "ULong"
                        CType.Primitive.FLOAT -> kotlin_type = "Float"
                        CType.Primitive.DOUBLE,
                        CType.Primitive.LONG_DOUBLE -> kotlin_type = "Double"
                        CType.Primitive.BOOL -> kotlin_type = "Boolean"
                    }

                is CType.TypeDef -> {
                    val resolved_type: CValueType = resolve(binder.typedefs)

                    when (resolved_type.type) {
                        is CType.Struct -> {
                            importStruct(resolved_type.type.name, name)
                            kotlin_type = name
                            pointer_depth += resolved_type.pointer_depth
                        }
                        is CType.Enum -> {
                            importEnum(resolved_type.type.name, name)
                            kotlin_type = name
                            pointer_depth += resolved_type.pointer_depth
                        }
                        is CType.Union -> throw IllegalStateException(resolved_type.toString())
                        else -> {
                            resolved_type.type.toKotlinType(pointer_depth + resolved_type.pointer_depth, { throw IllegalStateException("Union") }).apply {
                                kotlin_type = first
                                pointer_depth = second
                            }
                        }
                    }
                }

                is CType.Struct -> {
                    importStruct(name)
                    kotlin_type = name
                }

                is CType.Enum -> {
                    importEnum(name)
                    kotlin_type = name
                }

                is CType.Union -> {
                    kotlin_type = createUnion(this)
                }

                is CType.Function -> {
                    kotlin_type = buildString {
                        append('(')
                        for ((index, param) in shape.parameters.withIndex()) {
                            if (param.name != null) {
                                append(param.name)
                                append(": ")
                            }
                            append(param.type.toKotlinTypeName(false, createUnion))

                            if (index + 1 != shape.parameters.size) {
                                append(", ")
                            }
                        }

                        append(") -> ")
                        append(shape.return_type.toKotlinTypeName(true, createUnion))
                    }
                }
            }

            return Pair(kotlin_type, pointer_depth)
        }

        fun importStruct(struct_name: String, alias: String? = null): String {
            importFromCoordinates(getStructImport(struct_name), alias)
            return alias ?: struct_name
        }

        private fun importEnum(enum_name: String, alias: String? = null) {
            importFromCoordinates(getEnumImport(enum_name), alias)
        }

        fun createUnion(scope_name: String, parameter_index: Int?, union: CType.Union): String {
            val name: String = getUnion(scope_name, parameter_index)
            check(!unions.contains(name)) { "Union name collision '$name'" }

            unions[name] = generateUnion(name, union, target)
            return name
        }

        fun getUnion(scope_name: String, parameter_index: Int?): String {
            return scope_name + "_union_" + parameter_index?.toString().orEmpty()
        }

        fun importRuntimeType(type: RuntimeType): String {
            importFromCoordinates(type.coordinates)
            return type.name
        }
    }
}