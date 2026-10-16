import type { ExpoConfig } from "expo/config";

/**
 * アプリの設定（ADR-0032）。
 *
 * `scheme` は、ログインのあとに戻ってくる先（`quizapp://auth/callback`）。Cognito のアプリ用のクライアントに、同じものを登録してある（`modules/auth`）。
 * 接続先と Cognito の値は、ビルドのときに環境変数（`EXPO_PUBLIC_*`）で渡す（`.env.example`）。どれも秘密ではない
 */
const config: ExpoConfig = {
  name: "Quiz",
  slug: "quiz-app",
  scheme: "quizapp",
  version: "0.0.0",
  orientation: "portrait",
  icon: "./assets/icon.png",
  userInterfaceStyle: "light",
  ios: { supportsTablet: false, bundleIdentifier: "com.quizapp.mobile" },
  android: {
    package: "com.quizapp.mobile",
    adaptiveIcon: {
      backgroundColor: "#E6F4FE",
      foregroundImage: "./assets/android-icon-foreground.png",
      backgroundImage: "./assets/android-icon-background.png",
      monochromeImage: "./assets/android-icon-monochrome.png",
    },
  },
  plugins: ["expo-router", "expo-secure-store", "expo-web-browser"],
  experiments: { typedRoutes: true },
};

export default config;
