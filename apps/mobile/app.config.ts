import type { ExpoConfig } from "expo/config";

/**
 * アプリの設定（ADR-0032）。
 *
 * `scheme` は、ログインのあとに戻ってくる先（`quizapp://auth/callback`）。Cognito のアプリ用のクライアントに、同じものを登録してある（`modules/auth`）。
 * 接続先と Cognito の値は、ビルドのときに環境変数（`EXPO_PUBLIC_*`）で渡す（`.env.example`）。どれも秘密ではない
 *
 * 自分の端末に入れるときは、アプリの ID（`APP_ID`）と Apple のチーム（`APPLE_TEAM_ID`）も渡す（DEV-210）。
 * 無料の Apple ID で署名するとき、アプリの ID は Apple の中で重ならないものしか登録できず、既定の `com.quizapp.mobile` は使えない
 *
 * `extra.eas.projectId` は、Android の APK を作る EAS のプロジェクト（DEV-210）。秘密ではない
 */
const appId = process.env.APP_ID || "com.quizapp.mobile";

const config: ExpoConfig = {
  name: "Quiz",
  slug: "quiz-app",
  owner: "masayoshi_fujita",
  scheme: "quizapp",
  version: "0.0.0",
  orientation: "portrait",
  icon: "./assets/icon.png",
  userInterfaceStyle: "light",
  ios: { supportsTablet: false, bundleIdentifier: appId, appleTeamId: process.env.APPLE_TEAM_ID || undefined },
  android: {
    package: appId,
    adaptiveIcon: {
      backgroundColor: "#E6F4FE",
      foregroundImage: "./assets/android-icon-foreground.png",
      backgroundImage: "./assets/android-icon-background.png",
      monochromeImage: "./assets/android-icon-monochrome.png",
    },
  },
  plugins: ["expo-router", "expo-secure-store", "expo-web-browser"],
  experiments: { typedRoutes: true },
  extra: { eas: { projectId: "26cbb9e5-5f17-441a-9a24-ef03eed972d4" } },
};

export default config;
