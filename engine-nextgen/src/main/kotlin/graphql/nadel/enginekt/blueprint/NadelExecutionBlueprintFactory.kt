package graphql.nadel.enginekt.blueprint

import graphql.nadel.dsl.EnumTypeDefinitionWithTransformation
import graphql.nadel.dsl.InputObjectTypeDefinitionWithTransformation
import graphql.nadel.dsl.InterfaceTypeDefinitionWithTransformation
import graphql.nadel.dsl.ObjectTypeDefinitionWithTransformation
import graphql.nadel.dsl.TypeMappingDefinition
import graphql.schema.GraphQLSchema

object NadelExecutionBlueprintFactory {
    fun create(overallSchema: GraphQLSchema): NadelExecutionBlueprint {
        val underlyingTypes = getUnderlyingTypes(overallSchema)
        val underlyingFields = getUnderlyingFields(overallSchema)

        TODO()
    }

    private fun getUnderlyingFields(overallSchema: GraphQLSchema) {
        overallSchema.typeMap
            .asSequence()
    }

    private fun getUnderlyingTypes(overallSchema: GraphQLSchema): List<NadelUnderlyingType> {
        return overallSchema.typeMap.values.mapNotNull { type ->
            when (val def = type.definition) {
                is ObjectTypeDefinitionWithTransformation -> getUnderlyingType(def.typeMappingDefinition)
                is InterfaceTypeDefinitionWithTransformation -> getUnderlyingType(def.typeMappingDefinition)
                is InputObjectTypeDefinitionWithTransformation -> getUnderlyingType(def.typeMappingDefinition)
                is EnumTypeDefinitionWithTransformation -> getUnderlyingType(def.typeMappingDefinition)
                else -> null
            }
        }
    }

    private fun getUnderlyingType(typeMappingDefinition: TypeMappingDefinition): NadelUnderlyingType {
        return NadelUnderlyingType(
            overallName = typeMappingDefinition.overallName,
            underlyingName = typeMappingDefinition.underlyingName,
        )
    }
}