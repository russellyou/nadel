package graphql.nadel.enginekt.transform.query

import graphql.nadel.OperationKind
import graphql.nadel.Service
import graphql.nadel.enginekt.blueprint.NadelOverallExecutionBlueprint
import graphql.nadel.enginekt.schema.NadelFieldInfos
import graphql.nadel.enginekt.util.copyWithChildren
import graphql.nadel.enginekt.util.makeFieldCoordinates
import graphql.normalized.NormalizedField
import graphql.normalized.NormalizedQuery

class SplitFieldsByUnderlyingService(
    private val blueprint: NadelOverallExecutionBlueprint,
    private val fieldInfos: NadelFieldInfos,
    private val operationKind: OperationKind,
) {
    fun getFieldsToExecute(query: NormalizedQuery): List<FieldExecution> {
        return query.topLevelFields
            .flatMap { topLevelField ->
                if (topLevelField.getOneFieldDefinition(blueprint.schema).getDirective("namespaced") != null) {
                    val fieldsByService = topLevelField.children
                        .groupBy {
                            fieldInfos.getFieldInfo(
                                operationKind,
                                makeFieldCoordinates(
                                    it.getOneObjectType(blueprint.schema),
                                    it.getOneFieldDefinition(blueprint.schema)
                                )
                            )!!
                        }

                    fieldsByService.map {
                        FieldExecution(topLevelField.copyWithChildren(it.value), it.key)
                    }
                } else {
                    listOf(
                        FieldExecution(
                            field = topLevelField,
                            service = fieldInfos.getFieldInfo(
                                operationKind, makeFieldCoordinates(
                                    topLevelField.getOneObjectType(blueprint.schema),
                                    topLevelField.getOneFieldDefinition(blueprint.schema)
                                )
                            )!!
                        )
                    )
                }
            }
    }
}

data class FieldExecution(
    val field: NormalizedField,
    val service: Service,
)
