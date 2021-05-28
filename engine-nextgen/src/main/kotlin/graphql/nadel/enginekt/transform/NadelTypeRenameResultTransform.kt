package graphql.nadel.enginekt.transform

import graphql.introspection.Introspection
import graphql.nadel.Service
import graphql.nadel.ServiceExecutionResult
import graphql.nadel.enginekt.blueprint.NadelExecutionBlueprint
import graphql.nadel.enginekt.transform.query.NadelQueryTransformer
import graphql.nadel.enginekt.transform.query.NadelTransform
import graphql.nadel.enginekt.transform.query.NadelTransformFieldResult
import graphql.nadel.enginekt.transform.query.QueryPath
import graphql.nadel.enginekt.transform.result.NadelResultInstruction
import graphql.nadel.enginekt.transform.result.json.JsonNodeExtractor
import graphql.normalized.NormalizedField
import graphql.schema.GraphQLSchema


internal class NadelTypeRenameResultTransform : NadelTransform<NadelTypeRenameResultTransform.State> {
    data class State(
        val typeRenamePath: QueryPath
    )

    override fun isApplicable(userContext: Any?, overallSchema: GraphQLSchema, executionBlueprint: NadelExecutionBlueprint, service: Service, field: NormalizedField): State? {
        if (field.fieldName != Introspection.TypeNameMetaFieldDef.name) {
            return null
        }
        return State(
            typeRenamePath = QueryPath(field.listOfResultKeys)
        )
    }

    override fun transformField(transformer: NadelQueryTransformer.Continuation,
                                service: Service,
                                overallSchema: GraphQLSchema,
                                executionBlueprint: NadelExecutionBlueprint,
                                field: NormalizedField,
                                state: State): NadelTransformFieldResult {
        return NadelTransformFieldResult.unmodified(field)
    }

    override fun getResultInstructions(userContext: Any?,
                                       overallSchema: GraphQLSchema,
                                       executionBlueprint: NadelExecutionBlueprint,
                                       service: Service,
                                       field: NormalizedField,
                                       result: ServiceExecutionResult,
                                       state: State): List<NadelResultInstruction> {
        val nodes = JsonNodeExtractor.getNodesAt(
            result.data,
            field.listOfResultKeys,
            flatten = true,
        )
        return nodes.map {
            val underlyingTypeName = it.value as String
            val overallTypeName = getOverallTypeName(underlyingTypeName, executionBlueprint)
            return@map NadelResultInstruction.Set(it.path, overallTypeName)
        }
    }

    private fun getOverallTypeName(underlyingTypeName: String, executionBlueprint: NadelExecutionBlueprint): String {
        val typeRenameInstruction = executionBlueprint.typeInstructions.filter { it.value.underlyingName == underlyingTypeName }.values.single()
        return typeRenameInstruction.overallName
    }
}