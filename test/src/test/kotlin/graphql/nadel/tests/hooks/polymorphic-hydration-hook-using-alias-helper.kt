package graphql.nadel.tests.hooks

import graphql.nadel.Nadel
import graphql.nadel.enginekt.NadelEngineExecutionHooks
import graphql.nadel.enginekt.blueprint.NadelGenericHydrationInstruction
import graphql.nadel.enginekt.blueprint.hydration.NadelHydrationActorInputDef
import graphql.nadel.enginekt.transform.artificial.NadelAliasHelper
import graphql.nadel.enginekt.transform.result.json.JsonNode
import graphql.nadel.enginekt.util.JsonMap
import graphql.nadel.tests.EngineTestHook
import graphql.nadel.tests.NadelEngineType
import graphql.nadel.tests.UseHook

private class PolymorphicHydrationHookUsingAliasHelper : NadelEngineExecutionHooks {

    override fun <T : NadelGenericHydrationInstruction> getHydrationInstruction(
        instructions: List<T>,
        parentNode: JsonNode,
        aliasHelper: NadelAliasHelper
    ): T? {
        return instructions.firstOrNull {
            val (_, _, valueSource) = it.actorInputValueDefs.single()
            if (valueSource !is NadelHydrationActorInputDef.ValueSource.FieldResultValue) {
                return@firstOrNull false
            }
            val actorFieldName = valueSource.fieldDefinition.name
            val targetFieldName = aliasHelper.getResultKey(actorFieldName)
            val hydrationArgumentValue = (parentNode.value as JsonMap)[targetFieldName]
            hydrationInstructionMatchesArgumentValue(it, hydrationArgumentValue as String)
        }
    }

    private fun <T : NadelGenericHydrationInstruction> hydrationInstructionMatchesArgumentValue(
        instruction: T,
        hydrationArgumentValue: String
    ): Boolean {
        return instruction.actorService.name == "pets" && hydrationArgumentValue.startsWith("pet", ignoreCase = true) ||
            instruction.actorService.name == "people" && hydrationArgumentValue.startsWith("human", ignoreCase = true)
    }
}

@UseHook
class `batch-polymorphic-hydration` : EngineTestHook {
    override fun makeNadel(engineType: NadelEngineType, builder: Nadel.Builder): Nadel.Builder {
        return builder.serviceExecutionHooks(PolymorphicHydrationHookUsingAliasHelper())
    }
}