package graphql.nadel.enginekt.transform.hydration

import graphql.introspection.Introspection.TypeNameMetaFieldDef
import graphql.nadel.NextgenEngine
import graphql.nadel.Service
import graphql.nadel.ServiceExecutionResult
import graphql.nadel.enginekt.NadelExecutionContext
import graphql.nadel.enginekt.blueprint.NadelExecutionBlueprint
import graphql.nadel.enginekt.blueprint.NadelHydrationFieldInstruction
import graphql.nadel.enginekt.blueprint.getInstructionsOfTypeForField
import graphql.nadel.enginekt.plan.NadelExecutionPlan
import graphql.nadel.enginekt.transform.NadelTransform
import graphql.nadel.enginekt.transform.NadelTransformFieldResult
import graphql.nadel.enginekt.transform.NadelTransformUtil
import graphql.nadel.enginekt.transform.artificial.ArtificialFields
import graphql.nadel.enginekt.transform.hydration.NadelHydrationTransform.State
import graphql.nadel.enginekt.transform.query.NadelQueryTransformer
import graphql.nadel.enginekt.transform.result.NadelResultInstruction
import graphql.nadel.enginekt.transform.result.json.JsonNode
import graphql.nadel.enginekt.transform.result.json.JsonNodeExtractor
import graphql.nadel.enginekt.util.emptyOrSingle
import graphql.normalized.NormalizedField
import graphql.normalized.NormalizedField.newNormalizedField
import graphql.schema.FieldCoordinates
import graphql.schema.GraphQLSchema
import kotlinx.coroutines.internal.artificialFrame
import graphql.schema.FieldCoordinates.coordinates as makeFieldCoordinates

internal class NadelHydrationTransform(
    private val engine: NextgenEngine,
) : NadelTransform<State> {
    data class State(
        /**
         * The hydration instructions for the [field]. There can be multiple instructions
         * as a [NormalizedField] can have multiple [NormalizedField.objectTypeNames].
         *
         * The [Map.Entry.key] of [FieldCoordinates] denotes a specific object type and
         * its associated instruction.
         */
        val instructions: Map<FieldCoordinates, NadelHydrationFieldInstruction>,
        /**
         * The field in question for the transform, stored for quick access when
         * the [State] is passed around.
         */
        val field: NormalizedField,
        val artificialFields: ArtificialFields,
    )

    override suspend fun isApplicable(
        executionContext: NadelExecutionContext,
        overallSchema: GraphQLSchema,
        executionBlueprint: NadelExecutionBlueprint,
        services: Map<String, Service>,
        service: Service,
        field: NormalizedField,
    ): State? {
        val hydrationInstructions = executionBlueprint.fieldInstructions
            .getInstructionsOfTypeForField<NadelHydrationFieldInstruction>(field)

        return if (hydrationInstructions.isEmpty()) {
            null
        } else {
            State(
                hydrationInstructions,
                field,
                artificialFields = ArtificialFields("hydration_uuid"),
            )
        }
    }

    override suspend fun transformField(
        executionContext: NadelExecutionContext,
        transformer: NadelQueryTransformer.Continuation,
        service: Service,
        overallSchema: GraphQLSchema,
        executionPlan: NadelExecutionPlan,
        field: NormalizedField,
        state: State,
    ): NadelTransformFieldResult {
        return NadelTransformFieldResult(
            newField = null,
            artificialFields = state.instructions.flatMap { (fieldCoordinates, instruction) ->
                NadelHydrationFieldsBuilder.getArtificialFields(
                    service = service,
                    executionPlan = executionPlan,
                    artificialFields = state.artificialFields,
                    fieldCoordinates = fieldCoordinates,
                    instruction = instruction,
                )
            } + makeTypeNameField(state),
        )
    }

    /**
     * Read [State.instructions]
     *
     * In the case that there are multiple [FieldCoordinates] for a single [NormalizedField]
     * we need to know which type we are dealing with, so we use this to add a `__typename`
     * selection to determine the behavior on [getResultInstructions].
     *
     * This detail is omitted from most examples in this file for simplicity.
     */
    private fun makeTypeNameField(
        state: State,
    ): NormalizedField {
        // TODO: DRY this code, this is copied from deep rename
        return newNormalizedField()
            .alias(state.artificialFields.typeNameResultKey)
            .fieldName(TypeNameMetaFieldDef.name)
            .objectTypeNames(state.instructions.keys.map { it.typeName })
            .build()
    }

    override suspend fun getResultInstructions(
        executionContext: NadelExecutionContext,
        overallSchema: GraphQLSchema,
        executionPlan: NadelExecutionPlan,
        service: Service,
        field: NormalizedField,
        result: ServiceExecutionResult,
        state: State,
    ): List<NadelResultInstruction> {
        val parentNodes = JsonNodeExtractor.getNodesAt(
            data = result.data,
            queryResultKeyPath = field.listOfResultKeys.dropLast(1),
            flatten = true,
        )

        return parentNodes.flatMap {
            hydrate(
                parentNode = it,
                state = state,
                executionPlan = executionPlan,
                hydrationField = field,
                executionContext = executionContext,
            )
        }
    }

    private suspend fun hydrate(
        parentNode: JsonNode,
        state: State,
        executionPlan: NadelExecutionPlan,
        hydrationField: NormalizedField, // Field asking for hydration from the overall query
        executionContext: NadelExecutionContext,
    ): List<NadelResultInstruction> {
        val instruction = getMatchingInstruction(
            parentNode,
            state,
            executionPlan,
        )

        // Do nothing if there is no hydration instruction associated with this result
        instruction ?: return emptyList()

        val result = engine.executeHydration(
            service = instruction.sourceService,
            topLevelField = NadelHydrationFieldsBuilder.getQuery(
                instruction = instruction,
                artificialFields = state.artificialFields,
                hydrationField = hydrationField,
                parentNode = parentNode,
            ),
            pathToSourceField = instruction.pathToSourceField,
            executionContext = executionContext,
        )

        val data = JsonNodeExtractor.getNodesAt(
            data = result.data,
            queryResultKeyPath = instruction.pathToSourceField,
        ).emptyOrSingle()

        return listOf(
            NadelResultInstruction.Set(
                subjectPath = parentNode.path + hydrationField.resultKey,
                newValue = data?.value,
            ),
        )
    }

    /**
     * Note: this can be null if the type condition was not met
     */
    private fun getMatchingInstruction(
        parentNode: JsonNode,
        state: State,
        executionPlan: NadelExecutionPlan,
    ): NadelHydrationFieldInstruction? {
        val overallTypeName = NadelTransformUtil.getOverallTypename(
            executionPlan = executionPlan,
            artificialFields = state.artificialFields,
            node = parentNode,
        )
        return state.instructions[makeFieldCoordinates(overallTypeName, state.field.name)]
    }
}
