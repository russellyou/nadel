plugins {
    kotlin("jvm")
    groovy
}

val graphqlJavaVersion = "0.0.0-2022-03-01T04-16-14-e973c9a1"
val slf4jVersion = "1.7.25"

dependencies {
    api("com.graphql-java:graphql-java:$graphqlJavaVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")

    testImplementation("org.antlr:antlr4-runtime:4.8")
    testImplementation("junit:junit:4.11")
    testImplementation("org.spockframework:spock-core:2.0-groovy-3.0")
    testImplementation("org.codehaus.groovy:groovy:3.0.8")
    testImplementation("org.codehaus.groovy:groovy-json:3.0.8")
    testImplementation("cglib:cglib-nodep:3.1")
    testImplementation("org.objenesis:objenesis:2.1")
    testImplementation("org.slf4j:slf4j-simple:$slf4jVersion")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.9.6")
    testImplementation("org.openjdk.jmh:jmh-core:1.21")
    testImplementation("org.openjdk.jmh:jmh-generator-annprocess:1.21")
    testImplementation("com.google.guava:guava:28.0-jre")
    testImplementation("com.graphql-java:graphql-java-extended-scalars:2021-06-29T01-19-32-8e19827")
}

// compileJava.source file("build/generated-src"), sourceSets.main.java
tasks.compileJava {
    source(file("build/generated-src"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}
