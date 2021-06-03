package graphql.nadel.enginekt.transform.hydration

import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.NullValue
import graphql.language.ObjectField
import graphql.language.ObjectValue
import graphql.language.StringValue
import graphql.language.Value
import graphql.nadel.enginekt.blueprint.NadelHydrationFieldInstruction
import graphql.nadel.enginekt.blueprint.hydration.NadelHydrationArgument
import graphql.nadel.enginekt.transform.hydration.NadelHydrationUtil.getSourceFieldDefinition
import graphql.nadel.enginekt.transform.result.json.JsonNode
import graphql.nadel.enginekt.transform.result.json.JsonNodeExtractor
import graphql.nadel.enginekt.util.AnyList
import graphql.nadel.enginekt.util.AnyMap
import graphql.nadel.enginekt.util.JsonMap
import graphql.nadel.enginekt.util.emptyOrSingle
import graphql.nadel.enginekt.util.mapFrom
import graphql.normalized.NormalizedField
import graphql.normalized.NormalizedInputValue
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLTypeUtil
import graphql.nadel.enginekt.blueprint.hydration.NadelHydrationArgumentValueSource as ValueSource

internal object NadelHydrationArgumentsBuilder {
    fun createSourceFieldArgs(
        instruction: NadelHydrationFieldInstruction,
        parentNode: JsonNode,
        hydrationField: NormalizedField,
        pathToResultKeys: (List<String>) -> List<String>,
    ): Map<String, NormalizedInputValue> {
        val sourceField = getSourceFieldDefinition(instruction)

        return mapFrom(
            instruction.sourceFieldArguments
                .map {
                    val argumentDef = sourceField.getArgument(it.name)
                    it.name to makeInputValue(it, argumentDef, parentNode, hydrationField, pathToResultKeys)
                }
        )
    }

    private fun makeInputValue(
        argument: NadelHydrationArgument,
        argumentDef: GraphQLArgument,
        parentNode: JsonNode,
        hydrationField: NormalizedField,
        pathToResultKeys: (List<String>) -> List<String>,
    ): NormalizedInputValue {
        return when (val valueSource = argument.valueSource) {
            is ValueSource.ArgumentValue -> hydrationField.getNormalizedArgument(valueSource.argumentName)
            is ValueSource.FieldValue -> makeInputValue(argumentDef) {
                getFieldValue(valueSource, parentNode, pathToResultKeys)
            }
        }
    }

    private fun getFieldValue(
        valueSource: ValueSource.FieldValue,
        parentNode: JsonNode,
        pathToResultKeys: (List<String>) -> List<String>,
    ): AnyNormalizedInputValueValue {
        val value = JsonNodeExtractor.getNodesAt(
            rootNode = parentNode,
            queryResultKeyPath = pathToResultKeys(valueSource.pathToField),
        ).emptyOrSingle()?.value

        return NormalizedInputValueValue.AstValue(
            valueToAstValue(value),
        )
    }

    internal fun valueToAstValue(value: Any?): Value<*> {
        return when (value) {
            is AnyList -> ArrayValue(
                value.map(this::valueToAstValue),
            )
            is AnyMap -> ObjectValue
                .newObjectValue()
                .objectFields(
                    value.let {
                        @Suppress("UNCHECKED_CAST")
                        it as JsonMap
                    }.map {
                        ObjectField(it.key, valueToAstValue(it.value))
                    },
                )
                .build()
            null -> NullValue.newNullValue().build()
            is Double ->
                FloatValue.newFloatValue()
                    .value(value.toBigDecimal())
                    .build()
            is Float -> FloatValue.newFloatValue()
                .value(value.toBigDecimal())
                .build()
            is Number -> IntValue.newIntValue()
                .value(value.toLong().toBigInteger())
                .build()
            is String -> StringValue.newStringValue()
                .value(value)
                .build()
            is Boolean -> BooleanValue.newBooleanValue()
                .value(value)
                .build()
            else -> error("Unknown value type '${value.javaClass.name}'")
        }
    }

    private inline fun makeInputValue(
        argumentDef: GraphQLArgument,
        valueFactory: () -> AnyNormalizedInputValueValue,
    ): NormalizedInputValue {
        return NormalizedInputValue(
            GraphQLTypeUtil.simplePrint(argumentDef.type), // Type name
            valueFactory().value,
        )
    }
}

internal typealias AnyNormalizedInputValueValue = NormalizedInputValueValue<*>

internal sealed class NormalizedInputValueValue<T> {
    abstract val value: T

    data class ListValue<T>(
        override val value: List<T>,
    ) : NormalizedInputValueValue<List<T>>()

    data class ObjectValue(
        override val value: JsonMap,
    ) : NormalizedInputValueValue<JsonMap>()

    @Suppress("FINITE_BOUNDS_VIOLATION_IN_JAVA") // idk
    data class AstValue<T : Value<*>>(
        override val value: T,
    ) : NormalizedInputValueValue<T>()
}
