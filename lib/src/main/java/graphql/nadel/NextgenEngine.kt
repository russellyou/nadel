package graphql.nadel

import graphql.ErrorType
import graphql.ExecutionInput
import graphql.ExecutionResult
import graphql.ExecutionResultImpl.newExecutionResult
import graphql.GraphQLError
import graphql.execution.instrumentation.InstrumentationState
import graphql.language.Document
import graphql.nadel.engine.NadelExecutionContext
import graphql.nadel.engine.blueprint.NadelDefaultIntrospectionRunner
import graphql.nadel.engine.blueprint.NadelExecutionBlueprintFactory
import graphql.nadel.engine.blueprint.NadelIntrospectionRunnerFactory
import graphql.nadel.engine.document.DocumentPredicates
import graphql.nadel.engine.plan.NadelExecutionPlan
import graphql.nadel.engine.plan.NadelExecutionPlanFactory
import graphql.nadel.engine.transform.NadelTransform
import graphql.nadel.engine.transform.query.DynamicServiceResolution
import graphql.nadel.engine.transform.query.NadelFieldToService
import graphql.nadel.engine.transform.query.NadelQueryPath
import graphql.nadel.engine.transform.query.NadelQueryTransformer
import graphql.nadel.engine.transform.result.NadelResultTransformer
import graphql.nadel.engine.util.beginExecute
import graphql.nadel.engine.util.copy
import graphql.nadel.engine.util.fold
import graphql.nadel.engine.util.getOperationKind
import graphql.nadel.engine.util.mergeResults
import graphql.nadel.engine.util.newExecutionErrorResult
import graphql.nadel.engine.util.newExecutionResult
import graphql.nadel.engine.util.newGraphQLError
import graphql.nadel.engine.util.newServiceExecutionResult
import graphql.nadel.engine.util.provide
import graphql.nadel.engine.util.singleOfType
import graphql.nadel.engine.util.strictAssociateBy
import graphql.nadel.engine.util.toBuilder
import graphql.nadel.hooks.ServiceExecutionHooks
import graphql.nadel.util.ErrorUtil
import graphql.nadel.util.LogKit.getLogger
import graphql.nadel.util.LogKit.getNotPrivacySafeLogger
import graphql.nadel.util.OperationNameUtil
import graphql.normalized.ExecutableNormalizedField
import graphql.normalized.ExecutableNormalizedOperationFactory.createExecutableNormalizedOperationWithRawVariables
import graphql.normalized.ExecutableNormalizedOperationToAstCompiler.compileToDocument
import graphql.normalized.VariablePredicate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture

class NextgenEngine @JvmOverloads constructor(
    nadel: Nadel,
    transforms: List<NadelTransform<out Any>> = emptyList(),
    introspectionRunnerFactory: NadelIntrospectionRunnerFactory = NadelIntrospectionRunnerFactory(::NadelDefaultIntrospectionRunner),
) : NadelExecutionEngine {
    private val logNotSafe = getNotPrivacySafeLogger<NextgenEngine>()
    private val log = getLogger<NextgenEngine>()

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val services: Map<String, Service> = nadel.services.strictAssociateBy { it.name }
    private val engineSchema = nadel.engineSchema
    private val querySchema = nadel.querySchema
    private val serviceExecutionHooks: ServiceExecutionHooks = nadel.serviceExecutionHooks
    private val overallExecutionBlueprint = NadelExecutionBlueprintFactory.create(
        engineSchema = nadel.engineSchema,
        services = nadel.services,
    )
    private val executionPlanner = NadelExecutionPlanFactory.create(
        executionBlueprint = overallExecutionBlueprint,
        engine = this,
        transforms = transforms,
    )
    private val resultTransformer = NadelResultTransformer(overallExecutionBlueprint)
    private val instrumentation = nadel.instrumentation
    private val dynamicServiceResolution = DynamicServiceResolution(
        engineSchema = engineSchema,
        serviceExecutionHooks = serviceExecutionHooks,
        services = nadel.services,
    )
    private val fieldToService = NadelFieldToService(
        querySchema = nadel.querySchema,
        overallExecutionBlueprint = overallExecutionBlueprint,
        introspectionRunnerFactory = introspectionRunnerFactory,
        dynamicServiceResolution = dynamicServiceResolution,
        services,
    )
    private val executionIdProvider = nadel.executionIdProvider

    override fun execute(
        executionInput: ExecutionInput,
        queryDocument: Document,
        instrumentationState: InstrumentationState?,
        nadelExecutionParams: NadelExecutionParams,
    ): CompletableFuture<ExecutionResult> {
        return coroutineScope.async {
            executeCoroutine(
                executionInput,
                queryDocument,
                instrumentationState,
                nadelExecutionParams.nadelExecutionHints,
            )
        }.asCompletableFuture()
    }

    override fun close() {
        // Closes the scope after letting in flight requests go through
        coroutineScope.launch {
            delay(60_000) // Wait a minute
            coroutineScope.cancel()
        }
    }

    private suspend fun executeCoroutine(
        executionInput: ExecutionInput,
        queryDocument: Document,
        instrumentationState: InstrumentationState?,
        executionHints: NadelExecutionHints,
    ): ExecutionResult {
        try {
            val query = createExecutableNormalizedOperationWithRawVariables(
                querySchema,
                queryDocument,
                executionInput.operationName,
                executionInput.variables,
            )

            val executionContext = NadelExecutionContext(executionInput, query, serviceExecutionHooks, executionHints)
            val beginExecuteContext = instrumentation.beginExecute(
                query,
                queryDocument,
                executionInput,
                engineSchema,
                instrumentationState,
            )

            val result: ExecutionResult = try {
                mergeResults(
                    coroutineScope {
                        fieldToService.getServicesForTopLevelFields(query)
                            .map { (field, service) ->
                                async {
                                    try {
                                        val resolvedService = fieldToService.resolveDynamicService(field, service)
                                        executeTopLevelField(field, resolvedService, executionContext)
                                    } catch (e: Throwable) {
                                        when (e) {
                                            is GraphQLError -> newExecutionErrorResult(field, error = e)
                                            else -> throw e
                                        }
                                    }
                                }
                            }
                    }.awaitAll()
                )
            } catch (e: Throwable) {
                beginExecuteContext?.onCompleted(null, e)
                throw e
            }

            beginExecuteContext?.onCompleted(result, null)
            return result
        } catch (e: Throwable) {
            when (e) {
                is GraphQLError -> return newExecutionResult(error = e)
                else -> throw e
            }
        }
    }

    private suspend fun executeTopLevelField(
        topLevelField: ExecutableNormalizedField,
        service: Service,
        executionContext: NadelExecutionContext,
    ): ExecutionResult {
        val executionPlan = executionPlanner.create(executionContext, services, service, topLevelField)
        val queryTransform = transformQuery(service, executionContext, executionPlan, topLevelField)
        val transformedQuery = queryTransform.result.single()
        val result: ServiceExecutionResult = executeService(service, transformedQuery, executionContext)
        val transformedResult: ServiceExecutionResult = when {
            topLevelField.name.startsWith("__") -> result
            else -> resultTransformer.transform(
                executionContext = executionContext,
                executionPlan = executionPlan,
                artificialFields = queryTransform.artificialFields,
                overallToUnderlyingFields = queryTransform.overallToUnderlyingFields,
                service = service,
                result = result,
            )
        }

        @Suppress("UNCHECKED_CAST")
        return newExecutionResult()
            .data(transformedResult.data)
            .errors(ErrorUtil.createGraphQLErrorsFromRawErrors(transformedResult.errors))
            .extensions(transformedResult.extensions as Map<Any, Any>)
            .build()
    }

    internal suspend fun executeHydration(
        service: Service,
        topLevelField: ExecutableNormalizedField,
        pathToActorField: NadelQueryPath,
        executionContext: NadelExecutionContext,
        serviceHydrationDetails: ServiceExecutionHydrationDetails,
    ): ServiceExecutionResult {
        val actorField = fold(initial = topLevelField, count = pathToActorField.segments.size - 1) {
            it.children.single()
        }

        val (transformResult, executionPlan) = transformHydrationQuery(
            service,
            executionContext,
            actorField,
            serviceHydrationDetails
        )

        // Get to the top level field again using .parent N times on the new actor field
        val transformedQuery: ExecutableNormalizedField = fold(
            initial = transformResult.result.single(),
            count = pathToActorField.segments.size - 1,
        ) {
            it.parent ?: error("No parent")
        }

        val result = executeService(
            service,
            transformedQuery,
            executionContext,
            serviceHydrationDetails,
        )

        return resultTransformer.transform(
            executionContext = executionContext,
            executionPlan = executionPlan,
            artificialFields = transformResult.artificialFields,
            overallToUnderlyingFields = transformResult.overallToUnderlyingFields,
            service = service,
            result = result,
        )
    }

    private suspend fun transformHydrationQuery(
        service: Service,
        executionContext: NadelExecutionContext,
        actorField: ExecutableNormalizedField,
        serviceHydrationDetails: ServiceExecutionHydrationDetails,
    ): Pair<NadelQueryTransformer.TransformResult, NadelExecutionPlan> {
        val executionPlan = executionPlanner.create(
            executionContext,
            services,
            service,
            rootField = actorField,
            serviceHydrationDetails,
        )

        val queryTransform = transformQuery(service, executionContext, executionPlan, actorField)

        // Fix parent of the actor field
        if (actorField.parent != null) {
            val fixedParent = actorField.parent.toBuilder().children(queryTransform.result).build()
            val queryTransformResult = queryTransform.result.single()
            queryTransformResult.replaceParent(fixedParent)
        }

        return Pair(queryTransform, executionPlan)
    }

    private suspend fun executeService(
        service: Service,
        transformedQuery: ExecutableNormalizedField,
        executionContext: NadelExecutionContext,
        executionHydrationDetails: ServiceExecutionHydrationDetails? = null,
    ): ServiceExecutionResult {
        val executionInput = executionContext.executionInput

        val jsonPredicate: VariablePredicate = getDocumentVariablePredicate(executionContext.hints, service)

        val compileResult = compileToDocument(
            service.underlyingSchema,
            transformedQuery.getOperationKind(engineSchema),
            getOperationName(service, executionContext),
            listOf(transformedQuery),
            jsonPredicate
        )

        val serviceExecParams = ServiceExecutionParameters(
            query = compileResult.document,
            context = executionInput.context,
            executionId = executionInput.executionId ?: executionIdProvider.provide(executionInput),
            cacheControl = executionInput.cacheControl,
            variables = compileResult.variables,
            operationDefinition = compileResult.document.definitions.singleOfType(),
            serviceContext = executionContext.getContextForService(service).await(),
            hydrationDetails = executionHydrationDetails,
            executableNormalizedField = transformedQuery,
        )

        val serviceExecResult = try {
            service.serviceExecution
                .execute(serviceExecParams)
                .asDeferred()
                .await()
        } catch (e: Exception) {
            val errorMessage = "An exception occurred invoking the service '${service.name}'"
            val errorMessageNotSafe = "$errorMessage: ${e.message}"
            val executionId = serviceExecParams.executionId.toString()
            logNotSafe.error("$errorMessageNotSafe. Execution ID '$executionId'", e)
            log.error("$errorMessage. Execution ID '$executionId'", e)

            newServiceExecutionResult(
                errors = mutableListOf(
                    newGraphQLError(
                        message = errorMessageNotSafe, // End user can receive not safe message
                        errorType = ErrorType.DataFetchingException,
                        extensions = mutableMapOf(
                            "executionId" to executionId,
                        ),
                    ).toSpecification(),
                ),
            )
        }

        return serviceExecResult.copy(
            data = serviceExecResult.data.let { data ->
                data?.takeIf { transformedQuery.resultKey in data }
                    ?: mutableMapOf(transformedQuery.resultKey to null)
            },
        )
    }

    private fun getDocumentVariablePredicate(hints: NadelExecutionHints, service: Service): VariablePredicate {
        return if (hints.allDocumentVariablesHint.invoke(service)) {
            DocumentPredicates.allVariablesPredicate
        } else {
            DocumentPredicates.jsonPredicate
        }
    }

    private fun getOperationName(service: Service, executionContext: NadelExecutionContext): String? {
        val originalOperationName = executionContext.query.operationName
        return if (executionContext.hints.legacyOperationNames(service)) {
            return OperationNameUtil.getLegacyOperationName(service.name, originalOperationName)
        } else {
            originalOperationName
        }
    }

    private suspend fun transformQuery(
        service: Service,
        executionContext: NadelExecutionContext,
        executionPlan: NadelExecutionPlan,
        field: ExecutableNormalizedField,
    ): NadelQueryTransformer.TransformResult {
        return NadelQueryTransformer.transformQuery(
            overallExecutionBlueprint,
            service,
            executionContext,
            executionPlan,
            field,
        )
    }

    companion object {
        @JvmStatic
        fun newNadel(): Nadel.Builder {
            return Nadel.Builder().engineFactory(::NextgenEngine)
        }
    }
}