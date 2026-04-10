plugins {
    `java-library`
    `maven-publish`
}

group = "com.systemdesign"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val springBootVersion = "3.4.1"
val testcontainersVersion = "1.20.4"

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))

    api("org.springframework.boot:spring-boot-autoconfigure")
    api("org.springframework.data:spring-data-redis")
    api("io.lettuce:lettuce-core")

    compileOnly("org.springframework.boot:spring-boot-configuration-processor")
    api("jakarta.validation:jakarta.validation-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation(platform("org.testcontainers:testcontainers-bom:$testcontainersVersion"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("com.redis:testcontainers-redis:2.2.2")
}

tasks.jar {
    enabled = true
    archiveClassifier = ""
}

tasks.withType<Test> {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}
