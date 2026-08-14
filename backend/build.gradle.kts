plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.3.21"
	// Builds the container image straight to Artifact Registry with no Dockerfile
	// and no local Docker daemon. See docs/deployment.md.
	id("com.google.cloud.tools.jib") version "3.4.5"
}

group = "com.acme"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")

	// Delivers the outbox when a mail host is configured. Without one, the
	// starter is inert: no JavaMailSender is auto-configured and the SMTP
	// transport does not register.
	implementation("org.springframework.boot:spring-boot-starter-mail")

	// Publishes the OpenAPI spec the frontend generates its typed client from.
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")
	// Renders the executed agreement PDF at signing time, and the auditor export.
	implementation("org.apache.pdfbox:pdfbox:3.0.8")

	// Criteria prefill. Kotlin uses Anthropic's Java SDK; the adapter is inert
	// until an API key is configured, so this dependency costs a jar and nothing
	// else in an environment that has not enabled the model.
	implementation("com.anthropic:anthropic-java:2.34.0")

	runtimeOnly("org.postgresql:postgresql")
	// Cloud SQL over a Unix socket, so no database password crosses a network
	// and Cloud Run needs no VPC connector. Inert everywhere else: the local and
	// test JDBC URLs never name the socket factory, so this is a jar and nothing
	// more until DATABASE_URL asks for it.
	runtimeOnly("com.google.cloud.sql:postgres-socket-factory:1.21.0")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	// Spring Boot 4's dependency management does not version Testcontainers, so
	// the BOM is imported explicitly rather than pinning each artifact.
	testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	// Testcontainers 2.x prefixes every module artifact with `testcontainers-`.
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// ---------------------------------------------------------------------------
// Container image
// ---------------------------------------------------------------------------
//
// Target and region come from Gradle properties so nothing about Acme's project
// is committed here:
//
//   ./gradlew jib -Pgcp.project=acme-onboarding -Pgcp.region=us-central1
//
// See docs/deployment.md for the full runbook.

val gcpProject = (findProperty("gcp.project") ?: System.getenv("GCP_PROJECT") ?: "").toString()
val gcpRegion = (findProperty("gcp.region") ?: System.getenv("GCP_REGION") ?: "us-central1").toString()
val gcpRepository = (findProperty("gcp.repository") ?: "acme").toString()

jib {
	from {
		image = "eclipse-temurin:21-jre"
		// Pinned, and load-bearing on an Apple Silicon machine: Jib would
		// otherwise build for the host's architecture and Cloud Run would refuse
		// the image with an error that does not mention architecture at all.
		platforms {
			platform {
				architecture = "amd64"
				os = "linux"
			}
		}
	}
	to {
		image = "$gcpRegion-docker.pkg.dev/$gcpProject/$gcpRepository/supplier-onboarding"
		tags = setOf("latest")
	}
	container {
		// Cloud Run supplies PORT and application.yml reads it; this is only the
		// declared default.
		ports = listOf("8080")
		// Cloud Run's smallest instance is 512 MiB. Without this the JVM sizes
		// its heap against the host's memory and is killed on the first upload.
		jvmFlags = listOf("-XX:MaxRAMPercentage=70.0", "-XX:+UseSerialGC")
		creationTime = "USE_CURRENT_TIMESTAMP"
	}
}
