plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.mzhin"
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
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ローカル実行でリポジトリ直下の .env を「環境変数として」渡す。
// 本番（Fly.io Secrets）と同じ経路になるため、注入方法がローカルと本番で分岐しない。
//
// 空の値は渡さない。application.yml の ${VAR:default} は変数が未設定のときだけ
// デフォルトを使うため、空文字を環境に置くとデフォルトが効かなくなる
// （.env は .env.example の写しで、使っていないキーが空のまま残る）。
//
// spring.config.import で .env を読み込む案は採らなかった。
// 空の DATABASE_URL がデフォルトを潰し、テストの Spring コンテキストが起動しなくなる。
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
	val dotenv = file("../.env")
	if (dotenv.exists()) {
		dotenv.readLines()
			.map { it.trim() }
			.filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
			.map { it.split("=", limit = 2) }
			.filter { it[1].isNotBlank() }
			.forEach { environment(it[0], it[1]) }
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	// 日付境界の検証のため、テスト JVM のタイムゾーンを差し替えられるようにする。
	// イベントの開催日・出演時刻は JST のローカル値で、どの TZ で動かしても
	// 結果が変わってはいけない（docs/data-model.md 第 6 章 / ADR-0005）。
	//   ./gradlew test -PtestTimeZone=America/New_York
	providers.gradleProperty("testTimeZone").orNull?.let {
		jvmArgs("-Duser.timezone=$it")
	}
}
