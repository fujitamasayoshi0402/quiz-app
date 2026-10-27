import { useRouter } from "expo-router";
import { Text } from "react-native";
import { Button, Screen, styles } from "@/components/ui";

/** 別の画面で挑戦が終わったとき（DEV-204）。解き続けても結果がないため、出題の画面へ戻る口だけを出す */
export const ENDED_ELSEWHERE = "このクイズは別の画面で終了しました。";

/** 破棄した挑戦には結果がない。完了 API は 409 を返す */
export const ABANDONED = "このクイズは破棄されたため、結果はありません。";

/** web の `AttemptEnded` */
export function AttemptEnded({ slug, message }: { slug: string; message: string }) {
  const router = useRouter();
  return (
    <Screen>
      <Text style={styles.text}>{message}</Text>
      <Button
        label="クイズを選ぶ画面に戻る"
        onPress={() => router.dismissTo({ pathname: "/t/[slug]/play", params: { slug } })}
      />
    </Screen>
  );
}
