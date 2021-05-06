package graphql.nadel.enginekt.schema

import graphql.language.FieldDefinition
import graphql.language.ObjectTypeDefinition
import graphql.nadel.OperationKind
import graphql.nadel.Service
import graphql.nadel.enginekt.util.getOperationType
import graphql.nadel.enginekt.util.mapFrom
import graphql.schema.GraphQLFieldDefinition

data class NadelFieldInfos(
    private val queryTopLevelFields: Map<String, NadelFieldInfo>,
    private val mutationTopLevelFields: Map<String, NadelFieldInfo>,
    private val subscriptionTopLevelFields: Map<String, NadelFieldInfo>,
) {
    fun getFieldInfo(operationKind: OperationKind, topLevelFieldName: String): NadelFieldInfo? {
        return when (operationKind) {
            OperationKind.QUERY -> queryTopLevelFields[topLevelFieldName]
            OperationKind.MUTATION -> mutationTopLevelFields[topLevelFieldName]
            OperationKind.SUBSCRIPTION -> subscriptionTopLevelFields[topLevelFieldName]
        }
    }

    companion object {
        fun create(services: List<Service>): NadelFieldInfos {
            return NadelFieldInfos(
                getInfosFromServices(services, OperationKind.QUERY),
                getInfosFromServices(services, OperationKind.MUTATION),
                getInfosFromServices(services, OperationKind.SUBSCRIPTION),
            )
        }

        private fun getInfosFromServices(
            services: List<Service>,
            operationKind: OperationKind,
        ): Map<String, NadelFieldInfo> {
            return mapFrom(
                services.flatMap forService@{ service ->
                    val underlyingOperationType = service.underlyingSchema.getOperationType(operationKind)
                        ?: return@forService emptyList()

                    // TODO: determine what to do for underlying fields - those will conflict as Map keys
                    underlyingOperationType.fieldDefinitions.mapNotNull forField@{ underlyingField ->
                        val overallField = getOverallField(service, operationKind, underlyingField)
                            ?: return@forField null
                        overallField.name to NadelFieldInfo(service, operationKind, overallField)
                    }
                }
            )
        }

        private fun getOverallField(
            service: Service,
            operationKind: OperationKind,
            underlyingField: GraphQLFieldDefinition,
        ): FieldDefinition? {
            val overallOperationTypes: MutableList<ObjectTypeDefinition> = when (operationKind) {
                OperationKind.QUERY -> service.definitionRegistry.queryType
                OperationKind.MUTATION -> service.definitionRegistry.mutationType
                OperationKind.SUBSCRIPTION -> service.definitionRegistry.subscriptionType
            }

            return overallOperationTypes
                .asSequence()
                .flatMap(ObjectTypeDefinition::getFieldDefinitions)
                .firstOrNull { fieldDef ->
                    fieldDef.name == underlyingField.name
                }
        }
    }
}
