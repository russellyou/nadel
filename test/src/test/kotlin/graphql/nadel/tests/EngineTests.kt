package graphql.nadel.tests

import com.fasterxml.jackson.module.kotlin.readValue
import graphql.language.AstPrinter
import graphql.language.AstSorter
import graphql.nadel.Nadel
import graphql.nadel.NadelExecutionEngineFactory
import graphql.nadel.NadelExecutionInput.newNadelExecutionInput
import graphql.nadel.ServiceExecution
import graphql.nadel.ServiceExecutionFactory
import graphql.nadel.ServiceExecutionResult
import graphql.nadel.enginekt.util.JsonMap
import graphql.nadel.tests.util.getAncestorFile
import graphql.nadel.tests.util.requireIsDirectory
import graphql.nadel.tests.util.toSlug
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.TestContext
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.fail
import java.io.File
import java.util.concurrent.CompletableFuture

class EngineTests : FunSpec({
    val engineFactories = EngineTypeFactories()

    val fixturesDir = File(javaClass.classLoader.getResource("fixtures")!!.path)
        // Note: resources end up in nadel/test/build/resources/test/fixtures/
        .getAncestorFile("build").parentFile
        .requireIsDirectory()
        .let { moduleRootDir ->
            File(moduleRootDir, "src/test/resources/fixtures/").requireIsDirectory()
        }

    fixturesDir.walkTopDown()
        .asSequence()
        .filter {
            it.extension == "yml"
        }
        .onEach {
            println("Loading ${it.nameWithoutExtension}")
        }
        .map { file ->
            file
                .let(File::readText)
                .let<String, TestFixture>(yamlObjectMapper::readValue)
                .also { fixture ->
                    // Rename fixtures if they have the wrong name
                    val expectedName = fixture.name.toSlug()
                    if (file.nameWithoutExtension != expectedName) {
                        file.renameTo(File(file.parentFile, "$expectedName.yml"))
                    }
                }
        }
        .forEach { fixture ->
            engineFactories.all
                .filter { (engineType) ->
                    true
                    // fixture.name == "extending types from another service is possible with synthetic fields"
                }
                .filter { (engineType) ->
                    fixture.enabled.get(engineType = engineType) // && engineType == current
                }
                .forEach { (engineType, engineFactory) ->
                    // Run for tests that don't have nextgen calls
                    val execute: suspend TestContext.() -> Unit = {
                        execute(
                            fixture = fixture,
                            engineType = engineType,
                            engineFactory = engineFactory,
                        )
                    }
                    if (fixture.ignored.get(engineType = engineType)) {
                        xtest("$engineType ${fixture.name}", execute)
                    } else {
                        test("$engineType ${fixture.name}", execute)
                    }
                }

        }
})

private suspend fun execute(
    fixture: TestFixture,
    testHooks: EngineTestHook? = getTestHook(fixture),
    engineType: NadelEngineType,
    engineFactory: NadelExecutionEngineFactory,
) {
    val printLock = Any()
    fun printSyncLine(message: String): Unit = synchronized(printLock) {
        println(message)
    }

    fun printSyncLine(message: Any?): Unit = synchronized(printLock) {
        println(message)
    }

    fun printSyncLine(): Unit = synchronized(printLock) {
        println()
    }

    printSyncLine("Running ${fixture.name}")

    try {
        val nadel = Nadel.newNadel()
            .engineFactory(engineFactory)
            .dsl(fixture.overallSchema)
            .serviceExecutionFactory(object : ServiceExecutionFactory {
                private val astSorter = AstSorter()
                private val serviceCalls = fixture.serviceCalls[engineType].toMutableList()

                override fun getServiceExecution(serviceName: String): ServiceExecution {
                    return ServiceExecution { params ->
                        try {
                            val incomingQuery = params.query
                            val incomingQueryPrinted = AstPrinter.printAst(
                                astSorter.sort(incomingQuery),
                            )
                            printSyncLine(incomingQueryPrinted)

                            val response = synchronized(serviceCalls) {
                                val indexOfCall = serviceCalls
                                    .indexOfFirst {
                                        it.serviceName == serviceName
                                            && AstPrinter.printAst(it.request.document) == incomingQueryPrinted
                                        // && it.request.operationName == params.operationDefinition.name
                                    }
                                    .takeIf { it != -1 }

                                if (indexOfCall != null) {
                                    serviceCalls.removeAt(indexOfCall).response
                                } else {
                                    fail("Unable to match service call")
                                }
                            }

                            @Suppress("UNCHECKED_CAST")
                            CompletableFuture.completedFuture(
                                ServiceExecutionResult(
                                    response["data"] as JsonMap?,
                                    response["errors"] as List<JsonMap>?,
                                    response["extensions"] as JsonMap?,
                                ),
                            )
                        } catch (e: Throwable) {
                            fail("Unable to invoke service", e)
                        }
                    }
                }

                override fun getUnderlyingTypeDefinitions(serviceName: String): TypeDefinitionRegistry {
                    return SchemaParser().parse(fixture.underlyingSchema[serviceName])
                }
            })
            .let {
                testHooks?.makeNadel(engineType, it) ?: it
            }
            .build()

        val response = nadel.execute(
            newNadelExecutionInput()
                .query(fixture.query)
                .variables(fixture.variables)
                .artificialFieldsUUID("UUID")
                .let { builder ->
                    testHooks?.makeExecutionInput(engineType, builder) ?: builder
                }
                .build(),
        ).await()

        if (fixture.exception != null) {
            fail("Expected exception did not occur")
        }

        val expectedResponse = fixture.response
        if (expectedResponse != null) {
            // TODO: check extensions one day - right now they don't match up as dumped tests weren't fully E2E but tests are
            assertJsonObject(
                subject = response.toSpecification().let {
                    mapOf(
                        "data" to it["data"],
                        "errors" to (it["errors"] ?: emptyList<JsonMap>()),
                        // "extensions" to (it["extensions"] ?: emptyMap<String, Any?>()),
                    ).also {
                        printSyncLine("Overall response")
                        printSyncLine(it)
                        printSyncLine()
                    }
                },
                expected = expectedResponse.let {
                    mapOf(
                        "data" to it["data"],
                        "errors" to (it["errors"] ?: emptyList<JsonMap>()),
                        // "extensions" to (it["extensions"] ?: emptyMap<String, Any?>()),
                    ).also {
                        printSyncLine("Expecting response")
                        printSyncLine(it)
                        printSyncLine()
                    }
                },
            )
        }

        testHooks?.assertResult(engineType, response)
    } catch (e: Throwable) {
        if (fixture.exception?.message?.matches(e.message ?: "") == true) {
            return
        }
        if (testHooks?.assertFailure(engineType, e) == true) {
            return
        }
        fail("Unexpected error during engine execution", e)
    }
}
