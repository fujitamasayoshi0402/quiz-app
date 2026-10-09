// 負荷試験（DEV-112）。quiz-service の API を、利用者がクイズを解くときの流れで叩く。
// 手順と結果は docs/load-test.md にある。
//
//   k6 run -e PROFILE=smoke tests/load/load-test.js   # スクリプトが通るかを 1 回だけ流して確かめる
//   k6 run -e PROFILE=load  tests/load/load-test.js   # 利用者を段階的に増やす
//
// 環境変数（値は Terraform の出力にある。docs/load-test.md を参照）
//   BASE_URL            API のオリジン（https://api.dev.<ドメイン>）。web の proxy は通さない
//   AUTH_CLIENT_ID      web のクライアントの ID
//   AUTH_CLIENT_SECRET  web のクライアントのシークレット
//   LOAD_USER_PASSWORD  負荷試験の利用者の共通のパスワード
//   PROFILE             smoke / load / stress（既定は smoke）
//   THINK               回答と回答の間に置く秒数（既定は PROFILE による）
import http from 'k6/http';
import crypto from 'k6/crypto';
import exec from 'k6/execution';
import { check, fail, sleep } from 'k6';

const BASE_URL = required('BASE_URL').replace(/\/$/, '');
const CLIENT_ID = required('AUTH_CLIENT_ID');
const CLIENT_SECRET = required('AUTH_CLIENT_SECRET');
const PASSWORD = required('LOAD_USER_PASSWORD');
const REGION = __ENV.COGNITO_REGION || 'ap-northeast-1';
const TENANT = __ENV.TENANT || 'load';
const PROFILE = __ENV.PROFILE || 'smoke';

// 利用者はシード（R__load_data.sql）と Terraform（envs/dev の load_user_emails）が用意している。
// 利用者ごとに流量の上限（5 件/秒、バースト 30）があるため、VU ごとに別の利用者を割り当て、同じ利用者を同時に使わない。
// VU の番号はシナリオをまたいで 1 から振られる。全シナリオの VU の数の合計を、一般ユーザーの数（50）以下にする
const MEMBERS = Array.from({ length: 50 }, (_, i) => `load-${String(i + 1).padStart(2, '0')}@example.com`);
const ADMIN = 'load-admin@example.com';

// 回答 1 回あたりの考える時間。利用者ごとの上限（5 件/秒）を超えないよう、0.2 秒より短くしない
const THINK = Number(__ENV.THINK || { smoke: 0, load: 1, stress: 0.3 }[PROFILE]);
const QUIZZES_PER_ATTEMPT = 10;

const PROFILES = {
  // 1 回ずつ流して、スクリプトと環境がつながっているかを確かめる
  smoke: {
    play: { executor: 'shared-iterations', vus: 1, iterations: 1 },
    browse: { executor: 'shared-iterations', vus: 1, iterations: 1 },
    admin: { executor: 'shared-iterations', vus: 1, iterations: 1 },
  },
  // 実際の利用に近い間隔で、利用者を 10 人ずつ増やす。各段 3 分
  load: {
    play: steps([10, 20, 30, 40], '3m'),
    browse: steps([2, 4, 6, 8], '3m'),
    admin: { executor: 'constant-vus', vus: 1, duration: '14m' },
  },
  // 間隔を詰めて、どこで詰まるかを探す。各段 2 分
  stress: {
    play: steps([10, 20, 30, 40], '2m'),
    browse: steps([2, 4, 6, 8], '2m'),
    admin: { executor: 'constant-vus', vus: 1, duration: '10m' },
  },
};

if (!PROFILES[PROFILE]) {
  throw new Error(`PROFILE は ${Object.keys(PROFILES).join(' / ')} のどれかにしてください: ${PROFILE}`);
}

export const options = {
  scenarios: Object.fromEntries(
    Object.entries(PROFILES[PROFILE]).map(([name, scenario]) => [name, { ...scenario, exec: name }]),
  ),
  // 鳴らすための値ではなく、結果に出すための値。シナリオごとの p95 とエラーの率が、終わりの要約に並ぶ
  thresholds: {
    'http_req_duration{scenario:play}': ['p(95)<1000'],
    'http_req_duration{scenario:browse}': ['p(95)<1000'],
    'http_req_duration{scenario:admin}': ['p(95)<1000'],
    'http_req_failed{scenario:play}': ['rate<0.01'],
    'http_req_failed{scenario:browse}': ['rate<0.01'],
    'http_req_failed{scenario:admin}': ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  // トークンの取得と、利用者の結び付け（最初の要求）は、計測から外す。Aurora が止まっていれば、ここで起こす
  setupTimeout: '3m',
};

// ---- 準備 ----

export function setup() {
  const tokens = {};
  for (const email of [...MEMBERS, ADMIN]) {
    tokens[email] = signIn(email);
    // 最初の要求で、アプリの利用者がメールアドレスで結び付く（Cognito に GetUser を問い合わせる）。試験の中で起こさない
    const res = http.get(`${BASE_URL}/api/me/tenants`, {
      headers: auth(tokens[email]),
      tags: { name: 'setup' },
      timeout: '60s',
    });
    if (res.status !== 200) fail(`${email} の所属を取れませんでした: ${res.status} ${res.body}`);
  }
  return { tokens };
}

/** 画面と同じ USER_AUTH で、パスワードを指定してサインインする（.github/scripts/smoke-token.sh と同じ） */
function signIn(email) {
  const res = http.post(
    `https://cognito-idp.${REGION}.amazonaws.com/`,
    JSON.stringify({
      ClientId: CLIENT_ID,
      AuthFlow: 'USER_AUTH',
      AuthParameters: {
        USERNAME: email,
        PASSWORD,
        SECRET_HASH: crypto.hmac('sha256', CLIENT_SECRET, email + CLIENT_ID, 'base64'),
        PREFERRED_CHALLENGE: 'PASSWORD',
      },
    }),
    {
      headers: {
        'Content-Type': 'application/x-amz-json-1.1',
        'X-Amz-Target': 'AWSCognitoIdentityProviderService.InitiateAuth',
      },
      tags: { name: 'setup' },
    },
  );
  if (res.status !== 200) fail(`${email} でサインインできませんでした: ${res.status} ${res.body}`);
  return res.json('AuthenticationResult.AccessToken');
}

// ---- シナリオ ----

/** 出題 → 回答 → 結果。カテゴリと難易度を選んで 10 問を解く */
export function play(data) {
  const token = data.tokens[member()];
  const categories = get(token, 'play', '/play/categories', 'GET /play/categories');
  if (!categories) return;
  const category = pick(categories);
  const difficulty = pick(category.difficulties);

  const attempt = send(token, 'POST', '/play/attempts', 'POST /play/attempts', 201, {
    categoryId: category.id,
    difficultyId: difficulty.id,
    scope: 'unanswered',
    limit: QUIZZES_PER_ATTEMPT,
    discardInProgress: true,
  });
  if (!attempt) return;

  for (const quiz of attempt.quizzes) {
    sleep(THINK);
    send(token, 'POST', `/play/attempts/${attempt.id}/answers`, 'POST /play/attempts/{id}/answers', 200, {
      quizId: quiz.id,
      choiceId: pick(quiz.choices).id,
    });
  }
  send(token, 'POST', `/play/attempts/${attempt.id}/complete`, 'POST /play/attempts/{id}/complete', 200);
  sleep(THINK);
}

/** 履歴とランキングを見る。集計の問い合わせ */
export function browse(data) {
  const token = data.tokens[member()];
  get(token, 'play', '/play/history/attempts', 'GET /play/history/attempts');
  sleep(THINK);
  get(token, 'play', '/play/history/categories', 'GET /play/history/categories');
  sleep(THINK);
  get(token, 'play', '/play/ranking', 'GET /play/ranking');
  sleep(THINK * 3);
}

/** 管理者がクイズの一覧を見る */
export function admin(data) {
  const token = data.tokens[ADMIN];
  const categories = get(token, 'admin', '/admin/categories', 'GET /admin/categories');
  if (!categories) return;
  sleep(Math.max(THINK, 1));
  get(token, 'admin', `/admin/quizzes?categoryId=${pick(categories).id}`, 'GET /admin/quizzes');
  sleep(Math.max(THINK, 1));
}

// ---- 部品 ----

function get(token, area, path, name) {
  return send(token, 'GET', path, name, 200, undefined, area);
}

function send(token, method, path, name, expected, body, area = 'play') {
  const res = http.request(method, `${BASE_URL}/api/t/${TENANT}${path}`, body === undefined ? null : JSON.stringify(body), {
    headers: { ...auth(token), 'Content-Type': 'application/json' },
    tags: { name, area },
  });
  if (!check(res, { [`${name} が ${expected}`]: (r) => r.status === expected })) {
    // 失敗したら 1 秒待つ。手元の通信が切れたとき、待たずに繰り返すと、1 秒に数千回の失敗でログが埋まる
    sleep(1);
    return null;
  }
  return res.body ? res.json() : {};
}

function auth(token) {
  return { Authorization: `Bearer ${token}` };
}

/** VU ごとに決まった一般ユーザーを使う */
function member() {
  return MEMBERS[(exec.vu.idInInstance - 1) % MEMBERS.length];
}

function steps(targets, duration) {
  return {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: targets.flatMap((target) => [
      { duration: '30s', target },
      { duration, target },
    ]),
    gracefulRampDown: '30s',
  };
}

function pick(items) {
  return items[Math.floor(Math.random() * items.length)];
}

function required(name) {
  const value = __ENV[name];
  if (!value) throw new Error(`環境変数 ${name} を渡してください`);
  return value;
}
