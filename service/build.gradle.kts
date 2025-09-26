plugins {
  id("org.springframework.boot") version "3.5.6"
  id("io.spring.dependency-management") version "1.1.7"
  java
}

group = "com.ospicorp.timeseriesapi"
version = "0.0.1-SNAPSHOT"

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}

repositories {
  mavenCentral()
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("org.springframework.boot:spring-boot-starter-jdbc")
  implementation("org.postgresql:postgresql")

  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

  implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.13")
  implementation("io.github.resilience4j:resilience4j-spring-boot3:2.3.0")
  implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-csv")

  runtimeOnly("org.flywaydb:flyway-core")
  runtimeOnly("org.flywaydb:flyway-database-postgresql")

  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("io.swagger.parser.v3:swagger-parser:2.1.19")

  testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.3"))
  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:postgresql")
}

tasks.test {
  useJUnitPlatform()
  jvmArgs("-Dfile.encoding=UTF-8")
}
tasks.withType<JavaCompile>().configureEach {
  options.encoding = "UTF-8"
}



