package graphql.nadel.enginekt.schema

import graphql.language.FieldDefinition
import graphql.nadel.OperationKind
import graphql.nadel.Service

data class GraphQLFieldInfo(
    val service: Service,
    val operationKind: OperationKind,
    val fieldDef: FieldDefinition,
)
