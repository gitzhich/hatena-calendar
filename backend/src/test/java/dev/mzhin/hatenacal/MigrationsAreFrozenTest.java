package dev.mzhin.hatenacal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 適用済みのマイグレーションが変わっていないことを固定する。
 *
 * <p><b>Flyway のチェックサムはファイル全体から計算される。コメントも含む。</b>
 * 適用済みのファイルを 1 バイトでも変えると、起動時の検証が
 * {@code Migration checksum mismatch} で落ち、<b>アプリが上がらなくなる</b>。
 * 本番では次のデプロイが起動に失敗する形で現れる。
 *
 * <p>実際に踏んだ。設計文書への参照を章番号から見出しテキストへ一括置換した際に
 * V1〜V3 のコメントが書き換わり、ローカルのバックエンドが起動しなくなった。
 * V1 の冒頭には「このファイルを後から編集しない」と書いてあったが、
 * <b>人が読む注意書きは一括置換を止められない</b>。
 *
 * <p><b>このテストが落ちたときにすることは 1 つだけ。</b>
 * 既存ファイルへの変更を元に戻す（{@code git checkout} でよい）。
 * スキーマを変えたいなら<b>新しい番号のファイルを足す</b>。
 * ここのハッシュを書き換えて通すのは、検査を無効化するのと同じ。
 *
 * <p>新しいマイグレーションを足したときだけ、そのファイルのハッシュを
 * {@link #FROZEN} に追記する。
 *
 * <p>参照の書き方（docs/coding-guidelines.md「設計文書への参照」）も
 * これらのファイルには適用しない。{@code scripts/check-doc-refs.py} が
 * ディレクトリごと検査対象から外している。
 */
class MigrationsAreFrozenTest {

    /**
     * 適用済みマイグレーションの SHA-256。
     *
     * <p>Flyway が使うのは CRC32 だが、ここでは衝突を避けるため SHA-256 を使う。
     * 目的は「変わっていないこと」の検出であって、Flyway の値の再現ではない。
     */
    private static final Map<String, String> FROZEN = Map.of(
            "V1__init_schema.sql",
            "ad13654be78af630f8be4484b63e1ad1229be842bc250a8e352a00b36ab72a43",
            "V2__ingestion_run_truncated.sql",
            "3ad0e87a60eb3a674dabecec79460ff20a5af4565a8d0c3a342b9441493bafba",
            "V3__ingestion_run_cancelled.sql",
            "7f28f6c8271dddd2d5996f4ded533579449c34108a87f606b1ef264ba7680ee3");

    private static final String HOW_TO_FIX = """
            適用済みのマイグレーションが変更されている。
            Flyway のチェックサムが変わり、起動時の検証で落ちる（本番では次のデプロイが失敗する）。

            直し方: 既存ファイルへの変更を元に戻す。
                    git checkout -- backend/src/main/resources/db/migration/
            スキーマを変えたいなら、新しい番号のファイルを足す。

            FROZEN のハッシュを書き換えて通さないこと。検査を無効化するのと同じ。""";

    @Test
    @DisplayName("適用済みのマイグレーションが 1 バイトも変わっていない")
    void appliedMigrationsAreUnchanged() throws Exception {
        Map<String, String> actual = hashesOfMigrations();

        assertThat(actual)
                .as(HOW_TO_FIX)
                .containsExactlyInAnyOrderEntriesOf(new TreeMap<>(FROZEN));
    }

    @Test
    @DisplayName("新しいマイグレーションを足したらハッシュの追記を求める")
    void newMigrationsMustBeRecorded() throws Exception {
        assertThat(hashesOfMigrations().keySet())
                .as("""
                        マイグレーションが増減している。
                        足したなら、そのファイルの SHA-256 を FROZEN に追記する。

                          sha256sum backend/src/main/resources/db/migration/V*.sql

                        減らしたなら、適用済みの履歴と食い違う。Flyway は
                        「DB にあるがローカルに無い」を検出できるが、
                        削除そのものを止められるのはここだけ。""")
                .containsExactlyInAnyOrderElementsOf(FROZEN.keySet());
    }

    /**
     * クラスパス上のマイグレーションを読む。
     *
     * <p>ソースディレクトリではなく<b>クラスパスから読む</b>。Flyway が実際に読むのは
     * こちらで、ビルドの過程で内容が変わればそれも検出できる。
     */
    private static Map<String, String> hashesOfMigrations()
            throws IOException, URISyntaxException, NoSuchAlgorithmException {
        URL url = MigrationsAreFrozenTest.class.getResource("/db/migration");
        assertThat(url).as("クラスパスに /db/migration が無い").isNotNull();

        Map<String, String> hashes = new TreeMap<>();
        try (var files = Files.list(Path.of(url.toURI()))) {
            List<Path> sql = files.filter(p -> p.getFileName().toString().endsWith(".sql")).toList();
            for (Path p : sql) {
                hashes.put(p.getFileName().toString(), sha256(Files.readAllBytes(p)));
            }
        }
        return hashes;
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
