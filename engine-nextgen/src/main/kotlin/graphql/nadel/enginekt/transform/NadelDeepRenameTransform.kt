package graphql.nadel.enginekt.transform

import graphql.nadel.Service
import graphql.nadel.ServiceExecutionResult
import graphql.nadel.enginekt.NadelExecutionContext
import graphql.nadel.enginekt.blueprint.NadelDeepRenameFieldInstruction
import graphql.nadel.enginekt.blueprint.NadelOverallExecutionBlueprint
import graphql.nadel.enginekt.blueprint.getInstructionsOfTypeForField
import graphql.nadel.enginekt.transform.artificial.AliasHelper
import graphql.nadel.enginekt.transform.query.NFUtil
import graphql.nadel.enginekt.transform.query.NadelQueryTransformer
import graphql.nadel.enginekt.transform.query.QueryPath
import graphql.nadel.enginekt.transform.result.NadelResultInstruction
import graphql.nadel.enginekt.transform.result.json.JsonNodeExtractor
import graphql.nadel.enginekt.util.emptyOrSingle
import graphql.nadel.enginekt.util.queryPath
import graphql.normalized.NormalizedField
import graphql.schema.FieldCoordinates

/**
 * A deep rename is a rename in where the field being "renamed" is not on the same level
 * as the deep rename declaration e.g.
 *
 * ```graphql
 * type Dog {
 *   name: String @renamed(from: ["details", "name"]) # This is the deep rename
 *   details: DogDetails # only in underlying schema
 * }
 *
 * type DogDetails {
 *   name: String
 * }
 * ```
 */
internal class NadelDeepRenameTransform : NadelTransform<NadelDeepRenameTransform.State> {
    data class State(
        /**
         * The instructions for the a [NormalizedField].
         *
         * Note that we can have multiple transform instructions for one [NormalizedField]
         * due to the multiple [NormalizedField.objectTypeNames] e.g.
         *
         * ```graphql
         * type Query {
         *   pets: [Pet]
         * }
         *
         * interface Pet {
         *   name: String
         * }
         *
         * type Dog implements Pet {
         *   name: String @renamed(from: ["collar", "name"])
         * }
         *
         * type Cat implements Pet {
         *   name: String @renamed(from: ["tag", "name"])
         * }
         * ```
         */
        val instructions: Map<FieldCoordinates, NadelDeepRenameFieldInstruction>,
        /**
         * See [AliasHelper]
         */
        val aliasHelper: AliasHelper,
        /**
         * Stored for easy access in other functions.
         */
        val field: NormalizedField,
    )

    /**
     * Determines whether a deep rename is applicable for the given [overallField].
     *
     * Creates a state with the deep rename instructions and the transform alias.
     */
    override suspend fun isApplicable(
        executionContext: NadelExecutionContext,
        executionBlueprint: NadelOverallExecutionBlueprint,
        services: Map<String, Service>,
        service: Service,
        overallField: NormalizedField,
    ): State? {
        val deepRenameInstructions = executionBlueprint.fieldInstructions
            .getInstructionsOfTypeForField<NadelDeepRenameFieldInstruction>(overallField)
        if (deepRenameInstructions.isEmpty()) {
            return null
        }

        return State(
            deepRenameInstructions,
            AliasHelper.forField(tag = "deep_rename", overallField),
            overallField,
        )
    }

    /**
     * Changes the overall [field] to the fields from the underlying service
     * required to perform the deep rename.
     *
     * e.g. per the pet examples
     *
     * ```graphql
     * type Query {
     *   pets: [Pet]
     * }
     *
     * interface Pet {
     *   name: String
     * }
     *
     * type Dog implements Pet {
     *   name: String @renamed(from: ["collar", "name"])
     * }
     *
     * type Cat implements Pet {
     *   name: String @renamed(from: ["tag", "name"])
     * }
     * ```
     *
     * then given a query
     *
     * ```graphql
     * {
     *   pets {
     *     ... on Dog { name }
     *     ... on Cat { name }
     *   }
     * }
     * ```
     *
     * this function changes it to
     *
     * ```graphql
     * {
     *   pets {
     *     ... on Dog {
     *       collar { name }
     *     }
     *     ... on Cat {
     *       tag { name }
     *     }
     *   }
     * }
     * ```
     */
    override suspend fun transformField(
        executionContext: NadelExecutionContext,
        transformer: NadelQueryTransformer.Continuation, // this has an underlying schema
        executionBlueprint: NadelOverallExecutionBlueprint,
        service: Service,
        field: NormalizedField,
        state: State,
    ): NadelTransformFieldResult {
        return NadelTransformFieldResult(
            newField = null,
            artificialFields = state.instructions.map { (coordinates, instruction) ->
                makeDeepField(
                    state,
                    transformer,
                    executionBlueprint,
                    service,
                    field,
                    coordinates,
                    deepRename = instruction,
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
        return NadelTransformUtil.makeTypeNameField(
            aliasHelper = state.aliasHelper,
            objectTypeNames = state.instructions.keys.map { it.typeName },
        )
    }

    /**
     * Read [transformField]
     *
     * This function actually creates the deep selection i.e. for
     *
     * ```graphql
     * name: String @renamed(from: ["collar", "name"])
     * ```
     *
     * this will actually create
     *
     * ```graphql
     * collar {
     *   name
     * }
     * ```
     */
    private suspend fun makeDeepField(
        state: State,
        transformer: NadelQueryTransformer.Continuation,
        executionBlueprint: NadelOverallExecutionBlueprint,
        service: Service,
        field: NormalizedField,
        fieldCoordinates: FieldCoordinates,
        deepRename: NadelDeepRenameFieldInstruction,
    ): NormalizedField {
        val underlyingTypeName = executionBlueprint.getUnderlyingTypeName(fieldCoordinates.typeName)
        val underlyingObjectType = service.underlyingSchema.getObjectType(underlyingTypeName)
            ?: error("No underlying object type")

        return state.aliasHelper.toArtificial(
            NFUtil.createField(
                schema = service.underlyingSchema,
                parentType = underlyingObjectType,
                queryPathToField = deepRename.queryPathToField,
                fieldArguments = emptyMap(),
                fieldChildren = transformer.transform(field.children),
            ),
        )
    }

    /**
     * This function moves the referenced field to the deep rename field.
     *
     * i.e. for
     *
     * ```graphql
     * type Dog {
     *   name: String @renamed(from: ["collar", "name"])
     * }
     * ```
     *
     * then for an object in the GraphQL response
     *
     * ```graphql
     * {
     *   "__typename": "Dog",
     *   "collar": { "name": "Luna" }
     * }
     * ```
     *
     * it will return the instructions
     *
     * ```
     * Copy(subjectPath=/collar/name, destinationPath=/)
     * Remove(subjectPath=/collar)
     * ```
     */
    override suspend fun getResultInstructions(
        executionContext: NadelExecutionContext,
        executionBlueprint: NadelOverallExecutionBlueprint,
        service: Service,
        overallField: NormalizedField,
        underlyingParentField: NormalizedField?, // Overall field
        result: ServiceExecutionResult,
        state: State,
    ): List<NadelResultInstruction> {
        val parentNodes = JsonNodeExtractor.getNodesAt(
            data = result.data,
            queryPath = underlyingParentField?.queryPath ?: QueryPath.root,
            flatten = true,
        )

        return parentNodes.mapNotNull instruction@{ parentNode ->
            val instruction = state.instructions.getInstructionForNode(
                executionBlueprint = executionBlueprint,
                service = service,
                aliasHelper = state.aliasHelper,
                parentNode = parentNode,
            ) ?: return@instruction null

            val queryPathForSourceField = state.aliasHelper.getQueryPath(instruction.queryPathToField)
            val sourceFieldNode = JsonNodeExtractor.getNodesAt(parentNode, queryPathForSourceField)
                .emptyOrSingle()

            val destinationPath = parentNode.resultPath + overallField.resultKey
            when (sourceFieldNode) {
                null -> NadelResultInstruction.Set(
                    subjectPath = destinationPath,
                    newValue = null,
                )
                else -> NadelResultInstruction.Copy(
                    subjectPath = sourceFieldNode.resultPath,
                    destinationPath = destinationPath,
                )
            }
        }
    }
}

