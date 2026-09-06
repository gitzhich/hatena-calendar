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
	// テストは使い捨ての PostgreSQL コンテナに対して行う。
	// バージョンは Spring Boot の BOM が管理する
	testImplementation("org.testcontainers:testcontainers-postgresql")
	// LauncherSessionListener を実装するためコンパイル時にも要る
	testImplementation("org.junit.platform:junit-platform-launcher")
}

// ローカル実行でリポジトリ直下の .env を「環境変数として」渡す。
// 本番（Fly.io Secrets）と同じ経路になるため、注入方法がローカルと本番で分岐しない。
//
// spring.config.import で .env を読み込む案は採らなかった。
// あちらは bootRun だけでなく **テスト実行時にも** .env を読む。
// .env には INTERNAL_API_KEY などローカル用の値が入るため、
// 「キーが未設定のとき誰も通せない」ことを守る MissingApiKeyIT が
// 設定済みの状態で走ることになり、未設定の場面を一度も検証しなくなる。
// しかもテストは通ったままで、守っていないことに気づけない
// （ApiKeyFilter の未設定ガードを外す変異が、環境変数を与えると素通りした）。
//
// 空の値は渡さない。application.yml の ${VAR:default} は変数が未設定のときだけ
// デフォルトを使うため、空文字を環境に置くとデフォルトが効かなくなる。
// クォートを剥がしてから判定する。'' は空文字であって「クォート 2 文字」ではない。
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
	val dotenv = file("../.env")
	if (dotenv.exists()) {
		dotenv.readLines()
			.map { it.trim() }
			.filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
			.map { it.split("=", limit = 2) }
			.map { it[0] to unquoteEnvValue(it[1]) }
			.filter { it.second.isNotBlank() }
			.forEach { environment(it.first, it.second) }
	}
}

/**
 * .env の値を囲む対のクォートを 1 組だけ剥がす。
 *
 * **剥がさないと、同じ .env から読んだ値が読み手ごとに食い違う。**
 * bash（`set -a; . ./.env`）も `docker compose` も対のクォートを剥がすため、
 * ここだけ剥がさないと bootRun にだけクォート付きの値が渡る。
 *
 * 実際に `ADMIN_PASSWORD_HASH` で踏んだ。bcrypt ハッシュは `$2b$12$...` の形で、
 * 囲まないと `docker compose` が `$` を変数参照として展開しようとし、警告とともに
 * ハッシュの一部をコンソールに出す。それを避けてシングルクォートで囲むと、
 * 今度は bootRun にだけ `'$2b$12$...'` が渡り、Spring Security の
 * `\A\$2(a|y|b)?\$` に一致せず**管理ログインが通らなくなる**。
 *
 * **中身は解釈しない。** bash と `docker compose` はダブルクォートの中で変数を
 * 展開するが、ここではしない。`$` を含む値は必ずシングルクォートで囲むこと。
 * 書き方は docs/runbook-x-api-setup.md「引用符の扱い」。
 */
fun unquoteEnvValue(value: String): String {
	if (value.length < 2) return value
	val quote = value.first()
	if (quote != '\'' && quote != '"') return value
	return if (value.last() == quote) value.substring(1, value.length - 1) else value
}

tasks.withType<Test> {
	useJUnitPlatform()
	// 日付境界の検証のため、テスト JVM のタイムゾーンを差し替えられるようにする。
	// イベントの開催日・出演時刻は JST のローカル値で、どの TZ で動かしても
	// 結果が変わってはいけない（docs/data-model.md「タイムゾーンの扱い」 / ADR-0005）。
	//   ./gradlew test -PtestTimeZone=America/New_York
	providers.gradleProperty("testTimeZone").orNull?.let {
		jvmArgs("-Duser.timezone=$it")
	}
}
