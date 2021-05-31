package graphql.nadel.enginekt.blueprint.hydration

data class NadelHydrationArgument(
    val name: String,
    val valueSource: NadelHydrationArgumentValueSource,
)

sealed class NadelHydrationArgumentValueSource {
    /**
     * Uses a value from a field in the same object (or its children) as input e.g.
     *
     * ```
     * type Issue {
     *   id: ID! # Value used as argument to issueId
     *   owner: User @hydrated(from: ["issueOwner"], args: [{name: "issueId" valueFromField: "id"}])
     * }
     * ```
     */
    data class FieldValue(val pathToField: List<String>) : NadelHydrationArgumentValueSource()

    /**
     * Uses a value from a field in the same object (or its children) as input e.g.
     *
     * ```
     * type Issue {
     *   secret(password: String): JSON @hydrated(from: ["issueSecret"], args: [{name: "password", valueFromArg: "password"}])
     * }
     * ```
     */
    data class ArgumentValue(val argumentName: String) : NadelHydrationArgumentValueSource()
}