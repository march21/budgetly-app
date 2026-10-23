plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.graalvm.buildtools.native") version "0.10.6"
}

group = "dev.budgetly"
version = "0.6.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.flywaydb:flyway-core")

    // PDF : extraction de texte avec positions (pas d'OCR)
    implementation("org.apache.pdfbox:pdfbox:3.0.5") {
        // spring-jcl fournit déjà l'API commons-logging.
        exclude(group = "commons-logging", module = "commons-logging")
    }

    // SQLite + SQLite3MultipleCiphers (chiffrement au repos, compatible SQLCipher v4)
    implementation("io.github.willena:sqlite-jdbc:3.47.0.0")

    // Argon2id pour dériver la clé de la base depuis le mot de passe maître (API légère, sans provider JCE)
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

// L'UI React buildée (ui/dist) est servie par le cœur : même binaire en mode desktop et en mode LAN.
tasks.processResources {
    from("../ui/dist") {
        into("static")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

graalvmNative {
    binaries {
        all {
            // Cours par ISIN : seule sortie réseau, en HTTPS.
            buildArgs.add("--enable-url-protocols=https")
            // PDFBox décode des chaînes en Windows-1252 (absent par défaut d'une image native).
            buildArgs.add("-H:+AddAllCharsets")
            // Enums d'annotations Kotlin touchées pendant la construction de l'image (sans état, sûres).
            buildArgs.add(
                "--initialize-at-build-time=kotlin.annotation.AnnotationRetention,kotlin.annotation.AnnotationTarget",
            )
        }
        named("main") {
            imageName.set("budgetly-core")
            // Paquets desktop : pile complète des erreurs de construction de l'image.
            if (project.hasProperty("native.diagnostics")) buildArgs.add("-H:+ReportExceptionStackTraces")
        }
        named("test") {
            // Image de tests : compilation rapide, sans optimisations (elle ne sert qu'à exécuter les tests).
            buildArgs.add("-Ob")
        }
    }
    // PDFBox initialise AWT (ColorModel → libawt via JNI). L'agent GraalVM trace ces accès
    // pendant les tests JVM ; `metadataCopy` les verse dans les métadonnées de l'image.
    agent {
        defaultMode = "standard"
        metadataCopy {
            inputTaskNames.add("test")
            outputDirectories.add("src/main/resources/META-INF/native-image/dev.budgetly/core")
            mergeWithExisting = true
        }
    }
}
