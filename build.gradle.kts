import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
  java
  id("org.springframework.boot") version "4.1.1"
  id("com.diffplug.spotless") version "8.10.4"
}

group = "com.msvieira"
version = "1.0.0"
description = "Aviation API Wrapper - airport lookup by ICAO code"

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(25)
  }
}

repositories {
  mavenCentral()
}

val resilience4jVersion = "2.4.0"
val springdocVersion = "3.1.1"
val wiremockVersion = "3.13.2"

val mockitoAgent: Configuration by configurations.creating

dependencies {
  implementation(platform(SpringBootPlugin.BOM_COORDINATES))

  implementation("org.springframework.boot:spring-boot-starter-webmvc")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-restclient")

  implementation("org.springframework.boot:spring-boot-starter-aspectj")
  implementation("io.github.resilience4j:resilience4j-spring-boot4:$resilience4jVersion")

  implementation("org.springframework.boot:spring-boot-starter-cache")
  implementation("com.github.ben-manes.caffeine:caffeine")

  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-micrometer-metrics")
  implementation("io.micrometer:micrometer-registry-prometheus")

  implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")

  testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
  testImplementation("org.wiremock:wiremock-standalone:$wiremockVersion")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  mockitoAgent(platform(SpringBootPlugin.BOM_COORDINATES))
  mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
}

tasks.withType<Test> {
  useJUnitPlatform()
  jvmArgs("-javaagent:${mockitoAgent.asPath}", "-Xshare:off")
  testLogging {
    events("passed", "skipped", "failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}

tasks.withType<JavaCompile> {
  options.compilerArgs.add("-parameters")
}

spotless {
  java {
    target("src/**/*.java")
    palantirJavaFormat("2.102.0").formatJavadoc(true)
    importOrder("", "java|jakarta|javax", "\\#")
    removeUnusedImports()
    trimTrailingWhitespace()
    endWithNewline()
  }
  kotlinGradle {
    target("*.gradle.kts")
    ktlint("1.8.0")
  }
  format("misc") {
    target(
      "*.md",
      "docs/**/*.md",
      ".cursor/skills/**/*.md",
      ".gitignore",
      ".dockerignore",
      ".editorconfig",
      "Dockerfile",
      "src/**/*.yml",
      ".github/**/*.yml",
    )
    trimTrailingWhitespace()
    endWithNewline()
  }
}
