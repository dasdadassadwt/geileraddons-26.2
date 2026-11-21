import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	id("net.fabricmc.fabric-loom")
	`maven-publish`
	id("org.jetbrains.kotlin.jvm") version "2.4.21"
}

repositories {
	maven {
		name = "Hypixel"
		url = uri("https://repo.hypixel.net/repository/Hypixel/")
	}
	maven {
		name = "AzureAaron"
		url = uri("https://maven.azureaaron.net/releases")
		content {
			includeGroup("net.azureaaron")
		}
	}
	maven {
		name = "Modrinth"
		url = uri("https://api.modrinth.com/maven")
	}
	// Add repositories to retrieve artifacts from in here.
	// You should only use this when depending on other mods because
	// Loom adds the essential maven repositories to download Minecraft and libraries from automatically.
	// See https://docs.gradle.org/current/userguide/declaring_repositories.html
	// for more information about repositories.
}

loom {
	splitEnvironmentSourceSets()

	mods {
		register("geileraddons") {
			sourceSet(sourceSets.main.get())
			sourceSet(sourceSets.getByName("client"))
		}
	}
}

dependencies {
	// To change the versions see the gradle.properties file
	minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
	implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")

	// Fabric API. This is technically optional, but you probably want it anyway.
	implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
    implementation("net.fabricmc:fabric-language-kotlin:${providers.gradleProperty("fabric_kotlin_version").get()}")
	compileOnly("io.github.llamalad7:mixinextras-common:0.5.4")
	annotationProcessor("io.github.llamalad7:mixinextras-common:0.5.4")
	implementation("net.hypixel:mod-api:${providers.gradleProperty("hypixel_mod_api_version").get()}")
	runtimeOnly("maven.modrinth:hypixel-mod-api:${providers.gradleProperty("hypixel_mod_api_fabric_version").get()}")
	// Skyblocker item value calculator (Apache-2.0), bundled into the mod jar.
	implementation("net.azureaaron:networth-calculator:1.0.5")
	include("net.azureaaron:networth-calculator:1.0.5")
	// Legacy 1.8 item NBT to current components (Apache-2.0), the same fixer Skyblocker uses for NEU items; bundled into the jar.
	implementation("net.azureaaron:legacy-item-dfu:1.0.4+26.2")
	include("net.azureaaron:legacy-item-dfu:1.0.4+26.2")
}

tasks.processResources {
	val version = version
	inputs.property("version", version)

	filesMatching("fabric.mod.json") {
		expand("version" to version)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 25
	options.encoding = "UTF-8"
}

kotlin {
	compilerOptions {
		jvmTarget = JvmTarget.JVM_25
		jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
	}
}

java {
	// Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
	// if it is present.
	// If you remove this line, sources will not be generated.
	withSourcesJar()

	sourceCompatibility = JavaVersion.VERSION_25
	targetCompatibility = JavaVersion.VERSION_25
}

sourceSets {
	named("test") {
		compileClasspath += sourceSets.getByName("client").output + sourceSets.getByName("client").compileClasspath
		runtimeClasspath += sourceSets.getByName("client").output + sourceSets.getByName("client").runtimeClasspath
	}
}

tasks.named<JavaCompile>("compileTestJava") {
	dependsOn(tasks.named("compileClientJava"), tasks.named("compileClientKotlin"))
}

tasks.register<JavaExec>("offlineChecks") {
	dependsOn(tasks.named("testClasses"))
	classpath = sourceSets.getByName("test").runtimeClasspath
	mainClass = "geiler.addons.client.module.impl.OfflineChecks"
	providers.gradleProperty("skipI4HelperChecks").orNull?.let {
		systemProperty("geileraddons.skipI4HelperChecks", it)
	}
}

tasks.register<JavaExec>("chatChannelsChecks") {
	dependsOn(tasks.named("testClasses"))
	classpath = sourceSets.getByName("test").runtimeClasspath
	mainClass = "geiler.addons.client.chat.ChatChannelsChecks"
}

tasks.register<JavaExec>("configProfileInstallerBoundsChecks") {
	dependsOn(tasks.named("testClasses"))
	classpath = sourceSets.getByName("test").runtimeClasspath
	mainClass = "geiler.addons.client.config.ConfigProfileInstallerBoundsChecks"
}

tasks.register<JavaExec>("featureAcceptanceChecks") {
	dependsOn(tasks.named("testClasses"))
	classpath = sourceSets.getByName("test").runtimeClasspath
	mainClass = "geiler.addons.client.FeatureAcceptanceChecks"
}

val legacyConfigFixtureSource = providers.gradleProperty("configFixtureGameDir")
val legacyConfigRoundTripDir = layout.buildDirectory.dir("parity-fixtures/legacy-26.1.2-roundtrip")
val prepareLegacyConfigRoundTripFixture = tasks.register<Sync>("prepareLegacyConfigRoundTripFixture") {
	// The parity check mutates its game-directory copy; restore the read-only source before every run.
	outputs.upToDateWhen { false }
	from(legacyConfigFixtureSource.map { file(it) })
	into(legacyConfigRoundTripDir)
	doFirst {
		val source = legacyConfigFixtureSource.orNull?.takeIf { it.isNotBlank() }
			?: throw GradleException("Pass -PconfigFixtureGameDir=<copied 26.1.2 game directory>.")
		if (!file(source).isDirectory) throw GradleException("The config fixture game directory does not exist.")
		val target = legacyConfigRoundTripDir.get().asFile.canonicalFile.toPath()
		val buildRoot = layout.buildDirectory.get().asFile.canonicalFile.toPath()
		if (!target.startsWith(buildRoot)) throw GradleException("The round-trip fixture must stay under the project build directory.")
	}
}

tasks.register<JavaExec>("legacyConfigProfileChecks") {
	dependsOn(tasks.named("testClasses"))
	dependsOn(prepareLegacyConfigRoundTripFixture)
	classpath = sourceSets.getByName("test").runtimeClasspath
	mainClass = "geiler.addons.client.config.LegacyConfigProfileRoundTripChecks"
	doFirst {
		val fixtureGameDir = legacyConfigFixtureSource.orNull
			?.takeIf { it.isNotBlank() }
			?: throw GradleException("Pass -PconfigFixtureGameDir=<copied 26.1.2 game directory>.")
		systemProperty("geileraddons.checkSourceGameDir", file(fixtureGameDir).absolutePath)
		systemProperty("geileraddons.checkGameDir", legacyConfigRoundTripDir.get().asFile.absolutePath)
	}
}

tasks.named<Test>("test") {
	failOnNoDiscoveredTests = false
}

tasks.named("check") {
	dependsOn(tasks.named("offlineChecks"))
}

tasks.jar {
	val projectName = project.name
	inputs.property("projectName", projectName)

	from("LICENSE") {
		rename { "${it}_$projectName" }
	}
}

// configure the maven publication
publishing {
	publications {
		register<MavenPublication>("mavenJava") {
			from(components["java"])
		}
	}

	// See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
	repositories {
		// Add repositories to publish to here.
		// Notice: This block does NOT have the same function as the block in the top level.
		// The repositories here will be used for publishing your artifact, not for
		// retrieving dependencies.
	}
}
