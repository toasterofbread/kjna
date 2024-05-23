
package dev.toastbits.kje.c

import dev.toastbits.kje.grammar.*

data class CStructDefinition(
    val fields: Map<String, CValueType>
)

fun parseStructDefinition(members: List<CParser.StructDeclarationContext>): CStructDefinition {
    val fields: MutableMap<String, CValueType> = mutableMapOf()

    for (member in members) {
        val declarator: CParser.DeclaratorContext = member.structDeclaratorList()?.structDeclarator()?.firstOrNull()?.declarator() ?: continue
        val name: String = declarator.directDeclarator().Identifier()?.text ?: continue
        val pointer_depth: Int = declarator.pointer()?.Star()?.size ?: 0

        val qualifiers: MutableList<CParser.TypeQualifierContext> = mutableListOf()
        val specifiers: MutableList<CParser.TypeSpecifierContext> = mutableListOf()

        var qualifier_list: CParser.SpecifierQualifierListContext? = member.specifierQualifierList()

        while (qualifier_list != null) {
            qualifier_list.typeQualifier()?.also { qualifiers.add(it) }
            qualifier_list.typeSpecifier()?.also { specifiers.add(it) }
            qualifier_list = qualifier_list.specifierQualifierList()
        }

        val type: CType = parseType(specifiers, qualifiers)
        val value_type: CValueType = CValueType(type, pointer_depth = pointer_depth)

        fields[name] = value_type
    }

    return CStructDefinition(fields)
}
