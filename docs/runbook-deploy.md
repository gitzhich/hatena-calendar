# 手順書 — 本番デプロイ

最終更新: 2026-09-12

関連文書: [architecture.md](architecture.md)「設定と環境変数」・[architecture.md](architecture.md)「デプロイ」・[architecture.md](architecture.md)「運用コストの試算」 /
[security.md](security.md) / [runbook-x-api-setup.md](runbook-x-api-setup.md) /
[ADR-0009](adr/0009-hosting.md)

---

## 0. この文書の位置づけ

**初回デプロイと、2 回目以降のデプロイの作業手順。** 構成そのものの設計は
[architecture.md](architecture.md) にあり、ここでは繰り返さない。

X API の準備（トークン取得・課金ガードレール）は
[runbook-x-api-setup.md](runbook-x-api-setup.md) が持つ。**先にそちらを済ませておく。**

### 0.1 コンソールの画面名について

Neon / Fly.io / Vercel の画面名は変わる。**入れる値と、なぜそれを入れるかを正とし、
ボタンの名前が違ったら読み替える。**

---

## 1. 前提

### 1.1 デプロイする順番

**Neon → Fly.io → Vercel。** 依存がこの向きだから。

```
Vercel ──BACKEND_BASE_URL──▶ Fly.io ──DATABASE_URL──▶ Neon
```

前を決めないと次の環境変数が埋まらない。**逆順で始めると、
仮の値を入れて後から直すことになり、直し忘れる。**

### 1.2 デプロイするコミット

**`main` の、CI が green のコミットをデプロイする。**

イメージのビルドはコンテナの中で行い、**テストは走らせない**
（結合テストが Testcontainers で PostgreSQL を起動するため、
ビルドコンテナの中では動かせない）。つまり `fly deploy` 自体は
「テストを通っていないコード」も通してしまう。そこは人が守る。

```bash
git checkout main && git pull --ff-only
gh run list --branch main --limit 1     # 直近の CI が success か
git status --short                       # 作業ツリーが汚れていないか
```

**`fly deploy` は作業ツリーの中身を送る。** コミットしていない変更もイメージに入る。

### 1.3 用意しておくもの

| | |
| --- | --- |
| `flyctl` | `curl -L https://fly.io/install.sh \| sh` |
| Fly.io アカウント | 支払い方法の登録が要る（常時起動のため無料枠だけでは動かない） |
| Neon アカウント | Free プラン |
| Vercel アカウント | Hobby プラン。GitHub 連携 |
| Docker | ローカルビルドに使う（動いていなければ Fly.io のリモートビルダーが使われる） |

---

## 2. Neon（DB）

### 2.1 Neon のプロジェクトを作る

- リージョンは **`AWS Asia Pacific 1 (Singapore)`**。
  **Neon に東京（日本）リージョンは無い**（[ADR-0018](adr/0018-regions.md)）。
  **作成後に変更できない。** 変えるには別プロジェクトを作ってデータを移行することになる
- PostgreSQL のバージョンは **17**。ローカルの `compose.yaml` と CI に合わせる
- Project name は `hatena-calendar`、Database name は **`hatenacal`**（ローカルと揃える）
- **Neon Auth は off。** 管理者認証は自前で持っている（[architecture.md](architecture.md)「管理者の認証フロー」）

### 2.2 autoscaling の下限を 0.25 CU に固定する

**これを忘れると無料枠の試算が崩れる。**

`Settings → Compute` で **最小と最大の両方を `0.25 CU`** にする。
上振れするとコンピュート時間に比例して CU-hours が増え、
[architecture.md](architecture.md)「運用コストの試算」の「月 31 CU-hours」という見積もりが成立しない。
**無料枠（100 CU-hours/月）を超えると翌請求月までコンピュートが停止し、
サイトが閲覧不能になる**（NFR-02）。

**suspend までの時間は既定（5 分）のままでよい。** [architecture.md](architecture.md)「運用コストの試算」の試算はこれが前提。

### 2.3 接続文字列を JDBC の形で取る

**接続文字列は画面上で編集できない。** 編集する代わりに、
**スニペットの種類を `Java` にする**と JDBC の形で出てくる。

1. **Connect** を押す（サイドバー上部、または「Connection string」カード）
2. スニペットの種類を **`Java`** にする
3. **プーリングが有効**であることを確かめる。ホスト名に `-pooler` が入っていればよい
   （[architecture.md](architecture.md)「デプロイ」）
4. コピーして `DATABASE_URL` に使う

出てくるのはこの形。

```
jdbc:postgresql://ep-xxx-pooler.ap-southeast-1.aws.neon.tech/hatenacal?user=myuser&password=mypassword&sslmode=require
```

**次の 5 つが揃っていることを確かめる。**

| 見るもの | 期待 |
| --- | --- |
| 先頭 | `jdbc:postgresql://` |
| ホスト | **`-pooler` が入っている** |
| 認証情報 | `user=` と `password=` の**両方**がクエリにある |
| SSL | `sslmode=require` がある |
| DB 名 | `hatenacal` |

**要点は 3 つ目。** pgjdbc は `user:password@host` の形を受け付けない
（[architecture.md](architecture.md)「接続情報は `DATABASE_URL` 1 本で渡す」）。`Java` 以外のスニペット
（`psql` など）はその形で出るため、**そのままでは使えない**。

**ポート番号は無くてよい。** pgjdbc は省略時に 5432 を使い、Neon も 5432 で待つ。

**この文字列はパスワードを含む。** 扱いは他のシークレットと同じにする。

### 2.4 接続の確認

手元から繋がることを先に確かめる。**Fly.io に載せてから間違いに気づくと、
原因がネットワークなのか文字列なのか切り分けられない。**

```bash
psql "postgresql://myuser:mypassword@ep-xxx-pooler.../hatenacal?sslmode=require" -c '\dt'
```

`psql` が無ければ次章の `fly deploy` 後のログで判断する（本書「うまくいかないとき」）。

---

## 3. Fly.io（バックエンド）

### 3.1 アプリを作る

`backend/fly.toml` は**コミット済み**。`fly launch` は使わない
（対話でこのファイルを上書きし、`auto_stop_machines` や
`min_machines_running` の指定が消える）。

```bash
cd backend
fly apps create hatenacal        # 名前が取られていたら fly.toml の app も直す
```

**リージョンは `fly.toml` の `primary_region = "sin"`（Singapore）。**
Neon と同居させて、バックエンド↔DB の往復を消す（[ADR-0018](adr/0018-regions.md)）。
`fly launch` の対話で東京を選ばない。

### 3.2 Secrets を入れる

**`fly.toml` に書かない**（[security.md](security.md) T-01）。
`fly secrets set KEY=VALUE` は値がシェル履歴に残るので、標準入力から流す。

必要な変数は [architecture.md](architecture.md)「設定と環境変数」が正本。

| 変数 | 値 |
| --- | --- |
| `DATABASE_URL` | 本書「接続文字列を JDBC の形で取る」で作った JDBC の文字列 |
| `X_BEARER_TOKEN` | [runbook-x-api-setup.md](runbook-x-api-setup.md)「Bearer Token を取得して置く」 |
| `X_SOURCE_USERNAME` | `xinxin_official` |
| `ADMIN_PASSWORD_HASH` | [architecture.md](architecture.md)「値の作り方」の手順で作る |
| `INTERNAL_API_KEY` | `openssl rand -base64 32` |
| `INTERNAL_ADMIN_API_KEY` | 同上。**上と別の値**（[ADR-0010](adr/0010-split-api-keys.md)） |
| `GOOGLE_MAPS_API_KEY` | 本書「Places API のキーを発行する」 |

**`GOOGLE_MAPS_API_KEY` は無くてもデプロイできる。** 未設定なら会場の `place_id`
解決だけがスキップされ、地図リンクは名前検索に落ちる
（[ADR-0022](adr/0022-venue-place-id-and-region.md)）。

**`DATABASE_USER` / `DATABASE_PASSWORD` は入れない。** 認証情報は
`DATABASE_URL` に含める（[architecture.md](architecture.md)「接続情報は `DATABASE_URL` 1 本で渡す」）。

```bash
cd backend               # fly.toml はここにある
fly secrets import       # KEY=VALUE を 1 行ずつ貼り、Ctrl-D
fly secrets list         # 名前とダイジェストだけが出る。値は表示されない
```

**新しいコードを入れるデプロイが控えているなら `--stage` を付ける。**
`fly secrets import` は既定でマシンを再起動する。まだそのシークレットを使う
コードが入っていない段階で再起動しても何も起きず、次のデプロイでもう一度
再起動することになる。`--stage` にしておけば**そのデプロイで一緒に適用され、
再起動が 1 回で済む**。

**ローカルの `.env` をそのまま流し込まない。** ローカル用の値が混ざる。

#### Places API のキーを発行する

会場に Google の `place_id` を紐づけるために使う
（[ADR-0022](adr/0022-venue-place-id-and-region.md) / [security.md](security.md) T-08）。

**`Places API` と `Places API (New)` はコンソール上で別のサービス。** 使うのは
後者（`places.googleapis.com`）で、**有効化も API 制限も割り当ても New 側に対して行う**。
レガシー側（`places-backend.googleapis.com`）を選んでも通らない。

1. **課金アカウントを紐づけた Google Cloud プロジェクトを用意する。**
   使う SKU 自体は無料だが、**Maps Platform は課金アカウントが無いと呼べない**。
   **国と通貨はあとから変更できない**
2. そのプロジェクトで **Places API (New) を有効にする**
3. API キーを作り、**API 制限を Places API (New) だけに絞る**
4. **割り当てで使わないメソッドを 0 にする**（本書「割り当てで上限を縛る」）
5. **予算アラートを作る**（本書「予算アラート」）

**「制限なし」で発行しない。** 制限をかけていなければ、漏れたキーで Geocoding や
Directions など**別の API を叩かれて課金される**。

**ブラウザに出さない。** Next.js 側には置かない。呼ぶのは Spring Boot だけで、
`NEXT_PUBLIC_` を付ける場面は無い。キーは**リクエストヘッダで送る**
（[security.md](security.md) T-08）。

##### API 制限だけでは課金を止められない

**API 制限はサービス単位であって SKU 単位ではない。**
Places API (New) の中にも有料 SKU がある。

| 同じ Places API (New) の中 | 価格 |
| --- | --- |
| Text Search Essentials (IDs Only) ← 本アプリが使う | 無料・**無料枠は無制限** |
| Text Search Pro | $32.00 / 1,000 |
| Text Search Enterprise | $35.00 / 1,000 |

**フィールドマスクを変えるだけで有料 SKU に移る。** つまり Places API (New) に
絞ってもなお、漏れたキーでの課金は起こりうる。**実際に上限を止められるのは
割り当てだけ**で、予算アラートは止めない。

##### 割り当てで上限を縛る

**Google Maps Platform > 割り当て**で `Places API (New)` を選び、`per day` の行を編集する。

| 名前（per day） | 既定 | 設定値 | 理由 |
| --- | --- | --- | --- |
| `SearchTextRequest` | 75,000 | **100** → 初期投入後は **10** | **唯一使う**（`places:searchText`） |
| `SearchMediaRequest` | 無制限 | **0** | 未使用。**無制限が一番危ない** |
| `SearchReviewPostsRequest` | 無制限 | **0** | 同上 |
| `AutocompletePlacesRequest` | 175,000 | **0** | 未使用 |
| `GetPhotoMediaRequest` | 175,000 | **0** | 未使用 |
| `GetPlaceRequest` | 125,000 | **0** | 未使用（[ADR-0022](adr/0022-venue-place-id-and-region.md)「未決定: 12 か月を超えた place_id をどう扱うか」） |
| `SearchNearbyRequest` | 75,000 | **0** | 未使用 |

**`per minute` は触らない。** 初期投入は 60 件を数十秒で流すため、絞ると自分の首を絞める。
総額を縛るのは日次の上限である。

**初期投入が済んだら `SearchTextRequest` を 10 まで下げる。** 新しい会場が現れるのは
数週間に 1 度で、定常運用ではほとんど呼ばない。

**上限に当たっても壊れない。** Google が 429 を返し、バックエンドは
`place_id_checked_at` を更新せずにその回を打ち切り、翌日また同じ会場から再開する
（[ADR-0022](adr/0022-venue-place-id-and-region.md)「暴走と無駄叩きを防ぐ」）。
低くしすぎても、戻せばそのまま続く。

**無料トライアル中は割り当てを増やせない。** 下げる方向は通る。

##### アプリケーションの制限をかけない理由

コンソールの選択肢のうち、ウェブサイト（リファラ）と Android / iOS は
**サーバ間通信では成立しない**。残るのは IP アドレスだが、
**Fly.io の送信元 IP は既定で固定されない**——NAT され、マシンが移ると予告なく変わる。
固定するには static egress IP が要り、**$3.60/月**かかる。

月額の見込みが $4〜7（[architecture.md](architecture.md)「運用コストの試算」）の
プロジェクトで**運用費が 5〜9 割増える**。割り当てのほうが安く、確実に効く。

##### 予算アラート

**請求先アカウント > 予算とアラート**。範囲は全プロジェクト・全サービス、
金額は少額（¥1,000 程度）、しきい値は既定の 50 / 90 / 100%。

**「プロモーション クレジット」のチェックを外す。** 予算が追跡するのは
**総額から、選んだクレジットを差し引いた額**である。含めたままだと
$300 の無料トライアルクレジットが実際の課金を覆い隠し、
**クレジットが尽きるまでアラートが鳴らない**。
「無料枠のクレジット」は含めたままでよい——正常な使い方で鳴らせないため
（警告を増やすと、本当に見るべき警告が埋もれる。NFR-09）。

**予算アラートは支出を止めない。** 支出を実際に止める spend cap は
Gemini API / Agent Platform / Cloud Run にしか使えず、**Maps Platform は対象外**。

デプロイ後、`GET /api/admin/venues?unresolved=true` で `placeId` が埋まっていくことを
確認する（[api.md](api.md)「会場の一覧と編集」）。解決は**起動の 5 分後に始まり、
以後 1 日 1 回**走る。**管理画面の会場一覧はまだ無い**ので、当面はログ
（`place_id の解決が完了: N 件試行、M 件解決`）と API で見る。

### 3.3 デプロイ

```bash
scripts/deploy-backend.sh
```

**`fly deploy` を直接叩かない**（[ADR-0019](adr/0019-deploy-triggers-and-records.md)）。
スクリプトが次を一続きで行う。

1. `main` にいて、作業ツリーが clean で、`origin/main` と一致するか検査
2. **そのコミットの CI が success か検査**（イメージのビルドはテストを走らせないため）
3. `fly deploy --local-only --ha=false --image-label <短縮 SHA>`
4. **マシンが 1 台か検査**（複数なら異常として終了）
5. 成功したときだけ `backend-deploy-<UTC>` タグを打って push

**`--ha=false` を落とすと予備機がもう 1 台立ち、取り込みが二重に走る**
（同じ範囲を並行して取得して X API の課金が倍になる）。初回デプロイで実際に起きた。
**`fly.toml` では止められず、このフラグが唯一の制御**なので、
コマンドを記憶に委ねない形にしてある。

`--local-only` は手元の Docker でビルドする指定。外すと Fly.io の
リモートビルダー（別課金のマシン）が起動する。

初回は JDK イメージの取得と依存のダウンロードで 5〜10 分かかる。

### 3.4 起動を確認する

```bash
fly status                  # 1 台だけが running か
fly logs                    # Flyway の適用と "Started HatenacalApplication"
curl -s https://hatenacal.fly.dev/actuator/health
```

`{"status":"UP"}` が返れば DB まで繋がっている。

**確かめること**

- [ ] **マシンが 1 台**。`fly status` の Machines が 1 行であること。
      2 行あれば `fly scale count 1 -a hatenacal` で落とす。
      **複数台だと取り込みが多重起動し、X API の課金が倍になる**
- [ ] `https://` で応答する（`http://` はリダイレクトされる）
- [ ] `/api/public/appearances?from=...&to=...` が**キー無しで 403**
      （デフォルト拒否。[security.md](security.md)「実装チェックリスト」）
- [ ] `/actuator/env` が **403**（health 以外を公開していない）

---

## 4. 本番 DB に情報源アカウントを入れる

**ローカルと本番は別の DB。** `source_account` の行は自動では作られず、
**無いと取り込みが毎回スキップされる**（ログに「情報源アカウントが DB に無い」）。

手順は [runbook-x-api-setup.md](runbook-x-api-setup.md)「`source_account` に投入する」。
本番の接続文字列に対して同じことを行う。

**`last_fetched_tweet_id` は NULL のまま入れる。** 値を入れるとそこから先だけを
取りに行く。初回は過去分の取り込み（バックフィル）が走る。

---

## 5. Vercel（フロントエンド）

### 5.1 Vercel のプロジェクトを作る

GitHub 連携で `main` を自動デプロイする（[architecture.md](architecture.md)「デプロイ」）。

- **Root Directory を `frontend` にする。** モノレポなのでリポジトリ直下ではない
- Framework Preset は Next.js（自動で判定される）

**関数のリージョンは `frontend/vercel.json` が `hnd1`（東京）に指定済み。**
Vercel の既定は **`iad1`（ワシントン DC）**で、**明示しないと日本からの
リクエストが毎回アメリカ経由になる**（[ADR-0018](adr/0018-regions.md)）。
コンソールで上書きしないこと。設定をリポジトリに置いているのは、
コンソール側だと設定漏れに気づけないため。

デプロイ後に `Settings → Functions → Function Regions` が
`Tokyo, Japan (hnd1)` になっていることを確認する。

### 5.2 環境変数

**Vercel が自動検出した変数をそのまま使わない。** インポート画面は
「Environment Variables 5 Detected」として候補を出すが、**これは
リポジトリ直下の `.env.example`（Spring Boot 用）を読んだもの**で、
Root Directory を `frontend` にしても変わらない。
`X_BEARER_TOKEN` など**バックエンドの変数が並ぶので、全部消してから
下の 4 つを手で足す**。

**とくに `X_BEARER_TOKEN` を Vercel に入れない。** 課金に直結するシークレットで、
Next.js は一切使わない。置き場所を増やすほど漏洩面が広がるだけ
（[security.md](security.md) T-01）。

[architecture.md](architecture.md)「設定と環境変数」が正本。**`NEXT_PUBLIC_` を付けない。**
付けるとブラウザに露出する。

| 変数 | 値 |
| --- | --- |
| `BACKEND_BASE_URL` | `https://hatenacal.fly.dev` |
| `BACKEND_API_KEY` | Fly.io の `INTERNAL_API_KEY` と**同じ値** |
| `BACKEND_ADMIN_API_KEY` | Fly.io の `INTERNAL_ADMIN_API_KEY` と**同じ値** |
| `SESSION_SECRET` | `openssl rand -base64 32` |
| `SITE_DISABLED` | 設定しない（停止したいときだけ `true`） |

**左右で変数名が違い、値は同じ**という対応を取り違えやすい。
[architecture.md](architecture.md)「値の作り方」の対応表を見ながら入れる。

**スコープは `Production` だけにする。** 既定の「Production and Preview」のままだと、
PR ごとに作られる**プレビュー環境（公開 URL を持つ）から本番のバックエンドと
管理画面に到達できる**。Preview を外してもビルドは通る
（`BACKEND_BASE_URL` は未設定なら既定値へ落ち、`SESSION_SECRET` は
`/admin` へのアクセス時にしか検証されない）。

### 5.3 プレビューデプロイは作らない

`frontend/vercel.json` が `main` 以外のブランチのデプロイを無効にしている。

```json
"git": { "deploymentEnabled": { "main": true, "**": false } }
```

**`main` を明示しているのは本番を必ずデプロイさせるため。** 複数の規則に
当てはまるブランチは、**1 つでも `true` があればデプロイされる**（Vercel の仕様）。

理由は 2 つ。

- **使わない。** 環境変数を Production だけにしているため、プレビューは
  バックエンドに繋がらず、確認の役に立たない
- **公開 URL を持つ。** private リポジトリの変更が、URL を知る誰にでも見える

**Hobby プランでは、そもそもプレビューがブロックされる。** private リポジトリでは
Vercel が**コミット作者を Hobby アカウント所有者と照合**し、GitHub ユーザーに
紐づかないコミットを拒否する（`Deployment was blocked`）。ローカルの
`git config user.email` が GitHub の検証済みメールでない場合がこれに当たる。
**本番は影響を受けない** — マージコミットは GitHub がアカウント名義で作るため。

### 5.4 公開ページの確認

- [ ] `/` が表示され、**カレンダーに出演情報が出る**（バックエンドまで繋がっている）
- [ ] `/2020/01` が **404**（範囲外。[ADR-0014](adr/0014-bounded-calendar-range.md)）
- [ ] フッタに非公式である旨と連絡先が出る（LR-01 / LR-05）
- [ ] `/admin` がログイン画面へ飛び、パスワードでログインできる
- [ ] ログイン後に出演情報を 1 件登録し、公開ページに反映される

```bash
# セキュリティヘッダ（NFR-03 / ADR-0016）
curl -sI https://<domain>/ | grep -iE 'content-security-policy|strict-transport|x-content-type|referrer'
```

---

### 5.5 本番の所在

| | URL |
| --- | --- |
| 公開サイト | `https://hatena-calendar.vercel.app` |
| バックエンド | `https://hatenacal.fly.dev`（ブラウザから直接叩かない） |

---

## 6. デプロイ後にしか確認できないこと

[requirements.md](requirements.md)「リリース判定基準（Definition of Done）」の DoD のうち、ここで初めて埋まるもの。

- [x] **NFR-01 応答時間** — 2026-09-03 に本番で実測。ISR 命中が 135ms 前後、
      キャッシュに無い月の初回が 144ms / 324ms（目標は p95 で 300ms / 3 秒）。
      **Neon のコールドスタートは踏めていない。** 当時は取り込みが 30 分ごとに
      DB を触るためと説明したが、**これは誤っていた**（30 分間隔・5 分 suspend なら
      時間の 8 割は停止しているはず）。実際はコンピュートがほぼ suspend していない
      —— 下の NFR-04 を見ること
- [ ] **NFR-04 CU-hours** — **2026-09-15 に実測し、目標を満たしていないことが分かった。**
      請求期間（9/3 開始）の 12.4 日で **70.34 / 100 CU-hours**。日あたり 5.70 で、
      **月換算 約 171**。[architecture.md](architecture.md)「運用コストの試算」の試算 約 31 に対して
      **5.4 倍**、無料枠 100 に対して **1.7 倍**である。
      0.25 CU を常時起動した場合が 6.00 CU-hours/日なので、**稼働率はほぼ 100%** ——
      **コンピュートが一度も suspend していなかった**。
      **原因は Fly が 30 秒ごとに叩く `/actuator/health` の `db` インジケータ**で、
      2026-09-15 に外した（[ADR-0023](adr/0023-health-check-without-db.md)）。
      **このままだと 2026-09-20 ごろに枠を使い切り、請求期間が変わる 10/3 まで
      サイトが閲覧不能になる**（NFR-02。Free は超過課金ではなくコンピュート停止）
- [x] **本番が HTTPS のみで動作する** — 2026-09-03 に確認（[security.md](security.md)「実装チェックリスト」）
- [x] **Neon の autoscaling が下限・上限とも 0.25 CU** — 2026-09-03 に画面で確認（本書「autoscaling の下限を 0.25 CU に固定する」）
- [x] **NFR-06 / NFR-08** — 2026-09-06 に実機の幅 360px で確認し、
      コントラスト比は 2026-09-12 に `scripts/check-contrast.py` として CI に載せた
      （[requirements.md](requirements.md)「リリース判定基準（Definition of Done）」に詳細）

**取り込みが動くことの確認は 30 分待つ。** `initial-delay` が 1 分、
`interval` が 30 分。管理画面の「取り込み状況」に成功が記録されれば動いている。

---

## 7. place_id を手で入れる

会場の `place_id` は 1 日 1 回の定期実行が自動で埋める
（[ADR-0022](adr/0022-venue-place-id-and-region.md)）。
**手で入れるのは、自動解決が別の場所を指してしまったときだけ。**
管理画面の会場編集でいったん空にし、正しい ID が分かっていればそこへ入れる
（[api.md](api.md)「会場の一覧と編集」）。

**`place_id` は通常の Google マップには出てこない。** 検索結果の URL にも
共有リンクにも含まれない（`?q=` や `data=` に見える文字列は `place_id` ではない）。
調べるには **Place ID Finder** を使う。

<https://developers.google.com/maps/documentation/places/web-service/place-id>

地図上で会場を選ぶと `ChIJ…` の形の ID が出る。**同名の施設が複数あるので住所まで見て選ぶ。**

**確信が持てなければ空のままでよい。** 空なら地図リンクが名前検索に落ちるだけで、
画面は壊れない。**誤った `place_id` はリンクが無いより悪く、ファンが違う場所へ向かう**
（[security.md](security.md) T-08）。

---

## 8. 止める・戻す

| やりたいこと | 手段 |
| --- | --- |
| **サイトを非公開にする**（LR-05） | Vercel の `SITE_DISABLED=true` → 再デプロイ。停止中も `/admin` は使える（[ADR-0013](adr/0013-site-kill-switch.md)） |
| 取り込みだけ止める | Fly.io に `X_INGESTION_ENABLED=false` → `fly deploy` |
| 前のバージョンへ戻す | `fly releases` で番号を見て `fly deploy --image <前のイメージ>`、または Vercel の Deployments から Promote |
| 全部止める | Fly.io のマシンを停止。**ただし Vercel の ISR キャッシュは配信され続ける**（[ADR-0013](adr/0013-site-kill-switch.md)） |

**バックエンドを止めても公開ページは消えない。** 止めたいときは `SITE_DISABLED`。

---

## 9. 2 回目以降

```bash
git checkout main && git pull --ff-only
scripts/deploy-backend.sh          # バックエンドを変えたときだけ
```

**フロントエンドは `main` への push で Vercel が自動デプロイする。**
ただし **`frontend/` に変更が無ければ飛ばす**（`vercel.json` の `ignoreCommand`）。
`docs/` や `backend/` だけの変更でデプロイは走らない。

**何が本番で動いているかは次で分かる。**

```bash
fly image show -a hatenacal                                  # バックエンド：動いているコミット
git tag -l 'backend-deploy-*'                                # バックエンド：デプロイ履歴
gh api repos/{owner}/{repo}/deployments?sha=$(git rev-parse main)   # フロント
```

フロントエンドは `main` への push で Vercel が自動デプロイする。手作業は要らない。

**マイグレーションを足したときは、起動時に Flyway が適用する。**
`fly logs` で適用を確認してから公開ページを触る。

---

## 10. うまくいかないとき

| 症状 | 見るところ |
| --- | --- |
| 起動時に `FATAL: password authentication failed` | `DATABASE_URL` の形式（本書「接続文字列を JDBC の形で取る」）。`user=` / `password=` をクエリで渡しているか。パスワードの記号をエンコードしたか |
| 起動時に `Connection refused` / タイムアウト | ホストが `-pooler` 付きか。`sslmode=require` が付いているか |
| Flyway が `Validate failed` | ローカルと本番でマイグレーションの履歴が食い違っている。**本番の `flyway_schema_history` を直接消さない**。原因を特定してから判断する |
| 公開ページが 500 | `BACKEND_BASE_URL` の綴り、`BACKEND_API_KEY` と `INTERNAL_API_KEY` の値が一致しているか |
| 公開ページが空だが 200 | バックエンドには繋がっているがデータが無い。本番 DB に出演情報が入っていない（本書「本番 DB に情報源アカウントを入れる」） |
| 管理画面だけ 403 | `BACKEND_ADMIN_API_KEY` と `INTERNAL_ADMIN_API_KEY` の不一致。**公開キーと取り違えていないか**（[ADR-0010](adr/0010-split-api-keys.md)） |
| 取り込みが動かない | `source_account` の行があるか（本書「本番 DB に情報源アカウントを入れる」）。`X_BEARER_TOKEN` が入っているか。`fly logs` に「スキップする」が出ていないか |
| ヘルスチェックが通らず入れ替わらない | `grace_period` より起動が遅い。`fly logs` で Flyway の適用時間を見る |

**課金が跳ねたときは `last_fetched_tweet_id` をまず疑う**
（[runbook-x-api-setup.md](runbook-x-api-setup.md)「運用中の確認」）。
