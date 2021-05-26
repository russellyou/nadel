package graphql.nadel.enginekt.transform.deepRename

import graphql.nadel.Service
import graphql.nadel.ServiceExecutionResult
import graphql.nadel.enginekt.blueprint.NadelDeepRenameFieldInstruction
import graphql.nadel.enginekt.blueprint.NadelExecutionBlueprint
import graphql.nadel.enginekt.blueprint.getForField
import graphql.nadel.enginekt.transform.query.NadelPathToField
import graphql.nadel.enginekt.transform.query.NadelQueryTransformerContinue
import graphql.nadel.enginekt.transform.query.NadelTransform
import graphql.nadel.enginekt.transform.result.NadelResultInstruction
import graphql.nadel.enginekt.transform.result.json.JsonNodeExtractor
import graphql.nadel.enginekt.transform.result.util.RemoveUnusedField
import graphql.nadel.enginekt.util.JsonMap
import graphql.nadel.enginekt.util.emptyOrSingle
import graphql.normalized.NormalizedField
import graphql.schema.GraphQLSchema

internal class NadelDeepRenameTransform : NadelTransform<NadelDeepRenameTransform.State> {
    data class State(
        val instruction: NadelDeepRenameFieldInstruction,
        val alias: String,
    )

    override fun transformField(
        transformer: NadelQueryTransformerContinue,
        service: Service, // this has an underlying schema
        overallSchema: GraphQLSchema,
        executionBlueprint: NadelExecutionBlueprint,
        field: NormalizedField,
        state: State,
    ): List<NormalizedField> {
        return listOf(
            createDeepField(
                transformer,
                executionBlueprint,
                service,
                field,
                deepRename = state.instruction,
            ).transform {
                it.alias(state.alias)
            }
        )
    }

    private fun createDeepField(
        transformer: NadelQueryTransformerContinue,
        blueprint: NadelExecutionBlueprint,
        service: Service,
        field: NormalizedField,
        deepRename: NadelDeepRenameFieldInstruction,
    ): NormalizedField {
        val underlyingTypeName = field.objectTypeNames.first().let { overallTypeName ->
            blueprint.typeInstructions[overallTypeName]?.underlyingName ?: overallTypeName
        }

        val underlyingObjectType = service.underlyingSchema.getObjectType(underlyingTypeName)
            ?: error("No underlying object type")

        return NadelPathToField.createField(
            schema = service.underlyingSchema,
            parentType = underlyingObjectType,
            pathToField = deepRename.pathToSourceField,
            fieldChildren = transformer.transformNext(field.children),
        )
    }

    override fun isApplicable(
        userContext: Any?,
        overallSchema: GraphQLSchema,
        executionBlueprint: NadelExecutionBlueprint,
        service: Service,
        field: NormalizedField,
    ): State? {
        val instruction = executionBlueprint.fieldInstructions.getForField(field) as? NadelDeepRenameFieldInstruction
            ?: return null

        return State(
            instruction,
            "my_uuid"
            // UUID.randomUUID().toString(),
        )
    }

    override fun getResultInstructions(
        userContext: Any?,
        overallSchema: GraphQLSchema,
        executionBlueprint: NadelExecutionBlueprint,
        service: Service,
        field: NormalizedField,
        result: ServiceExecutionResult,
        state: State,
    ): List<NadelResultInstruction> {
        val parentNodes = JsonNodeExtractor.getNodesAt(result.data, field.listOfResultKeys.dropLast(1))
        val deepRenameInstruction = executionBlueprint.fieldInstructions.getForField(field)
            as NadelDeepRenameFieldInstruction

        return parentNodes.flatMap { parentNode ->
            @Suppress("UNCHECKED_CAST") // Ensure the result is a Map, return if null
            parentNode.value as JsonMap? ?: return@flatMap emptyList()

            val toCopy = JsonNodeExtractor.getNodesAt(parentNode, deepRenameInstruction.pathToSourceField)
                .emptyOrSingle() ?: return@flatMap emptyList()

            listOf(
                NadelResultInstruction.Copy(
                    subjectPath = toCopy.path,
                    destinationPath = parentNode.path + field.resultKey,
                ),
            ) + RemoveUnusedField.getInstructions(
                node = parentNode,
                originalSelection = field,
                pathToField = deepRenameInstruction.pathToSourceField,
            )
        }
    }
}

