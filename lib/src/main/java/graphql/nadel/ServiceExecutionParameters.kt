package graphql.nadel

import graphql.cachecontrol.CacheControl
import graphql.execution.ExecutionId
import graphql.language.Document
import graphql.language.OperationDefinition
import graphql.normalized.ExecutableNormalizedField

class ServiceExecutionParameters internal constructor(
    val query: Document,
    val context: Any?,
    val variables: Map<String, Any>,
    val operationDefinition: OperationDefinition,
    val executionId: ExecutionId,
    val cacheControl: CacheControl?,
    private val serviceContext: Any?,

    /**
     * @return details abut this service hydration or null if it's not a hydration call
     */
    val hydrationDetails: ServiceExecutionHydrationDetails?,
    val executableNormalizedField: ExecutableNormalizedField,
) {
    fun <T> getServiceContext(): T? {
        @Suppress("UNCHECKED_CAST") // Trust caller
        return serviceContext as T?
    }

    val isHydrationCall: Boolean
        get() = hydrationDetails != null
}
